package app.egress.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {

    private val phone4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val server4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
    private val phone6 = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val server6 = ByteArray(16).also { it[0] = 0x2a; it[1] = 0x02; it[15] = 0x34 }

    /** Builds a TCP segment the way the kernel would, with valid checksums. */
    private fun tcp(src: ByteArray, dst: ByteArray, flags: Int, seq: Long, ack: Long, payload: Int = 0): ByteArray {
        val v6 = src.size == 16
        val ipLen = if (v6) 40 else 20
        val p = ByteArray(ipLen + 20 + payload)
        if (v6) {
            p[0] = 0x60; Packets.put16(p, 4, 20 + payload); p[6] = 6; p[7] = 64
            System.arraycopy(src, 0, p, 8, 16); System.arraycopy(dst, 0, p, 24, 16)
        } else {
            p[0] = 0x45; Packets.put16(p, 2, p.size); p[8] = 64; p[9] = 6
            System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4)
            Packets.put16(p, 10, Packets.checksum(p, 0, 20, 0))
        }
        Packets.put16(p, ipLen, 51000); Packets.put16(p, ipLen + 2, 443)
        Packets.put32(p, ipLen + 4, seq); Packets.put32(p, ipLen + 8, ack)
        p[ipLen + 12] = 0x50; p[ipLen + 13] = flags.toByte()
        Packets.put16(p, ipLen + 16, Packets.checksum(p, ipLen, 20 + payload, pseudo(src, dst, 20 + payload)))
        return p
    }

    private fun pseudo(src: ByteArray, dst: ByteArray, len: Int): Long {
        var s = 0L
        for (i in src.indices step 2) s += Packets.u16(src, i)
        for (i in dst.indices step 2) s += Packets.u16(dst, i)
        return s + 6 + len
    }

    private fun assertValidReset(p: ByteArray, from: ByteArray, to: ByteArray) {
        val v6 = from.size == 16
        val ipLen = if (v6) 40 else 20
        assertEquals(ipLen + 20, p.size)
        if (v6) {
            assertEquals(0x60, p[0].toInt() and 0xF0)
            assertEquals(20, Packets.u16(p, 4))
            assertArrayEquals(from, p.copyOfRange(8, 24))
            assertArrayEquals(to, p.copyOfRange(24, 40))
        } else {
            assertEquals("IPv4 header checksum", 0, Packets.checksum(p, 0, 20, 0))
            assertArrayEquals(from, p.copyOfRange(12, 16))
            assertArrayEquals(to, p.copyOfRange(16, 20))
        }
        assertEquals("TCP checksum", 0, Packets.checksum(p, ipLen, 20, pseudo(from, to, 20)))
        assertEquals(443, Packets.u16(p, ipLen))
        assertEquals(51000, Packets.u16(p, ipLen + 2))
    }

    @Test fun syn_v4_gets_rst_ack_for_isn_plus_one() {
        val t = Packets.parse(tcp(phone4, server4, Packets.FLAG_SYN, 1000, 0).let { it }, 40) as Packets.Tcp
        assertTrue(t.syn && !t.hasAck)
        val r = Packets.buildTcpReset(t)!!
        assertValidReset(r, server4, phone4)
        assertEquals(Packets.FLAG_RST or Packets.FLAG_ACK, r[33].toInt())
        assertEquals(0L, Packets.u32(r, 24))
        assertEquals(1001L, Packets.u32(r, 28))
    }

    @Test fun syn_v6_gets_rst_ack() {
        val pkt = tcp(phone6, server6, Packets.FLAG_SYN, 0xFFFFFFFFL, 0)
        val t = Packets.parse(pkt, pkt.size) as Packets.Tcp
        assertTrue(t.v6)
        val r = Packets.buildTcpReset(t)!!
        assertValidReset(r, server6, phone6)
        assertEquals(0L, Packets.u32(r, 48)) // ack wraps: ISN 0xFFFFFFFF + 1
        assertEquals(Packets.FLAG_RST or Packets.FLAG_ACK, r[53].toInt())
    }

    @Test fun data_on_existing_connection_gets_bare_rst_at_their_ack() {
        val pkt = tcp(phone4, server4, Packets.FLAG_ACK or 0x08, 5000, 777777, payload = 100)
        val t = Packets.parse(pkt, pkt.size) as Packets.Tcp
        assertEquals(100, t.payloadLen)
        val r = Packets.buildTcpReset(t)!!
        assertValidReset(r, server4, phone4)
        assertEquals(Packets.FLAG_RST, r[33].toInt())
        assertEquals(777777L, Packets.u32(r, 24))
    }

    @Test fun rst_is_never_answered() {
        val pkt = tcp(phone4, server4, Packets.FLAG_RST, 1, 0)
        assertNull(Packets.buildTcpReset(Packets.parse(pkt, pkt.size) as Packets.Tcp))
    }

    @Test fun udp_round_trip_still_parses() {
        val payload = ByteArray(33) { it.toByte() }
        val pkt = Packets.buildUdp(phone4, byteArrayOf(10, 111, 222.toByte(), 53), 40000, 53, payload)
        val u = Packets.parse(pkt, pkt.size) as Packets.Udp
        assertEquals(53, u.dstPort)
        assertArrayEquals(payload, u.payload)
    }

    @Test fun ipv6_udp_is_reported_with_destination() {
        val p = ByteArray(48)
        p[0] = 0x60; Packets.put16(p, 4, 8); p[6] = 17
        System.arraycopy(server6, 0, p, 24, 16)
        val o = Packets.parse(p, p.size)
        assertNotNull(o)
        assertArrayEquals(server6, (o as Packets.Other).dst)
    }

    @Test fun garbage_does_not_crash() {
        val rnd = java.util.Random(7)
        repeat(50_000) {
            val b = ByteArray(rnd.nextInt(80)).also { rnd.nextBytes(it) }
            if (b.isNotEmpty()) b[0] = (if (rnd.nextBoolean()) 0x45 else 0x60).toByte()
            val p = Packets.parse(b, b.size)
            if (p is Packets.Tcp) Packets.buildTcpReset(p)
        }
    }
}
