package com.novosoftlabs.sosfiletransfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * fountain.ts IS the wire format. These are the exact golden vectors from
 * tests/fountain.test.ts, ported bit-for-bit — if one of these fails, this
 * Kotlin port has diverged from the TypeScript original and an Android
 * device will silently fail to interoperate with a web sender/receiver. See
 * tests/fountain.test.ts for the full rationale on each vector.
 */
class FountainGoldenVectorsTest {

    @Test
    fun `dlog is bit-exact against its recorded values`() {
        val golden = listOf(
            1.0 to 0.0,
            1.5 to 0.4054651081081644,
            2.0 to 0.6931471805599453,
            2.718281828459045 to 1.0,
            10.0 to 2.3025850929940455,
            20.0 to 2.995732273553991,
            200.0 to 5.298317366548036,
            2000.0 to 7.600902459542082,
            2986.0 to 8.001689978099137,
            44000.0 to 10.691944912900398,
            131070.0 to 11.78348681061359,
        )
        for ((x, expected) in golden) {
            assertEquals("dlog($x) drifted", expected, dlog(x), 0.0)
        }
    }

    /** Same little-endian byte layout JS typed arrays use, so the fnv1a
     *  digest matches the one recorded from V8. */
    private fun doubleArrayFingerprint(values: DoubleArray, n: Int): Int {
        val buf = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) buf.putDouble(values[i])
        return fnv1a(buf.array())
    }

    @Test
    fun `dlog is bit-exact across every input the degree distribution can reach`() {
        val values = DoubleArray(65535 + 64 * 4096)
        var n = 0
        for (k in 1..65535) values[n++] = dlog(2.0 * k)
        for (i in 64 until 64 * 4096) values[n++] = dlog(i / 64.0)
        val digest = doubleArrayFingerprint(values, n)
        assertEquals("dlog changed", "0x27b0f3cc", "0x" + Integer.toHexString(digest).padStart(8, '0'))
    }

    @Test
    fun `the soliton CDF is a well-formed distribution`() {
        for (k in listOf(1, 2, 17, 179, 716, 22000)) {
            val cdf = solitonCdf(k)
            assertEquals(k, cdf.size)
            assertEquals("k=$k CDF must terminate at exactly 1", 1.0, cdf[k - 1], 0.0)
            for (i in 1 until k) {
                assertTrue("k=$k CDF is not monotonic at $i", cdf[i] >= cdf[i - 1])
            }
            assertTrue("k=$k degree 1 must have non-zero mass or peeling never starts", cdf[0] > 0)
        }
    }

    @Test
    fun `the soliton CDF is bit-identical to its recorded fingerprint`() {
        val golden = listOf(
            1 to "0x8c6a9878",
            2 to "0x2417b297",
            17 to "0x2ba41e3c",
            179 to "0xe8b6340a",
            716 to "0x28d31438",
            5000 to "0x357a4c9a",
            22000 to "0xfc512a92",
        )
        for ((k, expected) in golden) {
            val cdf = solitonCdf(k)
            val digest = doubleArrayFingerprint(cdf, cdf.size)
            assertEquals(
                "k=$k degree distribution changed — senders and receivers will desync",
                expected,
                "0x" + Integer.toHexString(digest).padStart(8, '0'),
            )
        }
    }

    @Test
    fun `frameIndices matches its recorded subsets`() {
        val golden = mapOf(
            1 to listOf(listOf(0), listOf(0), listOf(0), listOf(0), listOf(0)),
            2 to listOf(listOf(1), listOf(1), listOf(1), listOf(0), listOf(1)),
            17 to listOf(listOf(3, 14), listOf(12, 0), listOf(6, 8), listOf(15, 16, 13), listOf(11, 2, 16)),
            179 to listOf(listOf(27, 39), listOf(30, 55), listOf(155, 125), listOf(28, 132, 88), listOf(39, 75, 24)),
            716 to listOf(
                listOf(27, 397), listOf(567, 592), listOf(155, 304), listOf(386, 311, 625), listOf(39, 433, 382),
            ),
        )
        val seqs = listOf(0, 1, 2, 41, 1000)
        for ((k, expected) in golden) {
            val cdf = solitonCdf(k)
            seqs.forEachIndexed { i, seq ->
                assertEquals(
                    "k=$k seq=$seq subset changed — this is a breaking wire-format change",
                    expected[i],
                    frameIndices(k, cdf, 4242, seq),
                )
            }
        }
    }

    @Test
    fun `frameIndices always yields distinct in-range blocks`() {
        for (k in listOf(1, 2, 17, 179, 4096)) {
            val cdf = solitonCdf(k)
            for (seq in 0 until 3000) {
                val idx = frameIndices(k, cdf, 9, seq)
                assertTrue("k=$k seq=$seq degree ${idx.size}", idx.size in 1..k)
                assertEquals("k=$k seq=$seq repeated a block index", idx.size, idx.toSet().size)
                for (b in idx) assertTrue("k=$k seq=$seq index $b", b in 0 until k)
            }
        }
    }

    @Test
    fun `the same seq on a different session picks a different subset`() {
        val cdf = solitonCdf(179)
        val a = frameIndices(179, cdf, 1, 0)
        val b = frameIndices(179, cdf, 2, 0)
        assertTrue(a != b)
    }

    /** Deterministic filler — the fingerprints below are recorded against it. */
    private fun testPayload(byteLength: Int): ByteArray {
        val payload = ByteArray(byteLength)
        for (i in 0 until byteLength) payload[i] = ((i * 37 + (i shr 8) * 11) and 0xff).toByte()
        return payload
    }

    @Test
    fun `the encoded stream is byte-identical to its recorded fingerprint`() {
        data class Case(val k: Int, val blockLen: Int, val sessionId: Int, val expected: String)
        val golden = listOf(
            Case(1, 64, 1, "k=1 fnv=0xf6a115c5"),
            Case(23, 64, 7, "k=23 fnv=0x2aafe48d"),
            Case(179, 2933, 4242, "k=179 fnv=0x83bbd1d7"),
            Case(716, 1445, 65535, "k=716 fnv=0x15e10360"),
        )
        for (c in golden) {
            val encoder = LTEncoder(testPayload(c.k * c.blockLen - 7), c.blockLen, c.sessionId)
            val stream = ByteArray(64 * c.blockLen)
            for (seq in 0 until 64) {
                System.arraycopy(encoder.encode(seq), 0, stream, seq * c.blockLen, c.blockLen)
            }
            val actual = "k=${encoder.k} fnv=0x" + Integer.toHexString(fnv1a(stream)).padStart(8, '0')
            assertEquals("stream for k=${c.k}/${c.blockLen}/${c.sessionId} changed", c.expected, actual)
        }
    }

    @Test
    fun `every frame is exactly blockLen bytes`() {
        val blockLen = 1445
        val encoder = LTEncoder(testPayload(blockLen * 5 + 1), blockLen, 3)
        assertEquals(6, encoder.k)
        for (seq in 0 until 200) assertEquals(blockLen, encoder.encode(seq).size)
    }

    private data class RoundTrip(val frames: Int, val overhead: Double, val recovered: ByteArray?)

    private fun roundTrip(byteLength: Int, blockLen: Int, sessionId: Int, dropRate: Double = 0.0): RoundTrip {
        val payload = testPayload(byteLength)
        val encoder = LTEncoder(payload, blockLen, sessionId)
        val decoder = LTDecoder(encoder.k, blockLen, sessionId, byteLength)
        val rnd = splitmix32(sessionId)
        var seq = 0
        val ceiling = encoder.k * 80 + 5000
        while (!decoder.isComplete && seq < ceiling) {
            if (rnd().toDouble() * (1.0 / 4294967296.0) >= dropRate) decoder.addFrame(seq, encoder.encode(seq))
            seq++
        }
        return RoundTrip(decoder.framesNew, decoder.framesNew.toDouble() / encoder.k, decoder.assemble())
    }

    @Test
    fun `a payload survives the fountain exactly`() {
        for ((byteLength, blockLen) in listOf(
            7 to 2933,
            2933 to 2933,
            50_000 to 1445,
            512 * 1024 to 2933,
            2 * 1024 * 1024 to 2933,
        )) {
            val (_, _, recovered) = roundTrip(byteLength, blockLen, 11)
            assertTrue("${byteLength}B did not complete", recovered != null)
            assertTrue(recovered!!.contentEquals(testPayload(byteLength)))
        }
    }

    @Test
    fun `dropping 30 percent of frames costs time, never correctness`() {
        val (_, overhead, recovered) = roundTrip(512 * 1024, 2933, 23, 0.3)
        assertTrue(recovered != null)
        assertTrue(recovered!!.contentEquals(testPayload(512 * 1024)))
        assertTrue("unique-frame overhead $overhead is too high", overhead < 1.6)
    }

    @Test
    fun `frames decode in any order`() {
        val byteLength = 200_000
        val blockLen = 1445
        val payload = testPayload(byteLength)
        val encoder = LTEncoder(payload, blockLen, 77)

        val captured = mutableListOf<Pair<Int, ByteArray>>()
        for (seq in 0 until Math.ceil(encoder.k * 2.5).toInt()) captured.add(seq to encoder.encode(seq))
        val shuffled = captured.toMutableList()
        val rnd = splitmix32(5)
        for (i in shuffled.size - 1 downTo 1) {
            val j = (rnd() % (i + 1).toLong()).toInt()
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }

        val decoder = LTDecoder(encoder.k, blockLen, 77, byteLength)
        for ((seq, block) in shuffled) {
            decoder.addFrame(seq, block)
            if (decoder.isComplete) break
        }
        assertTrue(decoder.isComplete)
        assertTrue(decoder.assemble()!!.contentEquals(payload))
    }

    @Test
    fun `repeated frames are counted but never corrupt the decode`() {
        val byteLength = 60_000
        val blockLen = 1445
        val payload = testPayload(byteLength)
        val encoder = LTEncoder(payload, blockLen, 31)
        val decoder = LTDecoder(encoder.k, blockLen, 31, byteLength)

        var seq = 0
        while (!decoder.isComplete) {
            val block = encoder.encode(seq)
            decoder.addFrame(seq, block)
            decoder.addFrame(seq, block) // the camera re-reads the same on-screen frame
            seq++
        }
        assertTrue(decoder.framesDup >= decoder.framesNew - 1)
        assertTrue(decoder.assemble()!!.contentEquals(payload))
    }

    @Test
    fun `a single-block payload completes on its first frame`() {
        val payload = testPayload(900)
        val encoder = LTEncoder(payload, 2933, 5)
        assertEquals(1, encoder.k)
        val decoder = LTDecoder(1, 2933, 5, 900)
        decoder.addFrame(0, encoder.encode(0))
        assertTrue(decoder.isComplete)
        assertTrue(decoder.assemble()!!.contentEquals(payload))
    }

    @Test
    fun `an incomplete decoder assembles nothing`() {
        val encoder = LTEncoder(testPayload(50_000), 1445, 13)
        val decoder = LTDecoder(encoder.k, 1445, 13, 50_000)
        decoder.addFrame(0, encoder.encode(0))
        assertEquals(false, decoder.isComplete)
        assertEquals(null, decoder.assemble())
    }
}
