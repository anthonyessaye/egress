package app.egress.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class DnsTest {

    private val sink4 = byteArrayOf(10, 111, 222.toByte(), 66)
    private val sink6 = InetAddress.getByName("fd65:6772:6573:7300::66").address

    private class Msg {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { out.write(v ushr 8); out.write(v and 0xFF) }
        fun u32(v: Long) = apply { u16((v ushr 16).toInt()); u16((v and 0xFFFF).toInt()) }
        fun name(n: String) = apply { n.split('.').forEach { l -> u8(l.length); l.forEach { u8(it.code) } }; u8(0) }
        fun ptr(off: Int) = apply { u16(0xC000 or off) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        val size get() = out.size()
    }

    /**
     * example.com A → CNAME edge.example.net; two A + one AAAA for the CNAME target (owner names compressed
     * as pointers *into the CNAME's rdata*, i.e. into the answer section); one glue A in additional.
     */
    private fun response(): ByteArray {
        val m = Msg().u16(0x1234).u16(0x8180).u16(1).u16(4).u16(0).u16(1)
        m.name("example.com").u16(1).u16(1)
        m.ptr(12).u16(5).u16(1).u32(3600)
        val cnameRd = m.size + 2
        m.u16(18).name("edge.example.net")
        m.ptr(cnameRd).u16(1).u16(1).u32(300).u16(4).bytes(byteArrayOf(1, 2, 3, 4))
        m.ptr(cnameRd).u16(1).u16(1).u32(300).u16(4).bytes(byteArrayOf(5, 6, 7, 8))
        m.ptr(cnameRd).u16(28).u16(1).u32(300).u16(16).bytes(InetAddress.getByName("2001:db8::1").address)
        m.name("ns.example.net").u16(1).u16(1).u32(86400).u16(4).bytes(byteArrayOf(1, 9, 9, 9))
        return m.out.toByteArray()
    }

    @Test fun blocked_records_are_sunk_in_place() {
        val msg = response()
        val before = msg.size
        val sunk = Dns.sinkAddresses(msg, sink4, sink6, ttl = 5) { it[0].toInt() == 1 || it.size == 16 }
        assertEquals(before, msg.size)
        assertEquals(listOf("1.2.3.4", "2001:db8:0:0:0:0:0:1", "1.9.9.9"), sunk.map { InetAddress.getByAddress(it).hostAddress })

        // The message still parses (pointers intact), blocked addresses now point at the sinks, others untouched.
        val a = Dns.inspectResponse(msg, ttlCap = 0)!!
        assertEquals(0x1234, a.id)
        assertEquals(listOf("10.111.222.66", "5.6.7.8", "fd65:6772:6573:7300:0:0:0:66"), a.ips)
    }

    @Test fun rewritten_ttls_are_cut_but_others_kept() {
        val msg = response()
        Dns.sinkAddresses(msg, sink4, sink6, ttl = 5) { it.contentEquals(byteArrayOf(1, 2, 3, 4)) }
        // Walk to the TTLs: record 2 (sunk A) and record 3 (kept A).
        val ttls = ArrayList<Long>()
        var off = 12 + 13 + 4 // header + "example.com" + qtype/qclass
        repeat(5) {
            off += if (msg[off].toInt() and 0xC0 == 0xC0) 2 else nameLen(msg, off)
            ttls += Packets.u32(msg, off + 4)
            off += 10 + Packets.u16(msg, off + 8)
        }
        assertEquals(listOf(3600L, 5L, 300L, 300L, 86400L), ttls)
    }

    private fun nameLen(m: ByteArray, start: Int): Int {
        var o = start
        while (m[o].toInt() != 0) o += (m[o].toInt() and 0xFF) + 1
        return o + 1 - start
    }

    @Test fun nothing_blocked_leaves_message_identical() {
        val msg = response()
        val copy = msg.copyOf()
        assertTrue(Dns.sinkAddresses(msg, sink4, sink6, 5) { false }.isEmpty())
        assertArrayEquals(copy, msg)
    }

    @Test fun queries_and_garbage_are_ignored() {
        val q = response().also { it[2] = 0x01 } // QR=0
        assertTrue(Dns.sinkAddresses(q, sink4, sink6, 5) { true }.isEmpty())
        val rnd = java.util.Random(3)
        repeat(100_000) {
            val b = ByteArray(rnd.nextInt(120)).also { rnd.nextBytes(it) }
            if (b.size > 3) b[2] = (b[2].toInt() or 0x80).toByte()
            Dns.sinkAddresses(b, sink4, sink6, 5) { true }
        }
        // Truncations of a real message never throw.
        val full = response()
        for (n in 0..full.size) Dns.sinkAddresses(full.copyOf(n), sink4, sink6, 5) { true }
    }
}
