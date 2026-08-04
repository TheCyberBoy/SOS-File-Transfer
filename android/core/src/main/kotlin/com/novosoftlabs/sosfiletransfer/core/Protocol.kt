package com.novosoftlabs.sosfiletransfer.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// Port of shared/protocol.ts. This is the wire format shared with the web
// sender/receiver — a byte or bit differing here means an Android device and
// a browser can no longer talk to each other. Every constant, field order and
// byte offset below must match protocol.ts exactly; see the module doc there
// for the frame layout diagram.

const val HEADER_LEN = 20
const val MAX_FILE_BYTES = 64 * 1024 * 1024
val MAX_FILE_LABEL = "${MAX_FILE_BYTES / 1024 / 1024} MB"

private const val FILE_HEADER_LEN = 49
private const val MAGIC0 = 0xd1
private const val MAGIC1 = 0x0c
private val FILE_MAGIC = byteArrayOf(0x44, 0x43, 0x46, 0x32) // DCF2

enum class CompressionMode { NONE, GZIP }

data class PackedOpticalFile(
    val container: ByteArray,
    val compression: CompressionMode,
    val originalSize: Int,
    val transmittedSize: Int,
)

data class OpticalFile(
    val name: String,
    val type: String,
    val bytes: ByteArray,
    val sha256: ByteArray,
    val compression: CompressionMode,
    val transmittedSize: Int,
)

data class FrameHeader(
    val sessionId: Int,
    val seq: Int,
    val k: Int,
    val blockLen: Int,
    val totalLen: Int,
    val payloadFnv: Int,
)

data class ParsedFrame(val header: FrameHeader, val block: ByteArray)

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

/**
 * Inflate with a hard output ceiling — the gzip trailer's declared size is
 * attacker-controlled (it arrives over the optical channel like everything
 * else), so it is a hint, never a bound. Mirrors gunzipAsync() in protocol.ts.
 */
private fun gunzip(bytes: ByteArray, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    GZIPInputStream(bytes.inputStream()).use { stream ->
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalArgumentException("The recovered file expands past its declared length.")
            }
            out.write(buf, 0, n)
        }
    }
    return out.toByteArray()
}

/** Reduce a name to a bare basename — applied on both ends, same as protocol.ts's safeFileName(). */
private fun safeFileName(name: String): String {
    val base = name.split('\\', '/').lastOrNull() ?: ""
    val cleaned = base.filter { it.code !in 0x00..0x1f && it.code != 0x7f }.trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "transfer.bin" else cleaned
}

private val PRECOMPRESSED_TYPES = setOf(
    "application/gzip", "application/java-archive", "application/vnd.rar",
    "application/x-7z-compressed", "application/x-brotli", "application/x-bzip",
    "application/x-bzip2", "application/x-gzip", "application/x-lzma",
    "application/x-rar-compressed", "application/x-xz", "application/x-zip-compressed",
    "application/zip", "application/zstd",
)

private val COMPRESSIBLE_IMAGES = Regex("^image/(bmp|x-ms-bmp|svg\\+xml|tiff|x-icon|vnd\\.microsoft\\.icon)$")
private val COMPRESSIBLE_AUDIO = Regex("^audio/(wav|x-wav|wave|vnd\\.wave|aiff|x-aiff|basic|l16)$")

/** Port of isPrecompressedType() — see protocol.ts for the full rationale. */
fun isPrecompressedType(type: String): Boolean {
    val media = type.substringBefore(';').trim().lowercase()
    return when {
        media.startsWith("video/") -> true
        media.startsWith("image/") -> !COMPRESSIBLE_IMAGES.matches(media)
        media.startsWith("audio/") -> !COMPRESSIBLE_AUDIO.matches(media)
        media.startsWith("application/vnd.openxmlformats-officedocument.") -> true
        media.startsWith("application/vnd.oasis.opendocument.") -> true
        media.endsWith("+zip") -> true
        else -> media in PRECOMPRESSED_TYPES
    }
}

