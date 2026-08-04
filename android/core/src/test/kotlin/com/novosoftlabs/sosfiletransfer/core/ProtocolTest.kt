package com.novosoftlabs.sosfiletransfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from tests/protocol.test.ts — see that file for the full rationale
 *  behind each case. */
class ProtocolTest {

    @Test
    fun `arbitrary file metadata and bytes survive the optical container`() {
        val source = byteArrayOf(0, 1, 2, 127, -128, -2, -1)
        val packed = packFile("resume.bin", "application/octet-stream", source)
        val recovered = unpackFile(packed.container)

        assertEquals(CompressionMode.NONE, packed.compression)
        assertEquals("resume.bin", recovered.name)
        assertEquals("application/octet-stream", recovered.type)
        assertTrue(recovered.bytes.contentEquals(source))
        assertTrue(verifyFile(recovered))
    }

    @Test
    fun `SHA-256 verification rejects changed file bytes`() {
        val packed = packFile("message.txt", "text/plain", "hello".toByteArray())
        val recovered = unpackFile(packed.container)
        recovered.bytes[0] = (recovered.bytes[0].toInt() xor 0xff).toByte()
        assertFalse(verifyFile(recovered))
    }

    @Test
    fun `compressible files use gzip and recover exactly`() {
        val source = "sos file transfer\n".repeat(4_000).toByteArray()
        val packed = packFile("notes.txt", "text/plain", source)
        val recovered = unpackFile(packed.container)

        assertEquals(CompressionMode.GZIP, packed.compression)
        assertTrue(packed.transmittedSize < source.size / 10)
        assertEquals(CompressionMode.GZIP, recovered.compression)
        assertTrue(recovered.bytes.contentEquals(source))
        assertTrue(verifyFile(recovered))
    }

