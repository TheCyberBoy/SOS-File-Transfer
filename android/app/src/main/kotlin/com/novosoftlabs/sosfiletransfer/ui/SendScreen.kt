package com.novosoftlabs.sosfiletransfer.ui

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.novosoftlabs.sosfiletransfer.core.FrameHeader
import com.novosoftlabs.sosfiletransfer.core.LTEncoder
import com.novosoftlabs.sosfiletransfer.core.MAX_FILE_LABEL
import com.novosoftlabs.sosfiletransfer.core.fnv1a
import com.novosoftlabs.sosfiletransfer.core.packFile
import com.novosoftlabs.sosfiletransfer.core.packFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

// The web sender defaults to 2953 bytes/frame — the exact byte ceiling of a
// QR v40-L code, zero margin. That's fine for zxing-wasm (battle-tested at
// that exact boundary), but zxing-core's Java encoder hasn't been verified
// there and a boundary condition (mode/terminator bit accounting right at
// max capacity) is a very plausible source of an encode-time crash. Use the
// same safer fallback the web app's own README recommends when the max
// setting misbehaves — real margin below any version's ceiling — instead of
// chasing the untested boundary.
private const val FRAME_BYTES = 1465 // QR v27-L, comfortable margin below capacity
private const val BLOCK_LEN = FRAME_BYTES - com.novosoftlabs.sosfiletransfer.core.HEADER_LEN
private const val TX_FPS = 20L // conservative default for a first pass; web defaults to 60

private sealed interface SendPhase {
    data object Idle : SendPhase
    data object Preparing : SendPhase
    data class Streaming(val fileName: String, val status: String, val encoder: LTEncoder, val header: FrameHeader) : SendPhase
    data class Failed(val message: String) : SendPhase
}

@Composable
fun SendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf<SendPhase>(SendPhase.Idle) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var seq by remember { mutableStateOf(0) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        phase = SendPhase.Preparing
        pickedUri = uri
    }

    // All the actual work — file read, gzip, SHA-256, fountain-code block
    // allocation — happens here, off the main thread. Doing this inline in
    // the picker callback above is what crashed the app: that callback runs
    // on the UI thread, and a large-enough file turns a blocking read plus a
    // few full-size byte-array copies into either a frozen UI or an
    // OutOfMemoryError that a plain `catch (e: Exception)` never sees.
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        try {
            val (name, type, bytes) = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val readBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read the selected file.")
                val displayName = queryDisplayName(context, uri) ?: "file"
                val mimeType = resolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(displayName.substringAfterLast('.', ""))
                    ?: "application/octet-stream"
                Triple(displayName, mimeType, readBytes)
            }

            val (encoder, header, statusLine) = withContext(Dispatchers.Default) {
                val packed = packFile(name, type, bytes)
                val sessionId = Random.nextInt(1, 0xffff)
                val enc = LTEncoder(packed.container, BLOCK_LEN, sessionId)
                val hdr = FrameHeader(
                    sessionId = sessionId,
                    seq = 0,
                    k = enc.k,
                    blockLen = BLOCK_LEN,
                    totalLen = packed.container.size,
                    payloadFnv = fnv1a(packed.container),
                )
                val status = "$TX_FPS FPS · $FRAME_BYTES bytes/frame · ${packed.compression} · K=${enc.k}"
                Triple(enc, hdr, status)
            }

            seq = 0
            frameBitmap = null
            phase = SendPhase.Streaming(name, statusLine, encoder, header)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("SendScreen", "OOM preparing file", e)
            phase = SendPhase.Failed("That file is too large for this device to prepare right now — try a smaller one.")
        } catch (e: Throwable) {
            // Throwable, not Exception — a library-internal Error (e.g. from
            // zxing/ML Kit's native bits) must never take the whole app down
            // silently. Logged so `adb logcat` shows the real cause instead
            // of just a generic message on screen.
            android.util.Log.e("SendScreen", "Failed to prepare file for sending", e)
            phase = SendPhase.Failed(e.message ?: "Could not prepare that file.")
        }
    }

    // Drives the animated QR stream once a file is selected — regenerates a
    // new frame every tick, same idea as the web sender's rAF loop, just on a
    // fixed-rate coroutine delay instead. Wrapped in try/catch so a bad frame
    // shows an error instead of taking the whole app down with it.
    LaunchedEffect(phase) {
        val streaming = phase as? SendPhase.Streaming ?: return@LaunchedEffect
        while (true) {
            try {
                val block = streaming.encoder.encode(seq)
                val frame = packFrame(streaming.header.copy(seq = seq), block)
                frameBitmap = withContext(Dispatchers.Default) { renderQrBitmap(frame) }
                seq++
            } catch (e: Throwable) {
                android.util.Log.e("SendScreen", "Failed to render QR frame $seq", e)
                phase = SendPhase.Failed(e.message ?: "The stream stopped unexpectedly.")
                return@LaunchedEffect
            }
            delay(1000L / TX_FPS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Send a file", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)

        val statusText = when (val p = phase) {
            is SendPhase.Idle -> "Choose a file to begin"
            is SendPhase.Preparing -> "Preparing…"
            is SendPhase.Streaming -> p.status
            is SendPhase.Failed -> p.message
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (phase is SendPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = { pickFile.launch("*/*") }, enabled = phase !is SendPhase.Preparing) {
            val label = (phase as? SendPhase.Streaming)?.fileName
            Text(label ?: "Choose file (up to $MAX_FILE_LABEL)")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                phase is SendPhase.Preparing -> CircularProgressIndicator()
                frameBitmap != null -> Image(
                    bitmap = frameBitmap!!.asImageBitmap(),
                    contentDescription = "Animated QR code carrying the file",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                )
                else -> Text(
                    "Your animated code will appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return null
}

/** Mask pattern is left to zxing's own evaluation (unlike the web sender's
 *  pinned mask) — see the Android porting notes on why that optimization is
 *  JS-specific and not obviously worth chasing here; any valid mask decodes
 *  the same either way. */
private fun renderQrBitmap(frame: ByteArray): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 4,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
    )
    val text = String(frame, Charsets.ISO_8859_1) // byte-preserving, matches the web sender's "byte" mode segment
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, hints)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}
