package com.droiddock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure functions that decide whether the phone offers an update.
 *
 * Both fail silently when they're wrong — a bad comparison doesn't crash, it
 * just means the update never appears (or appears forever), which is the kind
 * of bug that survives a release. Neither touches the framework, so they run on
 * the JVM alongside [AddressBookTest].
 */
class UpdateCheckerTest {

    // ── compareVersions ──────────────────────────────────────────────────────

    @Test
    fun `numeric components are compared numerically, not as text`() {
        // The whole reason this isn't a string compare: lexically "1.10.0" sorts
        // BELOW "1.9.0", which would strand every user on the tenth release.
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9") > 0)
    }

    @Test
    fun `equal versions compare equal, however they're written`() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "1.0.0"))
        // Missing components read as zero, so a two-part tag matches its
        // three-part equivalent rather than looking older.
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        // A leading `v` is the tag's, not the version's.
        assertEquals(0, UpdateChecker.compareVersions("v1.2.3", "1.2.3"))
    }

    @Test
    fun `non-numeric suffixes are ignored rather than throwing`() {
        // A `-beta` tag must not be able to make the check crash; it compares
        // as its numeric prefix.
        assertEquals(0, UpdateChecker.compareVersions("1.2.3-beta", "1.2.3"))
        assertTrue(UpdateChecker.compareVersions("1.3.0-rc1", "1.2.3") > 0)
    }

    // ── parseRelease ─────────────────────────────────────────────────────────

    private fun releaseJson(tag: String, assetName: String = "DroidDock-Android.apk") = """
        {
          "tag_name": "$tag",
          "body": "Fixes the thing.",
          "assets": [
            { "name": "DroidDock.dmg", "browser_download_url": "https://example.test/mac.dmg", "size": 40 },
            { "name": "$assetName", "browser_download_url": "https://example.test/app.apk", "size": 1024 }
          ]
        }
    """.trimIndent()

    @Test
    fun `a newer tag yields the APK asset`() {
        val release = UpdateChecker.parseRelease(releaseJson("v1.1.0"), currentVersion = "1.0.0")
        assertNotNull(release)
        // The `v` belongs to the tag; the version the UI shows should not carry it.
        assertEquals("1.1.0", release!!.version)
        assertEquals("https://example.test/app.apk", release.apkUrl)
        assertEquals(1024L, release.sizeBytes)
    }

    @Test
    fun `the current version and older ones are not offered`() {
        assertNull(UpdateChecker.parseRelease(releaseJson("v1.0.0"), currentVersion = "1.0.0"))
        assertNull(UpdateChecker.parseRelease(releaseJson("v0.9.0"), currentVersion = "1.0.0"))
    }

    @Test
    fun `a release without the APK asset offers nothing`() {
        // The tag lands the moment the mac job finishes; the Android job may
        // still be running or may have failed outright. Offering an update with
        // nothing to download would be worse than staying quiet.
        assertNull(
            UpdateChecker.parseRelease(
                releaseJson("v1.1.0", assetName = "something-else.apk"),
                currentVersion = "1.0.0",
            )
        )
    }

    @Test
    fun `a malformed release is treated as no release`() {
        assertNull(UpdateChecker.parseRelease("{}", currentVersion = "1.0.0"))
    }
}
