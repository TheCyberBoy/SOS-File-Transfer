package com.novosoftlabs.sosfiletransfer.ui

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.novosoftlabs.sosfiletransfer.core.LTEncoder
import com.novosoftlabs.sosfiletransfer.core.MAX_FILE_LABEL
import com.novosoftlabs.sosfiletransfer.core.fnv1a
import com.novosoftlabs.sosfiletransfer.core.packFile
import com.novosoftlabs.sosfiletransfer.core.packFrame
import com.novosoftlabs.sosfiletransfer.core.FrameHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val FRAME_BYTES = 2953 // QR v40 ceiling, same default as the web sender
private const val BLOCK_LEN = FRAME_BYTES - com.novosoftlabs.sosfiletransfer.core.HEADER_LEN
private const val TX_FPS = 20L // conservative default for a first pass; web defaults to 60

private data class SendState(
    val fileName: String = "",
    val status: String = "Choose a file to begin",
    val error: String? = null,
    val encoder: LTEncoder? = null,
    val header: FrameHeader? = null,
)

@Composable
fun SendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(SendState()) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var seq by remember { mutableStateOf(0) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not read the selected file.")
            val name = queryDisplayName(context, uri) ?: "file"
            val type = resolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
                ?: "application/octet-stream"

            val packed = packFile(name, type, bytes)
            val sessionId = Random.nextInt(1, 0xffff)
            val encoder = LTEncoder(packed.container, BLOCK_LEN, sessionId)
            val header = FrameHeader(
                sessionId = sessionId,
                seq = 0,
                k = encoder.k,
                blockLen = BLOCK_LEN,
                totalLen = packed.container.size,
                payloadFnv = fnv1a(packed.container),
            )
            seq = 0
            state = SendState(
                fileName = name,
                status = "$TX_FPS FPS · $FRAME_BYTES bytes/frame · ${packed.compression} · K=${encoder.k}",
                encoder = encoder,
                header = header,
            )
        } catch (e: Exception) {
            state = state.copy(error = e.message ?: "Could not prepare that file.")
        }
    }

    // Drives the animated QR stream once a file is selected — regenerates a
    // new frame every tick, same idea as the web sender's rAF loop, just on a
    // fixed-rate coroutine delay instead.
    LaunchedEffect(state.encoder) {
        val encoder = state.encoder ?: return@LaunchedEffect
        val header = state.header ?: return@LaunchedEffect
        while (true) {
            val block = encoder.encode(seq)
            val frame = packFrame(header.copy(seq = seq), block)
            frameBitmap = withContext(Dispatchers.Default) { renderQrBitmap(frame) }
            seq++
            kotlinx.coroutines.delay(1000L / TX_FPS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Send a file", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = { pickFile.launch("*/*") }) {
            Text(if (state.fileName.isEmpty()) "Choose file (up to $MAX_FILE_LABEL)" else state.fileName)
        }

        frameBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Animated QR code carrying the file",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            )
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