    @Test
    fun `malformed optical containers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { unpackFile(ByteArray(49)) }
    }

    @Test
    fun `the receiver sanitises the filename rather than trusting the sender`() {
        val cases = listOf(
            "../../etc/passwd" to "passwd",
            "C:\\Windows\\System32\\drivers\\etc\\hosts" to "hosts",
            "evidence.pdf" to "evidence.pdf",
            "report v2 (final).tar.gz" to "report v2 (final).tar.gz",
        )
        for ((sent, expected) in cases) {
            val packed = packFile(sent, "application/octet-stream", byteArrayOf(1, 2, 3))
            assertEquals("for $sent", expected, unpackFile(packed.container).name)
        }
    }

    @Test
    fun `filenames that sanitise away fall back to a safe default`() {
        for (sent in listOf("..", ".", "/", "   ", "\u0000\u0007")) {
            val packed = packFile(sent, "application/octet-stream", byteArrayOf(1))
            assertEquals("transfer.bin", unpackFile(packed.container).name)
        }
    }

    @Test
    fun `the frame header is byte-for-byte what the wire expects`() {
        val frame = packFrame(
            FrameHeader(
                sessionId = 0xbeef,
                seq = 0x01020304,
                k = 0x0111,
                blockLen = 6,
                totalLen = 0x00fedcba,
                payloadFnv = 0x89abcdef.toInt(),
            ),
            byteArrayOf(1, 2, 3, 4, 5, 6),
        )
        val hex = frame.joinToString(" ") { "%02x".format(it) }
        assertEquals(
            "d1 0c ef be 04 03 02 01 11 01 06 00 ba dc fe 00 ef cd ab 89 01 02 03 04 05 06",
            hex,
        )
        assertEquals(HEADER_LEN + 6, frame.size)

        val parsed = parseFrame(frame)
        assertTrue(parsed != null)
        assertEquals(
            FrameHeader(0xbeef, 0x01020304, 0x0111, 6, 0x00fedcba, 0x89abcdef.toInt()),
            parsed!!.header,
        )
        assertTrue(parsed.block.contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `gzip is skipped for formats it cannot help`() {
        for (type in listOf(
            "image/jpeg", "image/png", "image/webp", "image/avif", "image/heic",
            "video/mp4", "video/quicktime", "audio/mpeg", "audio/mp4", "audio/flac",
            "application/zip", "application/gzip", "application/x-7z-compressed",
            "application/vnd.rar", "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.spreadsheet",
            "IMAGE/JPEG", "image/jpeg; charset=binary",
        )) {
            assertTrue("$type should skip gzip", isPrecompressedType(type))
        }
    }

    @Test
    fun `gzip is still attempted for anything that might compress`() {
        for (type in listOf(
            "text/plain", "text/csv", "application/json", "application/pdf",
            "application/wasm", "application/octet-stream",
            "application/vnd.novosoftlabs.snippet", "image/svg+xml", "image/bmp",
            "image/tiff", "image/x-icon", "audio/wav", "audio/x-aiff", "",
        )) {
            assertFalse("$type should still try gzip", isPrecompressedType(type))
        }
    }

    @Test
    fun `a precompressed file is transmitted verbatim and still round-trips`() {
        val source = ByteArray(4096)
        for (i in source.indices) source[i] = ((i * 2654435761L) ushr 24).toByte()
        val packed = packFile("photo.jpg", "image/jpeg", source)

        assertEquals(CompressionMode.NONE, packed.compression)
        assertEquals(source.size, packed.transmittedSize)
        val recovered = unpackFile(packed.container)
        assertTrue(recovered.bytes.contentEquals(source))
        assertTrue(verifyFile(recovered))
    }

    @Test
    fun `declaring a compressible type still gets gzip`() {
        val source = "the same line over and over\n".repeat(2000).toByteArray()
        assertEquals(CompressionMode.GZIP, packFile("log.txt", "text/plain", source).compression)
        assertEquals(CompressionMode.NONE, packFile("log.txt", "image/jpeg", source).compression)
    }

    @Test
    fun `streamIdentity changes with every field that must not drift mid-stream`() {
        val base = FrameHeader(sessionId = 7, seq = 0, k = 100, blockLen = 2933, totalLen = 293_300, payloadFnv = 0xdeadbeef.toInt())
        val identity = streamIdentity(base)

        // seq is the one field that varies within a stream.
        assertEquals(identity, streamIdentity(base.copy(seq = 9999)))

        assertTrue(streamIdentity(base.copy(sessionId = base.sessionId + 1)) != identity)
        assertTrue(streamIdentity(base.copy(k = base.k + 1)) != identity)
        assertTrue(streamIdentity(base.copy(blockLen = base.blockLen + 1)) != identity)
        assertTrue(streamIdentity(base.copy(totalLen = base.totalLen + 1)) != identity)
        assertTrue(streamIdentity(base.copy(payloadFnv = base.payloadFnv + 1)) != identity)
    }

    @Test
    fun `streamIdentity fields cannot be confused by the separator`() {
        val a = FrameHeader(sessionId = 1, seq = 0, k = 1, blockLen = 23, totalLen = 4, payloadFnv = 5)
        val b = FrameHeader(sessionId = 1, seq = 0, k = 12, blockLen = 3, totalLen = 4, payloadFnv = 5)
        assertTrue(streamIdentity(a) != streamIdentity(b))
    }

    @Test
    fun `frames that are not ours, or not self-consistent, are rejected`() {
        val good = packFrame(
            FrameHeader(sessionId = 1, seq = 2, k = 3, blockLen = 4, totalLen = 10, payloadFnv = 0),
            byteArrayOf(9, 9, 9, 9),
        )
        assertTrue(parseFrame(good) != null)

        val wrongMagic = good.copyOf()
        wrongMagic[0] = 0xd2.toByte()
        assertNull(parseFrame(wrongMagic))

        assertNull(parseFrame(good.copyOfRange(0, HEADER_LEN)))
        assertNull(parseFrame(good.copyOfRange(0, good.size - 1)))

        val zeroK = good.copyOf()
        zeroK[8] = 0
        zeroK[9] = 0
        assertNull(parseFrame(zeroK))
    }
}
