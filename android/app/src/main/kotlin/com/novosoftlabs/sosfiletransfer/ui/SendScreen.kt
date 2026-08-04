package com.novosoftlabs.sosfiletransfer.ui

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.novosoftlabs.sosfiletransfer.core.HEADER_LEN
import com.novosoftlabs.sosfiletransfer.core.LTEncoder
import com.novosoftlabs.sosfiletransfer.core.MAX_FILE_LABEL
import com.novosoftlabs.sosfiletransfer.core.fnv1a
import com.novosoftlabs.sosfiletransfer.core.packFile
import com.novosoftlabs.sosfiletransfer.core.packFrame
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Same option lists as shared/send-settings.ts on the web side, so the two
// sender UIs offer identical tuning. 2953 (QR v40-L, zero margin) is kept
// as an option since it's the web app's own default and works fine in
// zxing-wasm — just not the *Android default*, since that exact boundary
// hasn't been confirmed against zxing-core's Java encoder yet.
private val FRAME_BYTES_OPTIONS = listOf(500, 1000, 1465, 1850, 2331, 2953)
private val TX_FPS_OPTIONS = listOf(10, 15, 20, 24, 30, 60)
private const val DEFAULT_FRAME_BYTES = 1465 // QR v27-L, comfortable margin below capacity
// 24, not 20 — this is literally the web app's own "if it's struggling"
// recovery pair (README: "bytes / frame → 1465, tx fps → 24"), so it's
// already the conservative choice, not a risk. The real throughput limiter
// on a fountain-coded link is how fast the *receiver* can decode frames,
// not how many the sender offers — see QrFrameAnalyzer's format
// restriction and ReceiveScreen's resolution cap for the actual speed fix.
private const val DEFAULT_TX_FPS = 24

// Showing more than one independent QR code per tick is a near-free
// throughput multiplier: a phone screen has far more pixel budget than one
// QR code uses, ML Kit's process() already returns every code it finds in
// a frame (not just one — see QrFrameAnalyzer), and each code is still just
// a standalone fountain frame, so this needs no protocol change at all.
// Same "visual MIMO" idea as the screen-camera VLC literature (COBRA,
// RDCode) and the QR-grid technique used for bulk document capture — just
// applied to this pipeline. 4 is a conservative default: more codes means
// each one is physically smaller on screen, which trades off against
// decode reliability at typical hand-held distance (the same scalability
// concern the "Strata" screen-camera paper is built around), so it's a
// tunable setting rather than a fixed maximum.
private val CODES_PER_FRAME_OPTIONS = listOf(1, 2, 4, 6, 9)
private const val DEFAULT_CODES_PER_FRAME = 4

private sealed interface SendPhase {
    data object Idle : SendPhase
    data object Preparing : SendPhase
    data class Streaming(val fileName: String, val compression: String, val encoder: LTEncoder, val header: FrameHeader) : SendPhase
    data class Failed(val message: String) : SendPhase
}

