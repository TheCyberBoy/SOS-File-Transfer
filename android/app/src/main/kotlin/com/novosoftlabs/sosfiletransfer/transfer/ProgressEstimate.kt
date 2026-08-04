package com.novosoftlabs.sosfiletransfer.transfer

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Port of shared/progress.ts — the receiver's live speed/ETA/percentage
 * readout. Pure display math, not part of the wire protocol, so unlike
 * :core this doesn't need bit-identical cross-platform verification; it
 * just needs to match the web receiver's behavior closely enough that the
 * two apps feel like the same product.
 */

/**
 * Distinct frames per source block an LT stream needs, as a function of k.
 * See shared/progress.ts for the empirical curve this approximates —
 * `1.1 + 2.45/sqrt(k)` tracks the measured p50 from k≈50 up, clamped at
 * both ends.
 */
fun expectedFountainOverhead(sourceBlocks: Int): Double {
    val k = max(1, sourceBlocks).toDouble()
    return min(1.6, max(1.15, 1.1 + 2.45 / sqrt(k)))
}

data class TransferProgressEstimate(
    val fraction: Double,
    val expectedFrames: Int,
    val etaSeconds: Double?,
    val phase: Phase,
) {
    enum class Phase { COLLECTING, DECODING }
}

fun estimateTransferProgress(
    sourceBlocks: Int,
    uniqueFrames: Int,
    elapsedSeconds: Double,
    solvedBlocks: Int = 0,
): TransferProgressEstimate {
    val minimumFrames = max(1, sourceBlocks)
    val expectedFrames = max(
        minimumFrames + 1,
        ceil(minimumFrames * expectedFountainOverhead(minimumFrames)).toInt(),
    )
    val expectedRedundancy = expectedFrames - minimumFrames

    // Frames drive a continuously moving baseline: 0-86% while collecting
    // the theoretical minimum, 86-96% through the expected redundancy, then
    // an asymptotic 96-99% if this particular stream needs more. Actual
    // decoded blocks can move the bar further ahead at any time.
    val frameFraction: Double = when {
        uniqueFrames < minimumFrames -> 0.86 * (uniqueFrames.toDouble() / minimumFrames)
        uniqueFrames <= expectedFrames ->
            0.86 + 0.1 * ((uniqueFrames - minimumFrames).toDouble() / expectedRedundancy)
        else -> {
            val extra = (uniqueFrames - expectedFrames).toDouble() / expectedRedundancy
            0.96 + 0.03 * (1 - exp(-extra))
        }
    }
    val decodedFraction = 0.99 * min(1.0, solvedBlocks.toDouble() / minimumFrames)
    val fraction = min(0.99, max(frameFraction, decodedFraction))
    val phase = if (uniqueFrames < minimumFrames) {
        TransferProgressEstimate.Phase.COLLECTING
    } else {
        TransferProgressEstimate.Phase.DECODING
    }
    val rate = if (elapsedSeconds > 0) uniqueFrames / elapsedSeconds else 0.0

    // Past the expected frame count the stream is running long — poor
    // light, motion blur, a camera that won't hold focus. That's exactly
    // when someone is watching the bar wondering if it stalled, so keep
    // quoting a time instead of going silent: extend the target one
    // redundancy block at a time.
    val overshoot = uniqueFrames - expectedFrames
    val target = if (overshoot < 0) {
        expectedFrames
    } else {
        expectedFrames + expectedRedundancy * (floor(overshoot.toDouble() / expectedRedundancy).toInt() + 1)
    }
    val etaSeconds = if (uniqueFrames >= 3 && elapsedSeconds >= 1 && rate > 0) {
        (target - uniqueFrames) / rate
    } else {
        null
    }
    return TransferProgressEstimate(fraction, expectedFrames, etaSeconds, phase)
}

/** Payload KB/s, discounting the frames the fountain spends on overhead. */
fun goodputKbs(framesNew: Int, blockLen: Int, sourceBlocks: Int, elapsedSeconds: Double): Double {
    return (framesNew.toDouble() * blockLen) /
        expectedFountainOverhead(sourceBlocks) /
        1024.0 /
        max(0.1, elapsedSeconds)
}

fun formatDuration(seconds: Double): String {
    val rounded = max(1.0, ceil(seconds)).toInt()
    if (rounded < 60) return "${rounded}s"
    val minutes = rounded / 60
    val remainder = rounded % 60
    if (minutes < 60) return if (remainder == 0) "${minutes}m" else "${minutes}m ${remainder}s"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (remainingMinutes == 0) "${hours}h" else "${hours}h ${remainingMinutes}m"
}
