package app.egress.vpn

/**
 * Minimal IPv4 / IPv6 / UDP / TCP parsing and packet construction for the DNS tap and the country block.
 * Pure Kotlin (no Android APIs) so it can be unit-tested on the JVM.
 */
object Packets {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    sealed class Parsed
    class Udp(
        val src: ByteArray, val dst: ByteArray,
        val srcPort: Int, val dstPort: Int,
        val payload: ByteArray,
    ) : Parsed()

    /** Any TCP segment (IPv4 or IPv6). Only the fields needed to answer it with a reset. */
    class Tcp(
        val src: ByteArray, val dst: ByteArray,
        val srcPort: Int, val dstPort: Int,
        val seq: Long, val ack: Long, val flags: Int, val payloadLen: Int,
    ) : Parsed() {
        val v6 get() = src.size == 16
        val syn get() = flags and FLAG_SYN != 0
        val hasAck get() = flags and FLAG_ACK != 0
        val rst get() = flags and FLAG_RST != 0
        val fin get() = flags and FLAG_FIN != 0
    }

    /** A packet we can't or needn't parse further, but whose destination we know (for counting blocked traffic). */
    class Other(val dst: ByteArray, val proto: Int) : Parsed()

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_ACK = 0x10

    fun parse(buf: ByteArray, len: Int): Parsed? {
        if (len < 20) return null
        val version = (buf[0].toInt() ushr 4) and 0x0F
        if (version == 6) return parseV6(buf, len)
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl) return null
        val totalLen = u16(buf, 2)
        if (totalLen < ihl || totalLen > len) return null
        // Drop fragments: DNS queries are tiny and never fragmented in practice.
        val fragField = u16(buf, 6)
        val moreFragments = (fragField and 0x2000) != 0
        val fragOffset = fragField and 0x1FFF
        if (moreFragments || fragOffset != 0) return null

        val proto = buf[9].toInt() and 0xFF
        val src = buf.copyOfRange(12, 16)
        val dst = buf.copyOfRange(16, 20)

        return when (proto) {
            PROTO_UDP -> {
                if (totalLen < ihl + 8) return null
                val sp = u16(buf, ihl)
                val dp = u16(buf, ihl + 2)
                val udpLen = u16(buf, ihl + 4)
                if (udpLen < 8 || ihl + udpLen > totalLen) return null
                Udp(src, dst, sp, dp, buf.copyOfRange(ihl + 8, ihl + udpLen))
            }
            PROTO_TCP -> parseTcp(buf, ihl, totalLen - ihl, src, dst) ?: Other(dst, proto)
            else -> Other(dst, proto)
        }
    }

    /** IPv6 with TCP directly after the fixed header. Extension headers are reported as [Other]. */
    private fun parseV6(buf: ByteArray, len: Int): Parsed? {
        if (len < 40) return null
        val payloadLen = u16(buf, 4)
        if (40 + payloadLen > len) return null
        val next = buf[6].toInt() and 0xFF
        val src = buf.copyOfRange(8, 24)
        val dst = buf.copyOfRange(24, 40)
        return if (next == PROTO_TCP) parseTcp(buf, 40, payloadLen, src, dst) ?: Other(dst, next) else Other(dst, next)
    }

    private fun parseTcp(buf: ByteArray, off: Int, segLen: Int, src: ByteArray, dst: ByteArray): Tcp? {
        if (segLen < 20) return null
        val dataOff = ((buf[off + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOff < 20 || dataOff > segLen) return null
        return Tcp(
            src, dst, u16(buf, off), u16(buf, off + 2),
            seq = u32(buf, off + 4), ack = u32(buf, off + 8),
            flags = buf[off + 13].toInt() and 0x3F, payloadLen = segLen - dataOff,
        )
    }

    fun buildUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        writeIpHeader(p, total, PROTO_UDP, src, dst)
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, udpLen)
        put16(p, 26, 0)
        System.arraycopy(payload, 0, p, 28, payload.size)
        var c = transportChecksum(p, 20, udpLen, PROTO_UDP, src, dst)
        if (c == 0) c = 0xFFFF // 0 means "no checksum" for UDP over IPv4
        put16(p, 26, c)
        return p
    }

    /**
     * The reset a closed port would send back for [t] (RFC 793 §3.4), addressed from its destination to its source.
     * Used for DNS-over-TCP/TLS probes to our resolver and for every connection to a blocked country, so apps
     * fail immediately instead of hanging until a timeout. Returns null for an incoming RST (never answered).
     */
    fun buildTcpReset(t: Tcp): ByteArray? {
        if (t.rst) return null
        val seq: Long
        val ack: Long
        val flags: Int
        if (t.hasAck) {
            seq = t.ack; ack = 0; flags = FLAG_RST
        } else {
            val consumed = t.payloadLen + (if (t.syn) 1 else 0) + (if (t.fin) 1 else 0)
            seq = 0; ack = (t.seq + consumed) and 0xFFFFFFFFL; flags = FLAG_RST or FLAG_ACK
        }
        val ipLen = if (t.v6) 40 else 20
        val p = ByteArray(ipLen + 20)
        if (t.v6) writeIp6Header(p, 20, PROTO_TCP, t.dst, t.src) else writeIpHeader(p, p.size, PROTO_TCP, t.dst, t.src)
        val o = ipLen
        put16(p, o, t.dstPort)
        put16(p, o + 2, t.srcPort)
        put32(p, o + 4, seq)
        put32(p, o + 8, ack)
        p[o + 12] = (5 shl 4).toByte() // data offset = 5 words
        p[o + 13] = flags.toByte()
        put16(p, o + 14, 0)            // window
        put16(p, o + 16, 0)            // checksum (filled below)
        put16(p, o + 18, 0)            // urgent
        put16(p, o + 16, transportChecksum(p, o, 20, PROTO_TCP, t.dst, t.src))
        return p
    }

    private fun writeIp6Header(p: ByteArray, payloadLen: Int, next: Int, src: ByteArray, dst: ByteArray) {
        p[0] = 0x60
        put16(p, 4, payloadLen)
        p[6] = next.toByte()
        p[7] = 64
        System.arraycopy(src, 0, p, 8, 16)
        System.arraycopy(dst, 0, p, 24, 16)
    }

    private fun writeIpHeader(p: ByteArray, total: Int, proto: Int, src: ByteArray, dst: ByteArray) {
        p[0] = 0x45
        p[1] = 0
        put16(p, 2, total)
        put16(p, 4, 0)
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64
        p[9] = proto.toByte()
        put16(p, 10, 0)
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20, 0))
    }

    private fun transportChecksum(p: ByteArray, off: Int, len: Int, proto: Int, src: ByteArray, dst: ByteArray): Int {
        // Pseudo-header: both addresses (4 or 16 bytes), protocol and length. Same sum for IPv4 and IPv6.
        var sum = 0L
        for (i in src.indices step 2) sum += u16(src, i)
        for (i in dst.indices step 2) sum += u16(dst, i)
        sum += proto
        sum += len
        return checksum(p, off, len, sum)
    }

    fun checksum(p: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (p[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
