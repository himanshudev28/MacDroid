package com.droiddock.app.ui

import com.droiddock.app.ui.pairing.DeepLinkDecision
import com.droiddock.app.ui.pairing.evaluateDeepLink
import com.droiddock.app.ui.pairing.isValidHostOrIp
import com.droiddock.app.ui.pairing.parseManualPort
import com.droiddock.app.validTabs
import com.droiddock.app.ui.theme.NavLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkPairingTest {
    private val fp = "7iF8Sz-mw4_5-QGyqC_ptXO0NB7toSxHHpHmz7EXwfs"
    private val base = "droiddock://pair?v=1&name=My%20Mac&ips=192.168.1.5&port=48600&token=abc"

    @Test fun `deep link without fp is refused`() =
        assertEquals(DeepLinkDecision.RefusedNoFingerprint, evaluateDeepLink(base))

    @Test fun `valid deep link needs confirmation`() {
        val d = evaluateDeepLink("$base&fp=$fp")
        assertTrue(d is DeepLinkDecision.Confirm)
        assertEquals("My Mac", (d as DeepLinkDecision.Confirm).pairing.macName)
    }

    @Test fun `malformed or foreign links are invalid`() {
        assertEquals(DeepLinkDecision.Invalid, evaluateDeepLink("droiddock://pair?ips=&token="))
        assertEquals(DeepLinkDecision.Invalid, evaluateDeepLink("https://example.com"))
        assertEquals(DeepLinkDecision.Invalid, evaluateDeepLink("$base&fp=short"))
    }

    @Test fun `manual host and port validation`() {
        assertTrue(isValidHostOrIp("192.168.0.108"))
        assertTrue(isValidHostOrIp("my-mac.local"))
        assertTrue(isValidHostOrIp("fe80::1"))
        assertFalse(isValidHostOrIp("999.1.1.1"))
        assertFalse(isValidHostOrIp("192.168.0"))
        assertFalse(isValidHostOrIp("not an ip"))
        assertEquals(48484, parseManualPort(""))
        assertEquals(8080, parseManualPort(" 8080 "))
        assertNull(parseManualPort("0"))
        assertNull(parseManualPort("70000"))
        assertNull(parseManualPort("abc"))
    }

    @Test fun `legacy default tab is not a valid tab`() {
        assertFalse("macfs" in validTabs(NavLayout.TABS_4))
        assertFalse("clipboard" in validTabs(NavLayout.TABS_4))
        assertTrue("clipboard" in validTabs(NavLayout.CLASSIC_5))
    }
}
