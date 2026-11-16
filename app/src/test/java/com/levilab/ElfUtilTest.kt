package com.levilab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ElfUtilTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun make(name: String, head: ByteArray, size: Int): File {
        val f = File(tmp.root, name)
        f.outputStream().use { it.write(head); it.write(ByteArray(size - head.size) { 0x41 }) }
        return f
    }

    @Test fun textFileNamedSoIsRejected() {
        val f = make("libminecraftpe.so", "this is plain text, not ELF".toByteArray(), 2 * 1024 * 1024)
        assertFalse(ElfUtil.validate(f).ok)
    }

    @Test fun elfHeaderButTooSmallIsRejected() {
        val f = make("libminecraftpe.so", byteArrayOf(0x7F, 0x45, 0x4C, 0x46), 4096)
        assertFalse(ElfUtil.validate(f).ok)
    }

    @Test fun elfHeaderAndLargeSizeIsAccepted() {
        val f = make("libminecraftpe.so", byteArrayOf(0x7F, 0x45, 0x4C, 0x46), 2 * 1024 * 1024)
        assertTrue(ElfUtil.validate(f).ok)
    }
}