fun packFile(name: String, type: String, bytes: ByteArray): PackedOpticalFile {
    if (bytes.isEmpty()) throw IllegalArgumentException("Choose a non-empty file.")
    if (bytes.size > MAX_FILE_BYTES) {
        throw IllegalArgumentException("Files are limited to $MAX_FILE_LABEL in this build.")
    }

    val nameBytes = safeFileName(name).toByteArray(Charsets.UTF_8)
    val typeBytes = (type.ifBlank { "application/octet-stream" }).toByteArray(Charsets.UTF_8)
    if (nameBytes.size > 0xffff || typeBytes.size > 0xffff) {
        throw IllegalArgumentException("The file name or media type is too long.")
    }

    val tryGzip = bytes.size >= 768 && !isPrecompressedType(type)
    val digest = sha256(bytes)
    val compressed = if (tryGzip) gzip(bytes) else null
    val useGzip = compressed != null && compressed.size + 64 < bytes.size
    val transmitted = if (useGzip) compressed!! else bytes
    val compression = if (useGzip) CompressionMode.GZIP else CompressionMode.NONE

    val out = ByteArray(FILE_HEADER_LEN + nameBytes.size + typeBytes.size + transmitted.size)
    val view = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    FILE_MAGIC.copyInto(out, 0)
    view.put(4, (if (useGzip) 1 else 0).toByte())
    view.putShort(5, nameBytes.size.toShort())
    view.putShort(7, typeBytes.size.toShort())
    view.putInt(9, bytes.size)
    view.putInt(13, transmitted.size)
    digest.copyInto(out, 17)
    nameBytes.copyInto(out, FILE_HEADER_LEN)
    typeBytes.copyInto(out, FILE_HEADER_LEN + nameBytes.size)
    transmitted.copyInto(out, FILE_HEADER_LEN + nameBytes.size + typeBytes.size)

    return PackedOpticalFile(out, compression, bytes.size, transmitted.size)
}

fun unpackFile(container: ByteArray): OpticalFile {
    if (container.size < FILE_HEADER_LEN) throw IllegalArgumentException("The recovered file header is incomplete.")
    for (i in FILE_MAGIC.indices) {
        if (container[i] != FILE_MAGIC[i]) throw IllegalArgumentException("The recovered file header is invalid.")
    }

    val view = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN)
    val compressionByte = view.get(4).toInt() and 0xff
    if (compressionByte > 1) throw IllegalArgumentException("The recovered file uses unsupported compression.")
    val compression = if (compressionByte == 1) CompressionMode.GZIP else CompressionMode.NONE
    val nameLength = view.getShort(5).toInt() and 0xffff
    val typeLength = view.getShort(7).toInt() and 0xffff
    val fileLength = view.getInt(9)
    val transmittedLength = view.getInt(13)
    val dataOffset = FILE_HEADER_LEN + nameLength + typeLength

    val fileLengthUnsigned = fileLength.toLong() and 0xffffffffL
    val transmittedLengthUnsigned = transmittedLength.toLong() and 0xffffffffL
    if (fileLength == 0 || fileLengthUnsigned > MAX_FILE_BYTES ||
        transmittedLength == 0 || transmittedLengthUnsigned > MAX_FILE_BYTES ||
        dataOffset + transmittedLengthUnsigned != container.size.toLong()
    ) {
        throw IllegalArgumentException("The recovered file length does not match its header.")
    }

    val transmitted = container.copyOfRange(dataOffset, container.size)
    if (compression == CompressionMode.GZIP) {
        if (transmitted.size < 18) throw IllegalArgumentException("The recovered gzip payload is incomplete.")
        // ByteBuffer.wrap(array, offset, length) sets position/limit for
        // *relative* access, but absolute getInt(index) still indexes from
        // the underlying array's start — wrapping the whole array and
        // offsetting the index is what actually reads the trailer's ISIZE.
        val trailer = ByteBuffer.wrap(transmitted).order(ByteOrder.LITTLE_ENDIAN)
        if (trailer.getInt(transmitted.size - 4) != fileLength) {
            throw IllegalArgumentException("The gzip payload length does not match its file header.")
        }
    }
    val bytes = if (compression == CompressionMode.GZIP) gunzip(transmitted, fileLength) else transmitted
    if (bytes.size != fileLength) {
        throw IllegalArgumentException("The decompressed file length does not match its header.")
    }

    return OpticalFile(
        name = safeFileName(String(container, FILE_HEADER_LEN, nameLength, Charsets.UTF_8)),
        type = String(container, FILE_HEADER_LEN + nameLength, typeLength, Charsets.UTF_8)
            .ifEmpty { "application/octet-stream" },
        sha256 = container.copyOfRange(17, 49),
        bytes = bytes,
        compression = compression,
        transmittedSize = transmittedLength,
    )
}

