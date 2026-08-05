package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address-book rules that decide whether the phone can still reach the Mac.
 *
 * These are pure functions living in `Prefs.kt` beside code that needs a
 * `Context`; nothing here touches the framework, which is why they're testable
 * on the JVM without Robolectric.
 *
 * They matter more than their size suggests: a tailnet address is the only one
 * that survives leaving the house, and the only one nothing can rediscover —
 * both discovery paths (UDP broadcast, mDNS) are link-local. Dropping it is
 * silent and unrecoverable short of re-pairing.
 */
class AddressBookTest {

    @Test
    fun `tailnet range boundaries are exact`() {
        // Tailscale hands out 100.64.0.0/10 — the CGNAT block.
        assertTrue(isTailnetAddress("100.64.0.1"))
        assertTrue(isTailnetAddress("100.127.255.254"))
        assertTrue(isTailnetAddress("100.100.100.100"))

        // One below and one above the block. Both are ordinary public IPv4 and
        // must not get the protection that keeps an address pinned forever.
        assertFalse(isTailnetAddress("100.63.255.255"))
        assertFalse(isTailnetAddress("100.128.0.0"))
    }

    @Test
    fun `ordinary addresses are not mistaken for tailnet ones`() {
        assertFalse(isTailnetAddress("192.168.0.5"))
        assertFalse(isTailnetAddress("10.0.0.1"))
        assertFalse(isTailnetAddress("172.16.0.3"))
    }

    @Test
    fun `malformed input never throws`() {
        // These arrive from stored prefs and from the wire, so a bad value has
        // to degrade rather than take down the connect loop.
        assertFalse(isTailnetAddress(""))
        assertFalse(isTailnetAddress("not.an.ip"))
        assertFalse(isTailnetAddress("100.64.0"))
        assertFalse(isTailnetAddress("100.64.0.1.5"))
        assertFalse(isTailnetAddress("100.x.0.1"))
        assertFalse(isTailnetAddress("::1"))
    }

    @Test
    fun `trimming keeps the tailnet address even when it falls past the cap`() {
        // The regression this exists for: four LAN addresses churn through the
        // front of the list and a plain take(4) silently evicts the 100.x one.
        val ips = listOf(
            "192.168.0.5", "192.168.0.9", "10.0.0.2", "172.16.0.3", "100.101.102.103"
        )
        val trimmed = trimAddresses(ips, 4)

        assertEquals(4, trimmed.size)
        assertTrue("tailnet address must survive", trimmed.contains("100.101.102.103"))
        // The most recently useful addresses are at the head, so it's the tail
        // that gets displaced.
        assertEquals(listOf("192.168.0.5", "192.168.0.9", "10.0.0.2", "100.101.102.103"), trimmed)
    }

    @Test
    fun `a tailnet address already inside the cap is left alone`() {
        val ips = listOf("100.101.102.103", "192.168.0.5")
        assertEquals(ips, trimAddresses(ips, 4))
    }

    @Test
    fun `a list with no tailnet address is a plain truncation`() {
        val ips = listOf("192.168.0.5", "192.168.0.6", "192.168.0.7", "10.0.0.1", "10.0.0.2")
        assertEquals(ips.take(3), trimAddresses(ips, 3))
    }

    @Test
    fun `duplicates are collapsed without losing the tailnet address`() {
        // markSeen prepends the working address to a list that may already
        // contain it, so duplicates are the normal case rather than an edge one.
        val ips = listOf("192.168.0.5", "192.168.0.5", "192.168.0.9", "100.64.0.7")
        val trimmed = trimAddresses(ips, 3)

        assertEquals(trimmed.distinct(), trimmed)
        assertTrue(trimmed.contains("100.64.0.7"))
    }

    @Test
    fun `trimming a short list changes nothing`() {
        val ips = listOf("192.168.0.5")
        assertEquals(ips, trimAddresses(ips, 4))
    }

    @Test
    fun `a known device round-trips to the pairing the connect loop uses`() {
        val device = KnownDevice(
            macName = "Himanshu's MacBook Air",
            ips = listOf("192.168.0.5", "100.64.0.7"),
            port = 48484,
            token = "tok-abc",
            lastSeenAt = 1_700_000_000_000L,
        )
        val pairing = device.toPairing()

        assertEquals(device.macName, pairing.macName)
        assertEquals(device.ips, pairing.ips)
        assertEquals(device.port, pairing.port)
        assertEquals(device.token, pairing.token)
        // Identity is the token, not the name or address — two Macs can share a
        // name and addresses move between networks.
        assertEquals("tok-abc", device.id)
    }
}
