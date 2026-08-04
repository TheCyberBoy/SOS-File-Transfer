package com.novosoftlabs.sosfiletransfer.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import java.util.concurrent.Executors

private data class ReceiveState(
    val status: String = "Ready to scan a file or text stream",
    val decoder: LTDecoder? = null,
    val streamKey: String = "",
    val progress: Float = 0f,
    val done: Boolean = false,
    val resultText: String? = null,
    val savedName: String? = null,
    val error: String? = null,
)

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
                )
            }
            val decoder = current.decoder!!
            decoder.addFrame(parsed.header.seq, parsed.block)
            val progress = decoder.solvedCount.toFloat() / decoder.k.toFloat()
            current = current.copy(
                progress = progress,
                status = "${decoder.solvedCount}/${decoder.k} blocks · ${decoder.framesNew} frames",
            )

            if (decoder.isComplete) {
                val payload = decoder.assemble()!!
                val ok = fnv1a(payload) == parsed.header.payloadFnv
                current = if (!ok) {
                    current.copy(done = true, error = "The optical stream checksum did not match.")
                } else {
                    try {
                        val file = unpackFile(payload)
                        if (!verifyFile(file)) throw IllegalStateException("SHA-256 verification failed.")
                        if (isSnippet(file)) {
                            current.copy(done = true, resultText = snippetText(file))
                        } else {
                            val saved = saveToDownloads(context, file.name, file.type, file.bytes)
                            current.copy(done = true, savedName = saved)
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("ReceiveScreen", "Failed to unpack/save received file", e)
                        current.copy(done = true, error = e.message ?: "Transfer failed.")
                    }
                }
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Receive", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (hasPermission) {
            CameraPreview(onFrame = ::onFrameDecoded)
            if (state.decoder != null && !state.done) {
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