fun verifyFile(file: OpticalFile): Boolean = sha256(file.bytes).contentEquals(file.sha256)

fun packFrame(h: FrameHeader, block: ByteArray): ByteArray {
    val out = ByteArray(HEADER_LEN + block.size)
    val dv = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    dv.put(0, MAGIC0.toByte())
    dv.put(1, MAGIC1.toByte())
    dv.putShort(2, h.sessionId.toShort())
    dv.putInt(4, h.seq)
    dv.putShort(8, h.k.toShort())
    dv.putShort(10, h.blockLen.toShort())
    dv.putInt(12, h.totalLen)
    dv.putInt(16, h.payloadFnv)
    block.copyInto(out, HEADER_LEN)
    return out
}

fun parseFrame(bytes: ByteArray): ParsedFrame? {
    if (bytes.size <= HEADER_LEN) return null
    if ((bytes[0].toInt() and 0xff) != MAGIC0 || (bytes[1].toInt() and 0xff) != MAGIC1) return null
    val dv = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val header = FrameHeader(
        sessionId = dv.getShort(2).toInt() and 0xffff,
        seq = dv.getInt(4),
        k = dv.getShort(8).toInt() and 0xffff,
        blockLen = dv.getShort(10).toInt() and 0xffff,
        totalLen = dv.getInt(12),
        payloadFnv = dv.getInt(16),
    )
    if (header.k == 0 || header.blockLen == 0 || header.totalLen == 0) return null
    if (bytes.size != HEADER_LEN + header.blockLen) return null
    return ParsedFrame(header, bytes.copyOfRange(HEADER_LEN, bytes.size))
}

/**
 * Everything about a frame that has to hold constant for a decoder to keep
 * accepting frames into it — see the identical-purpose comment on
 * streamIdentity() in protocol.ts for why payloadFnv is included alongside
 * sessionId (16-bit session id collisions across a sender restart are rare
 * but real).
 */
fun streamIdentity(h: FrameHeader): String =
    "${h.sessionId}:${h.k}:${h.blockLen}:${h.totalLen}:${h.payloadFnv}"

fun fnv1a(bytes: ByteArray): Int {
    var h = 0x811c9dc5L.toInt()
    for (b in bytes) {
        h = h xor (b.toInt() and 0xff)
        h *= 0x01000193
    }
    return h
}

/** splitmix32 — deterministic across platforms (integer ops only). Returns
 *  values in [0, 2^32) as a Long, matching JS's `t >>> 0` output range
 *  exactly; callers that need Int arithmetic on the raw bits should use
 *  `.toInt()` explicitly rather than relying on this being in Int range. */
fun splitmix32(seed: Int): () -> Long {
    var s = seed
    return {
        s += 0x9e3779b9.toInt()
        var t = s xor (s ushr 16)
        t *= 0x21f0aaad
        t = t xor (t ushr 15)
        t *= 0x735a2d97
        t = t xor (t ushr 15)
        t.toLong() and 0xffffffffL
    }
}
