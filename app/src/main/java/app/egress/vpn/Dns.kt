package app.egress.vpn

import java.net.InetAddress

/** DNS wire-format helpers. Pure Kotlin/JVM so they can be unit-tested off-device. */
object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    data class Query(val id: Int, val name: String, val qtype: Int)

    data class Answer(
        val id: Int,
        val rcode: Int,
        val ips: List<String>,
    )

    /** Parses the first question of a DNS query. Returns null for anything that isn't a plain query. */
    fun parseQuery(msg: ByteArray): Query? {
        if (msg.size < 12) return null
        val flags = Packets.u16(msg, 2)
        if (flags and 0x8000 != 0) return null      // QR=1 → it's a response
        if ((flags ushr 11) and 0xF != 0) return null // opcode must be QUERY
        val qd = Packets.u16(msg, 4)
        if (qd < 1) return null
        val (name, end) = readName(msg, 12) ?: return null
        if (end + 4 > msg.size) return null
        return Query(Packets.u16(msg, 0), name, Packets.u16(msg, end))
    }

    /**
     * Reads A/AAAA answers from a response. If [ttlCap] > 0, answer TTLs above it are rewritten in place,
     * which stops the system resolver cache from hiding repeat lookups by *other* apps (better attribution).
     */
    fun inspectResponse(msg: ByteArray, ttlCap: Int): Answer? {
        if (msg.size < 12) return null
        val flags = Packets.u16(msg, 2)
        if (flags and 0x8000 == 0) return null
        val rcode = flags and 0xF
        val qd = Packets.u16(msg, 4)
        val an = Packets.u16(msg, 6)
        var off = 12
        repeat(qd) {
            off = skipName(msg, off)
            if (off < 0 || off + 4 > msg.size) return null
            off += 4
        }
        val ips = ArrayList<String>()
        repeat(an) {
            off = skipName(msg, off)
            if (off < 0 || off + 10 > msg.size) return Answer(Packets.u16(msg, 0), rcode, ips)
            val type = Packets.u16(msg, off)
            val ttlOff = off + 4
            val rdLen = Packets.u16(msg, off + 8)
            val rd = off + 10
            if (rd + rdLen > msg.size) return Answer(Packets.u16(msg, 0), rcode, ips)
            if (ttlCap > 0 && Packets.u32(msg, ttlOff) > ttlCap) Packets.put32(msg, ttlOff, ttlCap.toLong())
            if ((type == TYPE_A && rdLen == 4) || (type == TYPE_AAAA && rdLen == 16)) {
                ips += InetAddress.getByAddress(msg.copyOfRange(rd, rd + rdLen)).hostAddress ?: ""
            }
            off = rd + rdLen
        }
        return Answer(Packets.u16(msg, 0), rcode, ips.filter { it.isNotEmpty() }.distinct())
    }

    /**
     * Country blocking at the DNS layer. Every A/AAAA record (answer, authority and additional sections) whose
     * address [isBlocked] is overwritten *in place* with [sinkV4]/[sinkV6] (addresses routed into our tunnel,
     * where connections get reset) and its TTL is cut to [ttl]. Records are rewritten rather than removed so
     * that name-compression pointers into later parts of the message stay valid.
     * Returns the original addresses that were sunk (empty if nothing matched or the message is malformed).
     */
    fun sinkAddresses(
        msg: ByteArray,
        sinkV4: ByteArray,
        sinkV6: ByteArray,
        ttl: Int,
        isBlocked: (ByteArray) -> Boolean,
    ): List<ByteArray> {
        require(sinkV4.size == 4 && sinkV6.size == 16)
        val sunk = ArrayList<ByteArray>()
        if (msg.size < 12 || Packets.u16(msg, 2) and 0x8000 == 0) return sunk
        val qd = Packets.u16(msg, 4)
        val records = Packets.u16(msg, 6) + Packets.u16(msg, 8) + Packets.u16(msg, 10)
        var off = 12
        repeat(qd) {
            off = skipName(msg, off)
            if (off < 0 || off + 4 > msg.size) return sunk
            off += 4
        }
        repeat(records) {
            off = skipName(msg, off)
            if (off < 0 || off + 10 > msg.size) return sunk
            val type = Packets.u16(msg, off)
            val rdLen = Packets.u16(msg, off + 8)
            val rd = off + 10
            if (rd + rdLen > msg.size) return sunk
            val sink = when {
                type == TYPE_A && rdLen == 4 -> sinkV4
                type == TYPE_AAAA && rdLen == 16 -> sinkV6
                else -> null
            }
            if (sink != null) {
                val ip = msg.copyOfRange(rd, rd + rdLen)
                if (isBlocked(ip)) {
                    sunk += ip
                    System.arraycopy(sink, 0, msg, rd, rdLen)
                    if (Packets.u32(msg, off + 4) > ttl) Packets.put32(msg, off + 4, ttl.toLong())
                }
            }
            off = rd + rdLen
        }
        return sunk
    }

    private fun readName(msg: ByteArray, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var off = start
        var end = -1
        var jumps = 0
        while (true) {
            if (off >= msg.size) return null
            val len = msg[off].toInt() and 0xFF
            when {
                len == 0 -> { if (end < 0) end = off + 1; break }
                len and 0xC0 == 0xC0 -> {
                    if (off + 1 >= msg.size || ++jumps > 16) return null
                    if (end < 0) end = off + 2
                    off = ((len and 0x3F) shl 8) or (msg[off + 1].toInt() and 0xFF)
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (off + 1 + len > msg.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in off + 1..off + len) sb.append((msg[i].toInt() and 0xFF).toChar())
                    off += len + 1
                }
            }
        }
        return sb.toString().lowercase() to end
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var off = start
        while (true) {
            if (off >= msg.size) return -1
            val len = msg[off].toInt() and 0xFF
            when {
                len == 0 -> return off + 1
                len and 0xC0 == 0xC0 -> return if (off + 2 <= msg.size) off + 2 else -1
                len and 0xC0 != 0 -> return -1
                else -> off += len + 1
            }
        }
    }
}