private data class PickedFile(val name: String, val type: String, val bytes: ByteArray)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf<SendPhase>(SendPhase.Idle) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFile by remember { mutableStateOf<PickedFile?>(null) }
    var frameBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var seq by remember { mutableStateOf(0) }
    var frameBytes by remember { mutableStateOf(DEFAULT_FRAME_BYTES) }
    var txFps by remember { mutableStateOf(DEFAULT_TX_FPS) }
    var codesPerFrame by remember { mutableStateOf(DEFAULT_CODES_PER_FRAME) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        phase = SendPhase.Preparing
        pickedFile = null
        pickedUri = uri
    }

    // The file read — off the main thread. Doing this inline in the picker
    // callback above is what originally crashed the app: that callback runs
    // on the UI thread, and a large-enough file turns a blocking read into
    // a frozen UI or an OutOfMemoryError a plain `catch (e: Exception)`
    // never sees. Kept separate from encoding below so changing a setting
    // (bytes/frame) doesn't require re-reading the file from disk.
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        try {
            pickedFile = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val readBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read the selected file.")
                val displayName = queryDisplayName(context, uri) ?: "file"
                val mimeType = resolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(displayName.substringAfterLast('.', ""))
                    ?: "application/octet-stream"
                PickedFile(displayName, mimeType, readBytes)
            }
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("SendScreen", "OOM reading file", e)
            phase = SendPhase.Failed("That file is too large for this device to prepare right now — try a smaller one.")
        } catch (e: Throwable) {
            android.util.Log.e("SendScreen", "Failed to read file for sending", e)
            phase = SendPhase.Failed(e.message ?: "Could not read that file.")
        }
    }

    // Fountain-encodes the picked file into blocks sized for the current
    // frameBytes setting. Keyed on both, so changing bytes/frame while a
    // file is already picked re-encodes with the new block size instead of
    // requiring a fresh pick — the same "changing a setting restarts the
    // stream" behavior the web sender has.
    LaunchedEffect(pickedFile, frameBytes) {
        val file = pickedFile ?: return@LaunchedEffect
        val blockLen = frameBytes - HEADER_LEN
        try {
            val (encoder, header, compression) = withContext(Dispatchers.Default) {
                val packed = packFile(file.name, file.type, file.bytes)
                val sessionId = Random.nextInt(1, 0xffff)
                val enc = LTEncoder(packed.container, blockLen, sessionId)
                val hdr = FrameHeader(
                    sessionId = sessionId,
                    seq = 0,
                    k = enc.k,
                    blockLen = blockLen,
                    totalLen = packed.container.size,
                    payloadFnv = fnv1a(packed.container),
                )
                Triple(enc, hdr, packed.compression.name)
            }

            seq = 0
            frameBitmaps = emptyList()
            phase = SendPhase.Streaming(file.name, compression, encoder, header)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("SendScreen", "OOM encoding file", e)
            phase = SendPhase.Failed("That file is too large for this device to prepare right now — try a smaller one.")
        } catch (e: Throwable) {
            // Throwable, not Exception — a library-internal Error (e.g. from
            // zxing's native bits) must never take the whole app down
            // silently. Logged so `adb logcat` shows the real cause instead
            // of just a generic message on screen.
            android.util.Log.e("SendScreen", "Failed to prepare file for sending", e)
            phase = SendPhase.Failed(e.message ?: "Could not prepare that file.")
        }
    }

    // Drives the animated QR stream once a file is selected — regenerates a
    // grid of frames every tick, same idea as the web sender's rAF loop,
    // just on a fixed-rate coroutine delay instead. Wrapped in try/catch so
    // a bad frame shows an error instead of taking the whole app down with
    // it. txFps and codesPerFrame are read fresh every loop iteration, so
    // adjusting either mid-stream takes effect on the next tick without
    // restarting the coroutine.
    LaunchedEffect(phase) {
        val streaming = phase as? SendPhase.Streaming ?: return@LaunchedEffect
        while (true) {
            try {
                val n = codesPerFrame.coerceAtLeast(1)
                val baseSeq = seq
                frameBitmaps = withContext(Dispatchers.Default) {
                    (0 until n).map { i ->
                        val frameSeq = baseSeq + i
                        val block = streaming.encoder.encode(frameSeq)
                        val frame = packFrame(streaming.header.copy(seq = frameSeq), block)
                        renderQrBitmap(frame)
                    }
                }
                seq = baseSeq + n
            } catch (e: Throwable) {
                android.util.Log.e("SendScreen", "Failed to render QR frame $seq", e)
                phase = SendPhase.Failed(e.message ?: "The stream stopped unexpectedly.")
                return@LaunchedEffect
            }
            delay(1000L / txFps)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Send a file", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)

        val statusText = when (val p = phase) {
            is SendPhase.Idle -> "Choose a file to begin"
            is SendPhase.Preparing -> "Preparing…"
            is SendPhase.Streaming -> "$txFps FPS × $codesPerFrame codes · $frameBytes bytes/frame · ${p.compression} · K=${p.header.k}"
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

        if (frameBitmaps.isNotEmpty()) {
            QrGrid(frameBitmaps, modifier = Modifier.fillMaxWidth())
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (phase is SendPhase.Preparing) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        "Your animated code will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        // Settings — bytes/frame and codes/frame both re-encode/re-render
        // the current file at the new size (see the LaunchedEffect(phase)
        // loop above, which reads codesPerFrame fresh every tick, and the
        // LaunchedEffect(pickedFile, frameBytes) above that); tx fps just
        // changes the delay between ticks and applies on the very next one
        // without interrupting the stream.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bytes per frame", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FRAME_BYTES_OPTIONS) { option ->
                    FilterChip(
                        selected = option == frameBytes,
                        onClick = { frameBytes = option },
                        label = { Text(option.toString()) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Codes per frame", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CODES_PER_FRAME_OPTIONS) { option ->
                    FilterChip(
                        selected = option == codesPerFrame,
                        onClick = { codesPerFrame = option },
                        label = { Text(option.toString()) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TX frames per second", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TX_FPS_OPTIONS) { option ->
                    FilterChip(
                        selected = option == txFps,
                        onClick = { txFps = option },
                        label = { Text(option.toString()) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

/** Lays out N independent QR codes as a roughly square grid — each one is
 *  a standalone fountain frame with its own seq (see the streaming
 *  LaunchedEffect above), so the receiver just needs to decode as many of
 *  them per camera frame as it can (QrFrameAnalyzer already does). */
@Composable
private fun QrGrid(bitmaps: List<Bitmap>, modifier: Modifier = Modifier) {
    val cols = ceil(sqrt(bitmaps.size.toFloat())).toInt().coerceAtLeast(1)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bitmaps.chunked(cols).forEach { rowBitmaps ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowBitmaps.forEach { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Animated QR code carrying part of the file",
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                    )
                }
                // A partial last row (e.g. 4 codes over 3 columns) still
                // needs its cells sized like a full row, not stretched.
                repeat(cols - rowBitmaps.size) {
                    Box(modifier = Modifier.weight(1f))
                }
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
    val width = matrix.width
    val height = matrix.height
    // Bitmap.setPixel() crosses the JNI boundary once per call — doing that
    // 512*512 (262,144) times per frame, every frame, is real per-frame cost
    // stacked directly against the send loop's frame budget (e.g. ~42ms at
    // 24 fps). Filling a plain IntArray in Kotlin and writing it in one
    // setPixels() call is a single JNI transfer instead of a quarter
    // million of them.
    val pixels = IntArray(width * height)
    var i = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[i++] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    return bmp
}
