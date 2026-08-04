package com.novosoftlabs.sosfiletransfer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.novosoftlabs.sosfiletransfer.camera.QrFrameAnalyzer
import com.novosoftlabs.sosfiletransfer.core.LTDecoder
import com.novosoftlabs.sosfiletransfer.core.fnv1a
import com.novosoftlabs.sosfiletransfer.core.isSnippet
import com.novosoftlabs.sosfiletransfer.core.parseFrame
import com.novosoftlabs.sosfiletransfer.core.snippetText
import com.novosoftlabs.sosfiletransfer.core.streamIdentity
import com.novosoftlabs.sosfiletransfer.core.unpackFile
import com.novosoftlabs.sosfiletransfer.core.verifyFile
import com.novosoftlabs.sosfiletransfer.transfer.TransferProgressEstimate
import com.novosoftlabs.sosfiletransfer.transfer.estimateTransferProgress
import com.novosoftlabs.sosfiletransfer.transfer.formatDuration
import com.novosoftlabs.sosfiletransfer.transfer.goodputKbs
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ReceiveState(
    val status: String = "Ready to scan a file or text stream",
    val decoder: LTDecoder? = null,
    val streamKey: String = "",
    val startTs: Long = 0L,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val etaLabel: String = "",
    val done: Boolean = false,
    val resultText: String? = null,
    val savedName: String? = null,
    val savedUri: Uri? = null,
    val savedMimeType: String? = null,
    val previewBitmap: Bitmap? = null,
    val error: String? = null,
)

/** What finishing a completed transfer can produce — kept off the main
 *  thread (unpack/gzip/SHA-256/disk write/image decode are all real work),
 *  then applied to [ReceiveState] once back on the composition's dispatcher. */
private sealed interface FinishResult {
    data class Snippet(val text: String) : FinishResult
    data class SavedFile(val name: String, val uri: Uri, val mimeType: String, val previewBitmap: Bitmap?) : FinishResult
    data class Error(val message: String) : FinishResult
}

private suspend fun finishReceivedTransfer(context: android.content.Context, payload: ByteArray): FinishResult =
    withContext(Dispatchers.Default) {
        try {
            val file = unpackFile(payload)
            if (!verifyFile(file)) throw IllegalStateException("SHA-256 verification failed.")
            if (isSnippet(file)) {
                FinishResult.Snippet(snippetText(file))
            } else {
                // Same idea as the web receiver showing an <img> for
                // image/* payloads (receive/main.ts) — decoded here, off
                // the main thread, since BitmapFactory isn't free for a
                // multi-megabyte photo.
                val preview = if (file.type.startsWith("image/")) {
                    try {
                        BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size)
                    } catch (e: Throwable) {
                        android.util.Log.e("ReceiveScreen", "Failed to decode image preview", e)
                        null
                    }
                } else {
                    null
                }
                val savedUri = withContext(Dispatchers.IO) { saveToDownloads(context, file.name, file.type, file.bytes) }
                FinishResult.SavedFile(file.name, savedUri, file.type, preview)
            }
        } catch (e: Throwable) {
            android.util.Log.e("ReceiveScreen", "Failed to unpack/save received file", e)
            FinishResult.Error(e.message ?: "Transfer failed.")
        }
    }

