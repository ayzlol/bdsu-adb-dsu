/*
 * bDSU - Android DSU sideloader
 * Copyright (C) 2025-2026 unfayd and ayzlol (the_pan)
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Small, dependency-free XZ/gzip size readers.
 *
 * The DSU preparation screen used to fake its percentage: it counted megabytes
 * and clamped the result, so every install sat at 50% almost immediately. This
 * file reads the real uncompressed size from the container metadata instead:
 *
 *   - XZ   stores an Index at the end of every stream. The footer points at it
 *          and the index records the uncompressed size of every block. Reading
 *          it costs two tiny seeks and no decompression at all.
 *   - gzip stores the uncompressed size (mod 2^32) in the last four bytes.
 *
 * If anything is malformed or unsupported we return -1 and callers fall back to
 * an estimate / indeterminate progress instead of inventing a number.
 *
 * Modified: 2026-11-08 (bDSU 1.3.3)
 */

package com.bdsu.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Exact uncompressed payload size of a single-stream .xz file, or -1. */
fun readXzUncompressedSize(file: File): Long = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        readXzUncompressedSize(raf.channel, raf.length())
    }
}.getOrDefault(-1L)

/**
 * Exact uncompressed payload size of a single-stream .xz file, read through an
 * already-open channel (this also works for content:// document descriptors).
 */
fun readXzUncompressedSize(channel: FileChannel, size: Long): Long = runCatching {
    // Smallest possible stream is far larger than this; reject immediately.
    if (size < 24L) return@runCatching -1L

    val footer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    if (!readFully(channel, footer, size - 12L)) return@runCatching -1L
    // Footer layout: CRC32 (4), Backward Size (4), Stream Flags (2), "YZ" (2).
    if (footer.get(10) != 0x59.toByte() || footer.get(11) != 0x5A.toByte()) return@runCatching -1L
    val backwardSize = ((footer.getInt(4).toLong() and 0xFFFFFFFFL) + 1L) * 4L
    if (backwardSize < 8L || backwardSize > 16L * 1024L * 1024L) return@runCatching -1L

    val indexStart = size - 12L - backwardSize
    if (indexStart < 0L) return@runCatching -1L

    val index = ByteBuffer.allocate(backwardSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
    if (!readFully(channel, index, indexStart)) return@runCatching -1L
    // The Index field starts with the 0x00 indicator byte, then the record count.
    if (index.get().toInt() != 0) return@runCatching -1L
    val recordCount = readVli(index) ?: return@runCatching -1L
    if (recordCount !in 1L..1_000_000L) return@runCatching -1L

    var total = 0L
    var records = 0L
    while (records < recordCount) {
        // Each record is (Unpadded Size, Uncompressed Size), both VLIs.
        readVli(index) ?: return@runCatching -1L
        val uncompressed = readVli(index) ?: return@runCatching -1L
        if (uncompressed < 0L || total > Long.MAX_VALUE - uncompressed) return@runCatching -1L
        total += uncompressed
        records++
    }
    if (total <= 0L) -1L else total
}.getOrDefault(-1L)

/** Raw gzip ISIZE field (mod 2^32). Values >= 4 GiB wrap and are reported as -1. */
fun readGzipUncompressedSize(file: File): Long = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        val size = raf.length()
        if (size < 18L) return@use -1L
        raf.seek(size - 4L)
        val tail = ByteArray(4)
        raf.readFully(tail)
        val value = (tail[0].toLong() and 0xFFL) or
            ((tail[1].toLong() and 0xFFL) shl 8) or
            ((tail[2].toLong() and 0xFFL) shl 16) or
            ((tail[3].toLong() and 0xFFL) shl 24)
        if (value <= 0L) -1L else value
    }
}.getOrDefault(-1L)

private fun readFully(channel: FileChannel, buffer: ByteBuffer, position: Long): Boolean {
    buffer.clear()
    var at = position
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, at)
        if (read <= 0) return false
        at += read
    }
    // Position the buffer for relative reads (the XZ index uses get()).
    buffer.flip()
    return true
}

/** XZ variable-length integer: little-endian base-128, high bit = continuation. */
private fun readVli(buffer: ByteBuffer): Long? {
    var value = 0L
    var shift = 0
    while (buffer.hasRemaining()) {
        val b = buffer.get().toInt() and 0xFF
        if (shift == 63 && (b and 0x7E) != 0) return null
        value = value or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return value
        shift += 7
        if (shift > 63) return null
    }
    return null
}
