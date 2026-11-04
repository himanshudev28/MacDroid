package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddrsTest {
    private val hotspot = listOf("10.131.164.37" to 24, "192.0.0.2" to 32)

    @Test
    fun `current subnet address goes ahead of the stale one`() {
        assertEquals(
            listOf("10.131.164.254", "192.168.31.50"),
            orderBySubnet(listOf("192.168.31.50", "10.131.164.254"), hotspot),
        )
    }

    @Test
    fun `no local match keeps order`() {
        assertEquals(
            listOf("192.168.31.50", "172.16.0.9"),
            orderBySubnet(listOf("192.168.31.50", "172.16.0.9"), hotspot),
        )
        assertEquals(listOf("b", "a").map { "1.1.1.$it".replace("a", "1").replace("b", "2") },
            orderBySubnet(listOf("1.1.1.2", "1.1.1.1"), emptyList()))
    }

    @Test
    fun `dedupes and keeps relative order within groups`() {
        assertEquals(
            listOf("10.131.164.5", "10.131.164.9", "8.8.8.8"),
            orderBySubnet(listOf("8.8.8.8", "10.131.164.5", "8.8.8.8", "10.131.164.9", "10.131.164.5"), hotspot),
        )
    }

    @Test
    fun `garbage and bad prefixes never match`() {
        assertFalse(sameSubnet("nope", "10.0.0.1", 24))
        assertFalse(sameSubnet("10.0.0.1", "10.0.0.2", 0))
        assertTrue(sameSubnet("10.0.0.1", "10.0.0.1", 32))
        assertFalse(sameSubnet("10.0.0.1", "10.0.0.2", 32))
        assertTrue(sameSubnet("10.0.1.1", "10.0.0.2", 23))
    }

    @Test
    fun `only addresses not dialed this round remain`() {
        assertEquals(
            listOf("10.131.164.254"),
            notTriedYet(listOf("192.168.31.50", "10.131.164.254", "10.131.164.254"), setOf("192.168.31.50")),
        )
        assertEquals(emptyList<String>(), notTriedYet(listOf("a"), setOf("a")))
    }

    @Test
    fun `private ranges`() {
        assertTrue(isPrivateLan("10.131.164.37"))
        assertTrue(isPrivateLan("192.168.43.1"))
        assertTrue(isPrivateLan("172.20.10.1"))
        assertFalse(isPrivateLan("172.32.0.1"))
        assertFalse(isPrivateLan("192.0.0.2"))
        assertFalse(isPrivateLan("100.64.0.1"))
    }

    private fun kd(name: String, ip: String, tok: String) = KnownDevice(name, listOf(ip), 48484, tok, 0L)

    @Test
    fun `known device matches by address before name`() {
        val a = kd("Mac", "10.0.0.2", "a")
        val b = kd("Mac", "10.0.0.3", "b")
        assertEquals(b, matchKnown(listOf(a, b), listOf("1.2.3.4", "10.0.0.3"), 48484, "mac"))
    }

    @Test
    fun `ambiguous name match is refused`() {
        val a = kd("Mac", "10.0.0.2", "a")
        val b = kd("Mac", "10.0.0.3", "b")
        assertEquals(null, matchKnown(listOf(a, b), listOf("9.9.9.9"), 48484, "Mac"))
        assertEquals(a, matchKnown(listOf(a), listOf("9.9.9.9"), 48484, "MAC"))
    }

    @Test
    fun `candidates prefer the paired Mac's name and are bounded`() {
        val mine = DiscoveredMac("Mine", "10.0.0.2", 48484, ips = listOf("10.0.0.2", "192.168.1.2"))
        val other = DiscoveredMac("Other", "10.0.0.9", 48484)
        assertEquals(listOf("10.0.0.2", "192.168.1.2"), candidateAddresses(listOf(other, mine), "mine"))
        assertEquals(listOf("10.0.0.9", "10.0.0.2", "192.168.1.2"), candidateAddresses(listOf(other, mine), "Gone"))
        assertEquals(1, candidateAddresses(listOf(mine), null, max = 1).size)
    }
}
