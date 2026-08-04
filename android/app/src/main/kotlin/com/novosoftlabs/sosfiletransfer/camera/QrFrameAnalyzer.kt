package com.novosoftlabs.sosfiletransfer.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * One CameraX frame in flight at a time — busy means drop, exactly like the
 * web receiver's worker pool (receive/worker.ts): a dropped frame costs
 * nothing, the fountain code absorbs it, and queueing stale frames behind a
 * busy decoder only adds latency.
 *
 * Restricted to QR_CODE only: the default `BarcodeScanning.getClient()`
 * (no options) scans every format ML Kit supports — QR, EAN, Code128,
 * PDF417, Aztec, Data Matrix, etc — on every single frame, which is real,
 * measurable per-frame overhead this stream never needed. Effective decode
 * throughput, not the sender's fps setting, is what actually caps transfer
 * speed on a fountain-coded one-way link like this one.
 *
 * `process()` already returns every QR code ML Kit finds in the image, not
 * just one — this is the receiving half of the sender's multi-code grid
 * (SendScreen's QrGrid): each detected code is an independent fountain
 * frame with its own seq, so decoding all of them per camera frame instead
 * of only the first is a direct N× throughput multiplier with no protocol
 * change at all.
 */
class QrFrameAnalyzer(private val onDecoded: (ByteArray) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
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
                for (barcode in barcodes) {
                    val raw = barcode.rawBytes ?: continue
                    onDecoded(raw)
                }
            }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }
}
