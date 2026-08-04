package com.novosoftlabs.sosfiletransfer.core

// Port of shared/fountain.ts — the LT (Luby transform) fountain code that
// makes the one-way optical channel work. See fountain.ts's module doc for
// the full rationale. The critical constraint repeated from there: sender and
// receiver must derive BIT-IDENTICAL degree distributions independently and
// never compare notes, so dlog(), solitonCdf(), frameSeed() and
// frameIndices() below must match the TypeScript originals exactly — this is
// wire format, not an implementation detail.

private const val LN2 = 0.6931471805599453

/**
 * Deterministic natural log — exact-ops range reduction + atanh series.
 * `kotlin.math.ln` (like JS `Math.log`) is implementation-approximated and is
 * NOT interchangeable with this: see dlog() in fountain.ts for why a sender
 * and receiver on different JS engines (or a JVM and a JS engine here) can
 * disagree by an ulp and silently desync a transfer. Pinned by golden vectors
 * in FountainGoldenVectorsTest — do not "simplify" this into ln(x).
 */
fun dlog(x: Double): Double {
    var e = 0
    var m = x
    while (m >= 1.5) {
        m /= 2
        e++
    }
    while (m < 0.75) {
        m *= 2
        e--
    }
    val z = (m - 1) / (m + 1)
    val z2 = z * z
    var term = z
    var sum = 0.0
    var n = 1
    while (n <= 21) {
        sum += term / n
        term *= z2
        n += 2
    }
    return e * LN2 + 2 * sum
}

private const val SOLITON_C = 0.1
private const val SOLITON_DELTA = 0.5

/** Robust-soliton degree CDF for k source blocks. Wire-format-pinned, same as dlog(). */
fun solitonCdf(k: Int): DoubleArray {
    val cdf = DoubleArray(k)
    if (k == 1) {
        cdf[0] = 1.0
        return cdf
    }
    val r = maxOf(1.0, SOLITON_C * dlog(k / SOLITON_DELTA) * Math.sqrt(k.toDouble()))
    val spike = minOf(k.toDouble(), Math.ceil(k / r))
    var total = 0.0
    for (d in 1..k) {
        val rho = if (d == 1) 1.0 / k else 1.0 / (d.toDouble() * (d - 1))
        var tau = 0.0
        if (d < spike) {
            tau = r / (d * k)
        } else if (d.toDouble() == spike) {
            tau = (r * maxOf(0.0, dlog(r / SOLITON_DELTA))) / k
        }
        total += rho + tau
        cdf[d - 1] = total
    }
    for (i in 0 until k) cdf[i] = cdf[i] / total
    cdf[k - 1] = 1.0
    return cdf
}

private fun frameSeed(sessionId: Int, seq: Int): Int {
    var h = ((sessionId + 1) * 0x9e3779b1.toInt()) xor (seq + 0x85ebca6b.toInt())
    h = (h xor (h ushr 13)) * 0xc2b2ae35.toInt()
    return h xor (h ushr 16)
}

private const val TWO_POW_NEG_32 = 1.0 / 4294967296.0

/**
 * The block indices XORed into frame `seq` — identical on both ends. Any
 * change here is a breaking wire-format change: an old standalone HTML
 * sender has to keep agreeing with a current Android receiver. Pinned by
 * golden vectors in FountainGoldenVectorsTest.
 */
fun frameIndices(k: Int, cdf: DoubleArray, sessionId: Int, seq: Int): List<Int> {
    val rnd = splitmix32(frameSeed(sessionId, seq))
    val u = rnd().toDouble() * TWO_POW_NEG_32
    var lo = 0
    var hi = k - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (cdf[mid] >= u) hi = mid else lo = mid + 1
    }
    val d = minOf(k, lo + 1)
    if (d > (k shr 3)) {
        // large degree: partial Fisher-Yates over an identity array
        val scratch = IntArray(k) { it }
        val out = IntArray(d)
        for (i in 0 until d) {
            val j = i + (rnd() % (k - i).toLong()).toInt()
            val t = scratch[i]
            scratch[i] = scratch[j]
            scratch[j] = t
            out[i] = scratch[i]
        }
        return out.toList()
    }
    val set = LinkedHashSet<Int>()
    while (set.size < d) set.add((rnd() % k.toLong()).toInt())
    return set.toList()
}

private fun xorInto(dst: ByteArray, src: ByteArray) {
    for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
}

class LTEncoder(payload: ByteArray, val blockLen: Int, val sessionId: Int) {
    val k: Int = maxOf(1, Math.ceil(payload.size.toDouble() / blockLen).toInt())
    private val cdf: DoubleArray = solitonCdf(k)
    private val blocks: Array<ByteArray> = Array(k) { b ->
        val block = ByteArray(blockLen)
        val start = b * blockLen
        val end = minOf(start + blockLen, payload.size)
        if (start < end) System.arraycopy(payload, start, block, 0, end - start)
        block
    }

    fun encode(seq: Int): ByteArray {
        val idx = frameIndices(k, cdf, sessionId, seq)
        val out = ByteArray(blockLen)
        for (b in idx) xorInto(out, blocks[b])
        return out
    }
}

private class PendingFrame(val idx: MutableSet<Int>, val words: ByteArray)

class LTDecoder(val k: Int, val blockLen: Int, val sessionId: Int, val totalLen: Int) {
    private val cdf: DoubleArray = solitonCdf(k)
    private val solved: Array<ByteArray?> = arrayOfNulls(k)
    private val byBlock = HashMap<Int, MutableSet<PendingFrame>>()
    private val seen = HashSet<Int>()

    var solvedCount: Int = 0
        private set
    var framesNew: Int = 0
        private set
    var framesDup: Int = 0
        private set

    val isComplete: Boolean get() = solvedCount >= k

    fun addFrame(seq: Int, block: ByteArray) {
        if (!seen.add(seq)) {
            framesDup++
            return
        }
        framesNew++
        if (isComplete) return

        val idx = frameIndices(k, cdf, sessionId, seq).toMutableSet()
        val words = ByteArray(blockLen)
        System.arraycopy(block, 0, words, 0, blockLen)
        for (b in idx.toList()) {
            val s = solved[b]
            if (s != null) {
                xorInto(words, s)
                idx.remove(b)
            }
        }
        if (idx.isEmpty()) return // fully redundant
        if (idx.size == 1) {
            resolve(idx.first(), words)
            return
        }
        val pf = PendingFrame(idx, words)
        for (b in idx) byBlock.getOrPut(b) { LinkedHashSet() }.add(pf)
    }

    /** Peeling cascade: solve a block, reduce every frame waiting on it, repeat. */
    private fun resolve(b0: Int, w0: ByteArray) {
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        queue.addLast(b0 to w0)
        while (queue.isNotEmpty()) {
            val (b, w) = queue.removeLast()
            if (solved[b] != null) continue
            solved[b] = w
            solvedCount++
            val waiting = byBlock.remove(b) ?: continue
            for (pf in waiting) {
                xorInto(pf.words, w)
                pf.idx.remove(b)
                if (pf.idx.size == 1) {
                    val r = pf.idx.first()
                    byBlock[r]?.remove(pf)
                    if (solved[r] == null) queue.addLast(r to pf.words)
                }
            }
        }
    }

    fun assemble(): ByteArray? {
        if (!isComplete) return null
        val out = ByteArray(totalLen)
        for (b in 0 until k) {
            val start = b * blockLen
            val len = minOf(blockLen, totalLen - start)
            if (len > 0) System.arraycopy(solved[b]!!, 0, out, start, len)
        }
        return out
    }
}
