package app.egress.vpn

import java.io.DataInputStream
import java.io.InputStream

/** An IP prefix: [address] is 4 or 16 bytes. */
class Cidr(val address: ByteArray, val prefix: Int) {
    override fun toString(): String = if (address.size == 4) {
        address.joinToString(".") { (it.toInt() and 0xFF).toString() } + "/$prefix"
    } else {
        (0 until 8).joinToString(":") { Integer.toHexString(Packets.u16(address, it * 2)) } + "/$prefix"
    }
}

/**
 * Routes to send into the tunnel for a set of blocked countries, largest prefixes first, within a budget.
 * [coverage] is the share of each country's IPv4 address space the routes cover (the DNS layer catches the rest).
 */
class RoutePlan(val v4: List<Cidr>, val v6: List<Cidr>, val coverage: Map<String, Float>) {
    val size get() = v4.size + v6.size
}

/**
 * Offline IP → country table (DB-IP "IP to Country Lite", CC BY 4.0), built by tools/build_ipdb.py.
 * Pure Kotlin so it stays JVM-testable. ~5 MB in memory; load once, off the main thread.
 *
 * Each table is a sorted list of range starts; entry i covers [start_i, start_{i+1}). Starts are stored with the
 * sign bit flipped so plain signed comparisons give unsigned order. IPv6 keeps the top 64 bits only.
 */
class IpCountryDb private constructor(
    private val codes: Array<String>,
    private val v4Start: IntArray,
    private val v4Cc: ByteArray,
    private val v6Start: LongArray,
    private val v6Cc: ByteArray,
) {
    val countries: List<String> get() = codes.drop(1)

    /** ISO code for a 4- or 16-byte address, or null if unknown / reserved. */
    fun country(addr: ByteArray): String? {
        val idx = when (addr.size) {
            4 -> v4Cc[floor(v4Start, Packets.u32(addr, 0).toInt() xor Int.MIN_VALUE)]
            16 -> v6Cc[floor(v6Start, high64(addr) xor Long.MIN_VALUE)]
            else -> return null
        }.toInt() and 0xFF
        return if (idx == 0) null else codes[idx]
    }

    /**
     * Prefixes covering [blocked]. Android can only take a few thousand VPN routes, so when a country needs more
     * than its share of [budget], the largest prefixes win. About a quarter of the budget goes to IPv6.
     */
    fun plan(blocked: Set<String>, budget: Int): RoutePlan {
        if (blocked.isEmpty() || budget <= 0) return RoutePlan(emptyList(), emptyList(), emptyMap())
        val wanted = BooleanArray(codes.size) { it != 0 && codes[it] in blocked }

        class Cand(val cc: Int, val start: ULong, val hostBits: Int, val weight: Double)

        fun collect(n: Int, bits: Int, startAt: (Int) -> ULong, ccAt: (Int) -> Int): List<Cand> {
            val totals = DoubleArray(codes.size)
            val raw = ArrayList<Cand>()
            for (i in 0 until n) {
                val cc = ccAt(i)
                if (!wanted[cc]) continue
                val s = startAt(i)
                val e = if (i + 1 < n) startAt(i + 1) - 1u else maxValue(bits)
                decompose(s, e, bits) { start, hostBits ->
                    val size = Math.scalb(1.0, hostBits)
                    totals[cc] += size
                    raw += Cand(cc, start, hostBits, size)
                }
            }
            // Weight = share of that country's space, so small countries aren't starved by big ones.
            return raw.map { Cand(it.cc, it.start, it.hostBits, it.weight / totals[it.cc]) }
                .sortedByDescending { it.weight }
        }

        val c4 = collect(v4Start.size, 32, { (v4Start[it] xor Int.MIN_VALUE).toUInt().toULong() }, { v4Cc[it].toInt() and 0xFF })
        val c6 = collect(v6Start.size, 64, { (v6Start[it] xor Long.MIN_VALUE).toULong() }, { v6Cc[it].toInt() and 0xFF })

        var b6 = minOf(c6.size, budget / 4)
        val b4 = minOf(c4.size, budget - b6)
        b6 = minOf(c6.size, budget - b4)

        val taken4 = c4.take(b4)
        val coverage = HashMap<String, Float>()
        blocked.forEach { cc -> if (cc in codes) coverage[cc] = 0f }
        taken4.forEach { coverage[codes[it.cc]] = (coverage[codes[it.cc]] ?: 0f) + it.weight.toFloat() }
        if (c4.isNotEmpty() && b4 == c4.size) c4.forEach { coverage[codes[it.cc]] = 1f }

        return RoutePlan(
            v4 = taken4.map { Cidr(v4Bytes(it.start), 32 - it.hostBits) },
            v6 = c6.take(b6).map { Cidr(v6Bytes(it.start), 64 - it.hostBits) },
            coverage = coverage.mapValues { it.value.coerceIn(0f, 1f) },
        )
    }

    companion object {
        fun read(input: InputStream): IpCountryDb {
            val d = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(4).also { d.readFully(it) }
            check(String(magic, Charsets.US_ASCII) == "EGI1") { "bad ipcountry.bin" }
            val codes = Array(d.readUnsignedByte()) {
                String(byteArrayOf(d.readByte(), d.readByte()), Charsets.US_ASCII)
            }
            val n4 = d.readInt()
            val s4 = IntArray(n4) { d.readInt() xor Int.MIN_VALUE }
            val c4 = ByteArray(n4).also { d.readFully(it) }
            val n6 = d.readInt()
            val s6 = LongArray(n6) { d.readLong() xor Long.MIN_VALUE }
            val c6 = ByteArray(n6).also { d.readFully(it) }
            return IpCountryDb(codes, s4, c4, s6, c6)
        }

        /** Index of the last entry whose start is <= [key] (tables always start at 0, so this is >= 0). */
        private fun floor(a: IntArray, key: Int): Int {
            var lo = 0
            var hi = a.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (a[mid] <= key) lo = mid else hi = mid - 1
            }
            return lo
        }

        private fun floor(a: LongArray, key: Long): Int {
            var lo = 0
            var hi = a.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (a[mid] <= key) lo = mid else hi = mid - 1
            }
            return lo
        }

        private fun maxValue(bits: Int): ULong = if (bits == 64) ULong.MAX_VALUE else (1uL shl bits) - 1u

        /** Splits the inclusive range [s, e] of a [bits]-wide space into the minimal list of aligned prefixes. */
        fun decompose(s: ULong, e: ULong, bits: Int, emit: (start: ULong, hostBits: Int) -> Unit) {
            var cur = s
            while (true) {
                var k = if (cur == 0uL) bits else minOf(bits, cur.countTrailingZeroBits())
                while (k > 0 && cur + span(k) > e) k--
                emit(cur, k)
                val end = cur + span(k)
                if (end >= e || end == ULong.MAX_VALUE) return
                cur = end + 1u
            }
        }

        private fun span(hostBits: Int): ULong = if (hostBits >= 64) ULong.MAX_VALUE else (1uL shl hostBits) - 1u

        private fun high64(a: ByteArray): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (a[i].toLong() and 0xFF)
            return v
        }

        private fun v4Bytes(v: ULong) = ByteArray(4) { (v shr (24 - 8 * it)).toByte() }
        private fun v6Bytes(v: ULong) = ByteArray(16) { if (it < 8) (v shr (56 - 8 * it)).toByte() else 0 }
    }
}
