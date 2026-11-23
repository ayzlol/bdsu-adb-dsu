package com.bdsu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.FileOutputStream

/** Verifies that progress percentages are based on the real XZ index, not a guess. */
class XzSizeTest {
    @Test
    fun readsExactUncompressedSizeFromXzIndex() {
        val file = File.createTempFile("bdsu-size-", ".xz")
        val payload = ByteArray(3 * 1024 * 1024 + 12345) { index -> (index * 31 + 7).toByte() }
        try {
            XZOutputStream(FileOutputStream(file), LZMA2Options(1)).use { it.write(payload) }
            assertEquals(payload.size.toLong(), readXzUncompressedSize(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun sumsUncompressedSizesAcrossXzBlocks() {
        val file = File.createTempFile("bdsu-blocks-", ".xz")
        val part = ByteArray(512 * 1024 + 77) { index -> (index * 13 + 3).toByte() }
        try {
            XZOutputStream(FileOutputStream(file), LZMA2Options(1)).use { xz ->
                xz.write(part)
                xz.endBlock()
                xz.write(part)
                xz.endBlock()
                xz.write(part)
            }
            assertEquals((part.size * 3).toLong(), readXzUncompressedSize(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun returnsMinusOneForGarbage() {
        val file = File.createTempFile("bdsu-garbage-", ".xz")
        try {
            file.writeBytes(ByteArray(256) { 0x42 })
            assertEquals(-1L, readXzUncompressedSize(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsGzipIsize() {
        val file = File.createTempFile("bdsu-size-", ".gz")
        val payload = ByteArray(1024 * 1024 + 99) { index -> (index * 7).toByte() }
        try {
            java.util.zip.GZIPOutputStream(FileOutputStream(file)).use { it.write(payload) }
            assertTrue(readGzipUncompressedSize(file) == payload.size.toLong())
        } finally {
            file.delete()
        }
    }
}
