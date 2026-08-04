package com.novosoftlabs.sosfiletransfer.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * One CameraX frame in flight at a time — busy means drop, exactly like the
 * web receiver's worker pool (receive/worker.ts): a dropped frame costs
 * nothing, the fountain code absorbs it, and queueing stale frames behind a
 * busy decoder only adds latency.
 */
class QrFrameAnalyzer(private val onDecoded: (ByteArray) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()
    @Volatile private var busy = false

    override fun analyze(imageProxy: ImageProxy) {
        if (busy || imageProxy.image == null) {
            imageProxy.close()
            return
        }
        busy = true
        val image = InputImage.fromMediaImage(imageProxy.image!!, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawBytes
                if (raw != null) onDecoded(raw)
            }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }
}
