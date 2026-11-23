package app.egress.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetAddress

class IpCountryDbTest {

    companion object {
        lateinit var db: IpCountryDb

        @BeforeClass @JvmStatic fun load() {
            db = File("src/main/assets/ipcountry.bin").inputStream().use { IpCountryDb.read(it) }
        }
    }

    private fun cc(ip: String) = db.country(InetAddress.getByName(ip).address)

    @Test fun decompose_is_minimal_and_exact() {
        val out = ArrayList<Pair<ULong, Int>>()
        IpCountryDb.decompose(10u, 20u, 32) { s, k -> out += s to k }
        assertEquals(listOf(10uL to 1, 12uL to 2, 16uL to 2, 20uL to 0), out)

        out.clear()
        IpCountryDb.decompose(0u, ULong.MAX_VALUE, 64) { s, k -> out += s to k }
        assertEquals(listOf(0uL to 64), out)

        val rnd = java.util.Random(11)
        repeat(2000) {
            val a = rnd.nextInt(1 shl 20).toULong()
            val b = a + rnd.nextInt(1 shl 16).toULong()
            var next = a
            IpCountryDb.decompose(a, b, 32) { s, k ->
                assertEquals("contiguous", next, s)
                assertTrue("aligned", k == 0 || s % (1uL shl k) == 0uL)
                next = s + (1uL shl k)
            }
            assertEquals("covers exactly", b + 1u, next)
        }
    }

    @Test fun lookups() {
        assertEquals("US", cc("8.8.8.8"))
        assertEquals("AU", cc("1.0.0.1"))
        assertEquals("CN", cc("1.0.1.1"))
        assertEquals("RU", cc("2a02:6b8::1"))
        assertNull(cc("10.1.2.3"))
        assertNull(cc("192.168.1.1"))
        assertNull(cc("127.0.0.1"))
        assertNull(cc("0.0.0.0"))
        assertTrue(cc("255.255.255.255") == null)
    }

    @Test fun small_country_is_fully_covered_and_routes_point_inside_it() {
        val plan = db.plan(setOf("LB"), 2500)
        assertEquals(1f, plan.coverage["LB"]!!, 0f)
        assertTrue(plan.size in 300..2500)
        (plan.v4 + plan.v6).forEach { assertEquals(it.toString(), "LB", db.country(it.address)) }
    }

    @Test fun big_country_is_truncated_to_budget_largest_first() {
        val plan = db.plan(setOf("CN"), 1000)
        assertEquals(1000, plan.size)
        assertTrue("CN coverage ${plan.coverage["CN"]}", plan.coverage["CN"]!! > 0.85f)
        assertTrue(plan.v4.zipWithNext().all { (a, b) -> a.prefix <= b.prefix })

        val us = db.plan(setOf("US"), 2500)
        assertEquals(2500, us.size)
        us.v4.forEach { assertEquals("US", db.country(it.address)) }
    }

    @Test fun budget_is_shared_between_countries() {
        val plan = db.plan(setOf("CN", "RU"), 2000)
        assertEquals(2000, plan.size)
        assertTrue(plan.coverage["CN"]!! > 0.8f)
        assertTrue(plan.coverage["RU"]!! > 0.5f)
        assertTrue(db.plan(emptySet(), 2000).size == 0)
    }
}