@Composable
fun ReceiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) { if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA) }

    var state by remember { mutableStateOf(ReceiveState()) }
    val stateRef = rememberUpdatedState(state)
    val scope = rememberCoroutineScope()

    fun onFrameDecoded(bytes: ByteArray) {
        // Runs off the ML Kit analyzer callback on every decoded frame — any
        // uncaught throw here (a malformed frame, a corrupt in-flight
        // decoder state) would crash the whole app mid-scan, so nothing
        // escapes this function silently.
        try {
            val parsed = parseFrame(bytes) ?: return
            if (stateRef.value.done) return
            val identity = streamIdentity(parsed.header)
            var current = stateRef.value
            if (current.decoder == null || current.streamKey != identity) {
                current = current.copy(
                    decoder = LTDecoder(parsed.header.k, parsed.header.blockLen, parsed.header.sessionId, parsed.header.totalLen),
                    streamKey = identity,
                    startTs = System.nanoTime(),
                    status = "Receiving…",
                )
            }
            val decoder = current.decoder!!
            decoder.addFrame(parsed.header.seq, parsed.block)

            val elapsedSeconds = maxOf(0.0, (System.nanoTime() - current.startTs) / 1_000_000_000.0)
            val estimate = estimateTransferProgress(decoder.k, decoder.framesNew, elapsedSeconds, decoder.solvedCount)
            val percent = estimate.fraction * 100
            val shownPercent = if (percent < 10) {
                String.format(Locale.US, "%.1f", percent)
            } else {
                String.format(Locale.US, "%.0f", percent)
            }
            // Held back for the first few frames — a two-frame sample reads wildly wrong.
            val rate = if (decoder.framesNew >= 4) {
                val kbs = goodputKbs(decoder.framesNew, decoder.blockLen, decoder.k, elapsedSeconds)
                " · ${String.format(Locale.US, "%.1f", kbs)} KB/s"
            } else {
                ""
            }
            val etaLabel = (
                if (estimate.etaSeconds == null) {
                    if (estimate.phase == TransferProgressEstimate.Phase.DECODING) {
                        "${decoder.framesNew} frames · decoding"
                    } else {
                        "Estimating time…"
                    }
                } else {
                    "About ${formatDuration(estimate.etaSeconds)} · ${decoder.framesNew} frames"
                }
                ) + rate

            current = current.copy(
                progress = estimate.fraction.toFloat(),
                progressLabel = "$shownPercent% · ${decoder.solvedCount}/${decoder.k} blocks",
                etaLabel = etaLabel,
            )

            if (decoder.isComplete) {
                val payload = decoder.assemble()!!
                val seconds = maxOf(0.0, (System.nanoTime() - current.startTs) / 1_000_000_000.0)
                val ok = fnv1a(payload) == parsed.header.payloadFnv
                // done = true now, before any of the heavier finishing work
                // below runs — this callback fires on the main thread (ML
                // Kit's Task listeners default there), so nothing past this
                // point may block it: unpack/verify/save/decode all move to
                // a coroutine on Dispatchers.Default/IO instead.
                current = current.copy(
                    done = true,
                    status = if (ok) "Finishing…" else "Done",
                    progress = 1f,
                    progressLabel = "100%",
                    etaLabel = "${formatDuration(seconds)} total",
                )
                state = current
                if (!ok) {
                    state = state.copy(status = "Done", error = "The optical stream checksum did not match.")
                } else {
                    scope.launch {
                        when (val result = finishReceivedTransfer(context, payload)) {
                            is FinishResult.Snippet -> state = state.copy(
                                status = "Done",
                                resultText = result.text,
                                progressLabel = "100% · text recovered",
                            )
                            is FinishResult.SavedFile -> state = state.copy(
                                status = "Done",
                                savedName = result.name,
                                savedUri = result.uri,
                                savedMimeType = result.mimeType,
                                previewBitmap = result.previewBitmap,
                                progressLabel = "100% · file recovered",
                            )
                            is FinishResult.Error -> state = state.copy(status = "Done", error = result.message)
                        }
                    }
                }
                return
            }
            state = current
        } catch (e: Throwable) {
            android.util.Log.e("ReceiveScreen", "Failed to process decoded frame", e)
            state = stateRef.value.copy(done = true, error = e.message ?: "The optical stream failed unexpectedly.")
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
        Text("Receive", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (hasPermission) {
            CameraPreview(onFrame = ::onFrameDecoded)
            if (state.decoder != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        state.progressLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        state.etaLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Text("Camera permission is required to receive.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) { Text("Grant camera access") }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.resultText?.let {
            Text("Text received:", style = MaterialTheme.typography.titleLarge)
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
        state.savedName?.let {
            Text("Saved to Downloads: $it", color = MaterialTheme.colorScheme.primary)
        }
        state.previewBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Preview of the received file",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
            )
        }
        if (state.savedUri != null) {
            Button(onClick = {
                val opened = openReceivedFile(context, state.savedUri!!, state.savedMimeType)
                if (!opened) state = state.copy(error = "No app on this device can open that file type.")
            }) {
                Text("Open ${state.savedName}")
            }
        }

        Text(
            "SOS File Transfer · by Novosoft Labs",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun CameraPreview(onFrame: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val onFrameState = rememberUpdatedState(onFrame)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var focusTap by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                // Tap-to-focus, the same gesture any camera app offers — a QR
                // stream is small, high-contrast, and often not what the
                // sensor's own region-of-interest guess lands on, so handing
                // the user a direct way to re-aim focus at it is a real fix
                // for "camera won't lock on," not just a nicety.
                detectTapGestures { offset ->
                    val view = previewView ?: return@detectTapGestures
                    val cam = camera ?: return@detectTapGestures
                    val point = view.meteringPointFactory.createPoint(offset.x, offset.y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    focusTap = offset
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val newPreviewView = PreviewView(ctx)
                previewView = newPreviewView
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val previewBuilder = Preview.Builder()
                    // CameraX's own default continuous-AF mode is tuned for
                    // still photos (prioritizes settling accuracy over
                    // speed). CONTINUOUS_VIDEO converges faster and hunts
                    // less — the mode Google's own ML Kit + CameraX barcode
                    // samples use, since a photo-tuned AF is the wrong
                    // trade-off for a live scan.
                    //
                    // CONTROL_AE_TARGET_FPS_RANGE addresses a different, real
                    // failure mode: in a dim room, the default auto-exposure
                    // is free to lengthen exposure time per frame instead of
                    // raising ISO gain to stay bright — which silently caps
                    // the achievable frame rate far below whatever fps the
                    // sender is offering, well before anything about focus
                    // or distance comes into play. Pinning a target range
                    // forces AE to hold frame rate and compensate with gain
                    // (more noise per frame) instead, trading per-frame
                    // image quality for throughput — the right trade here,
                    // since the fountain code already discards a bad frame
                    // for free, but a frame the camera never captured at all
                    // costs real time.
                    Camera2Interop.Extender(previewBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                        )
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(20, 30),
                        )
                    val preview = previewBuilder.build().also {
                        it.setSurfaceProvider(newPreviewView.surfaceProvider)
                    }
                    // Without an explicit target, CameraX picks a default
                    // ImageAnalysis resolution per-device — on some sensors
                    // that's well above what a QR scan needs, and ML Kit's
                    // per-frame cost scales with pixel count, so this is
                    // still capped rather than left uncapped. 1280x720 was
                    // enough for a single QR code filling most of the
                    // frame, but the sender can now show a grid of several
                    // — with 4 codes in a 2x2 grid, each one only gets
                    // roughly a quarter of that pixel budget, which was
                    // dropping their effective module size below what ML
                    // Kit could reliably resolve. 1920x1080 gives each code
                    // in a grid room to stay sharp; the receiver has no way
                    // to know the sender's codes-per-frame setting (this is
                    // a one-way link), so this has to cover the multi-code
                    // case unconditionally rather than sizing to it.
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(resolutionSelector)
                        .build()
                        .also { it.setAnalyzer(executor, QrFrameAnalyzer { bytes -> onFrameState.value(bytes) }) }
                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (_: Exception) {
                        // Surfaced to the user via the status line in a later pass;
                        // for now the preview simply stays blank if binding fails.
                    }
                }, ContextCompat.getMainExecutor(ctx))
                newPreviewView
            },
        )
        focusTap?.let { offset -> FocusRing(offset) { focusTap = null } }
    }
}

/** Brief ring at the tap point — the same "focus locked here" feedback a
 *  real camera app gives, so tapping doesn't feel like it did nothing. */
@Composable
private fun FocusRing(offset: Offset, onFinished: () -> Unit) {
    var expanded by remember(offset) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (expanded) 1f else 1.4f, tween(250), label = "focusRingScale")
    val alpha by animateFloatAsState(if (expanded) 1f else 0f, tween(250), label = "focusRingAlpha")

    LaunchedEffect(offset) {
        expanded = true
        delay(700)
        expanded = false
        delay(250)
        onFinished()
    }

    Box(
        modifier = Modifier
            .offset { IntOffset((offset.x - 28.dp.toPx()).roundToInt(), (offset.y - 28.dp.toPx()).roundToInt()) }
            .size(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .border(2.dp, androidx.compose.ui.graphics.Color.White, CircleShape),
    )
}

/** Hands the saved file to whatever app the OS considers the right viewer
 *  for its media type — a video player, image viewer, PDF reader, etc.
 *  Saving to Downloads alone (the previous behavior) left the user with
 *  nothing but a filename on screen for anything that wasn't an image; this
 *  is the same "tap to open" a received file gets in any messaging app. */
private fun openReceivedFile(context: android.content.Context, uri: Uri, mimeType: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        android.util.Log.e("ReceiveScreen", "No app can open $mimeType", e)
        false
    }
}

/** Saves a received file into the public Downloads collection via
 *  MediaStore — the Android equivalent of the web receiver's `download`
 *  attribute, without needing broad storage permissions on API 29+. */
private fun saveToDownloads(context: android.content.Context, name: String, mimeType: String, bytes: ByteArray): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Could not create the download.")
    resolver.openOutputStream(uri)?.use { it.write(bytes) }
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}
