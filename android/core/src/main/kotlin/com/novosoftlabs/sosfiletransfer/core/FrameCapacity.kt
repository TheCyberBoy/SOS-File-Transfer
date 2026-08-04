package com.novosoftlabs.sosfiletransfer.core

// Port of shared/frame-capacity.ts — how much payload fits in a stream at a
// given frame size. k is a u16 in the frame header, so a large payload at a
// small bytes-per-frame runs out of block numbers before it runs out of the
// file size limit.

/** `k` is a u16 in the frame header. */
const val MAX_SOURCE_BLOCKS = 0xffff

/** Payload bytes per frame, once the header has taken its cut. */
fun blockLength(frameBytes: Int): Int = frameBytes - HEADER_LEN

/** Source blocks a payload splits into at this frame size. */
fun sourceBlockCount(payloadBytes: Int, frameBytes: Int): Int =
    Math.ceil(payloadBytes.toDouble() / blockLength(frameBytes)).toInt()

fun fitsInOneStream(payloadBytes: Int, frameBytes: Int): Boolean =
    sourceBlockCount(payloadBytes, frameBytes) <= MAX_SOURCE_BLOCKS

/** The smallest bytes-per-frame that can carry this payload at all. */
fun minimumFrameBytes(payloadBytes: Int): Int =
    Math.ceil(payloadBytes.toDouble() / MAX_SOURCE_BLOCKS).toInt() + HEADER_LEN

/** The smallest offered setting that works, so callers can name a value that
 *  is actually in a settings dropdown instead of the bare arithmetic minimum. */
fun smallestSufficientFrameSize(payloadBytes: Int, options: List<Int>): Int? {
    val minimum = minimumFrameBytes(payloadBytes)
    return options.filter { it >= minimum }.minOrNull()
}
