package com.droiddock.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoragePathsTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var priv: File

    @Before fun setUp() {
        base = Files.createTempDirectory("sp").toFile().canonicalFile
        root = File(base, "emulated0").also { it.mkdirs() }
        File(root, "DCIM").mkdirs()
        File(base, "emulated0evil").mkdirs()
        priv = File(base, "data/app").also { it.mkdirs() }
    }

    @After fun tearDown() { base.deleteRecursively() }

    private fun r(p: String) = StoragePaths.resolve(p, listOf(root), listOf(priv))

    @Test fun `normal path resolves`() {
        assertEquals(File(root, "DCIM").path, r("${root.path}/DCIM")?.path)
        assertNotNull(r(root.path))
    }

    @Test fun `dot dot traversal is rejected`() {
        assertNull(r("${root.path}/../data/app"))
        assertNull(r("${root.path}/DCIM/../../emulated0evil"))
    }

    @Test fun `dot dot that stays inside is fine`() {
        assertEquals(File(root, "DCIM").path, r("${root.path}/DCIM/../DCIM")?.path)
    }

    @Test fun `sibling sharing a name prefix is rejected`() {
        assertNull(r("${base.path}/emulated0evil"))
        assertNull(r("${base.path}/emulated0evil/x"))
    }

    @Test fun `app private dir is rejected even when a root contains it`() {
        assertNull(StoragePaths.resolve("${priv.path}/token", listOf(base), listOf(priv)))
        assertNotNull(StoragePaths.resolve("${root.path}/DCIM", listOf(base), listOf(priv)))
    }

    @Test fun `symlink inside root pointing outside is rejected`() {
        val link = File(root, "sneaky").toPath()
        try { Files.createSymbolicLink(link, priv.toPath()) } catch (e: Exception) { return }
        assertNull(r("${root.path}/sneaky/token"))
    }

    @Test fun `symlinked root alias resolves`() {
        val alias = File(base, "sdcard").toPath()
        try { Files.createSymbolicLink(alias, root.toPath()) } catch (e: Exception) { return }
        assertEquals(File(root, "DCIM").path, r("${base.path}/sdcard/DCIM")?.path)
    }

    @Test fun `root detection and nul bytes`() {
        assertEquals(true, StoragePaths.isRoot(root, listOf(root)))
        assertEquals(false, StoragePaths.isRoot(File(root, "DCIM"), listOf(root)))
        assertNull(r("${root.path}/DCIM\u0000/../x"))
    }
}
