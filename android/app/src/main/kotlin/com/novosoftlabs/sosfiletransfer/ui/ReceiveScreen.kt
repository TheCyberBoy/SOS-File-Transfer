package com.novosoftlabs.sosfiletransfer.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
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
    val previewBitmap: Bitmap? = null,
    val error: String? = null,
)

/** What finishing a completed transfer can produce — kept off the main
 *  thread (unpack/gzip/SHA-256/disk write/image decode are all real work),
 *  then applied to [ReceiveState] once back on the composition's dispatcher. */
private sealed interface FinishResult {
    data class Snippet(val text: String) : FinishResult
    data class SavedFile(val name: String, val previewBitmap: Bitmap?) : FinishResult
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
                val saved = withContext(Dispatchers.IO) { saveToDownloads(context, file.name, file.type, file.bytes) }
                FinishResult.SavedFile(saved, preview)
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
    }
}

@Composable
private fun CameraPreview(onFrame: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val onFrameState = rememberUpdatedState(onFrame)

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(16.dp)),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // Without an explicit target, CameraX picks a default
                // ImageAnalysis resolution per-device — on some sensors
                // that's well above what a QR scan needs, and ML Kit's
                // per-frame cost scales with pixel count. 1280x720 is
                // comfortably enough detail to read a dense QR code at
                // arm's length while keeping decode latency low and
                // predictable across devices.
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.setAnalyzer(executor, QrFrameAnalyzer { bytes -> onFrameState.value(bytes) }) }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
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
            previewView
        },
    )
}

/** Saves a received file into the public Downloads collection via
 *  MediaStore — the Android equivalent of the web receiver's `download`
 *  attribute, without needing broad storage permissions on API 29+. */
private fun saveToDownloads(context: android.content.Context, name: String, mimeType: String, bytes: ByteArray): String {
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
    return name
}
