package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Review3FixesTest {
    // ---- S2: discovery HMAC. This vector is mirrored on the Mac side. ----
    @Test fun `discovery tag matches the cross-check vector`() {
        val nowMs = 1_700_000_000_000L
        assertEquals(5_666_666L, DiscoveryAuth.window(nowMs))
        assertEquals("71f590d3961937dfe5153c714967a6f5", DiscoveryAuth.tag("test-token-0123456789", 5_666_666L))
        assertEquals(
            "DROIDDOCK:DISCOVER2:71f590d3961937dfe5153c714967a6f5",
            String(DiscoveryAuth.payload("test-token-0123456789", nowMs)),
        )
    }

    @Test fun `discovery payload never contains the token and changes per window`() {
        val tok = "secret-token-xyz"
        val a = String(DiscoveryAuth.payload(tok, 1_700_000_000_000L))
        val b = String(DiscoveryAuth.payload(tok, 1_700_000_000_000L + 300_000L))
        assertFalse(a.contains(tok))
        assertTrue(a != b)
        // same 5-minute window -> same tag
        assertEquals(a, String(DiscoveryAuth.payload(tok, 1_700_000_000_000L + 1_000L)))
    }

    // ---- name sanitising ----
    @Test fun `baseName takes the basename and rejects dot names`() {
        assertEquals("a.txt", SafeNames.baseName("a.txt"))
        assertEquals("passwd", SafeNames.baseName("../../etc/passwd"))
        assertEquals("x.sh", SafeNames.baseName("dir\\sub\\x.sh"))
        assertNull(SafeNames.baseName(""))
        assertNull(SafeNames.baseName("."))
        assertNull(SafeNames.baseName(".."))
        assertNull(SafeNames.baseName("a/.."))
        assertNull(SafeNames.baseName("folder/"))
        assertNull(SafeNames.baseName("   "))
    }

    @Test fun `dirUnder keeps dest inside the allowed roots`() {
        val root = File(System.getProperty("java.io.tmpdir"), "dd-root-test").also { it.mkdirs() }
        val roots = listOf(root)
        assertEquals(root.canonicalFile, SafeNames.dirUnder(root.path, roots))
        assertEquals(File(root, "sub").canonicalFile, SafeNames.dirUnder(File(root, "sub").path, roots))
        assertNull(SafeNames.dirUnder(File(root, "../elsewhere").path, roots))
        assertNull(SafeNames.dirUnder("/etc", roots))
        assertNull(SafeNames.dirUnder(root.path + "-sibling", roots))
    }

    // ---- pin non-downgrade ----
    @Test fun `a pairing with no fp never downgrades an existing pin, QR or not`() {
        val fp = "a".repeat(43)
        val d = KnownDevice("Mac", listOf("10.0.0.2"), 48484, "tok", 5L, fp)
        val p = Pairing(listOf("10.0.0.9"), 48484, "tok", "Mac", null)
        assertEquals(fp, mergePairing(listOf(d), p, authoritative = true).single().fp)
        assertEquals(fp, mergePairing(listOf(d), p, authoritative = false).single().fp)
    }

    // ---- reconnect backoff / boot ----
    @Test fun `backoff grows past 15s up to 60s`() {
        var b = 2_000L
        val seen = mutableListOf<Long>()
        repeat(12) { b = nextBackoff(b); seen += b }
        assertEquals(listOf(4_000L, 8_000L, 15_000L, 30_000L, 60_000L, 60_000L), seen.take(6))
        assertEquals(60_000L, seen.last())
    }

    @Test fun `boot restarts only a paired phone that is not paused`() {
        val now = 1_000L
        assertTrue(BootReceiver.shouldStartOnBoot(true, 0L, now))
        assertTrue(BootReceiver.shouldStartOnBoot(true, 500L, now))
        assertFalse(BootReceiver.shouldStartOnBoot(true, 2_000L, now))
        assertFalse(BootReceiver.shouldStartOnBoot(true, Long.MAX_VALUE, now))
        assertFalse(BootReceiver.shouldStartOnBoot(false, 0L, now))
    }

    @Test fun `removable volumes are allowed but other users and traversal are not`() {
        val storage = File(System.getProperty("java.io.tmpdir"), "dd-storage-test").also { it.deleteRecursively(); it.mkdirs() }
        File(storage, "1A2B-3C4D").mkdirs()
        File(storage, "emulated/10").mkdirs()
        File(storage, "self").mkdirs()
        val roots = SafeNames.volumeRoots(storage)
        assertEquals(listOf(File(storage, "1A2B-3C4D")), roots)
        assertEquals(File(storage, "1A2B-3C4D/DCIM").canonicalFile, SafeNames.dirUnder(File(storage, "1A2B-3C4D/DCIM").path, roots))
        assertNull(SafeNames.dirUnder(File(storage, "emulated/10").path, roots))
        assertNull(SafeNames.dirUnder(File(storage, "1A2B-3C4D/../emulated/10").path, roots))
    }
}
