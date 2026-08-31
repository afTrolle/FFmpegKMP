// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteArrayFileHandleTest {
    private val bytes = "Hello, FFmpegKMP!".encodeToByteArray()

    @Test
    fun sizeMatchesByteArrayLength() {
        val handle = ByteArrayFileHandle(bytes)
        assertEquals(bytes.size.toLong(), handle.size())
    }

    @Test
    fun readsFromTheStart() {
        val handle = ByteArrayFileHandle(bytes)
        val destination = ByteArray(5)
        val read = handle.read(0L, destination, 0, 5)
        assertEquals(5, read)
        assertEquals("Hello", destination.decodeToString())
    }

    @Test
    fun readsFromAnArbitraryOffset() {
        val handle = ByteArrayFileHandle(bytes)
        val destination = ByteArray(9)
        val read = handle.read(7L, destination, 0, 9)
        assertEquals(9, read)
        assertEquals("FFmpegKMP", destination.decodeToString())
    }

    @Test
    fun readsIntoAnOffsetDestinationArrayWithoutDisturbingSurroundingBytes() {
        val handle = ByteArrayFileHandle(bytes)
        val destination = ByteArray(10) { 'x'.code.toByte() }
        val read = handle.read(0L, destination, 3, 5)
        assertEquals(5, read)
        assertEquals("xxxHelloxx", destination.decodeToString())
    }

    @Test
    fun truncatesAReadThatWouldRunPastEndOfFile() {
        val handle = ByteArrayFileHandle(bytes)
        val destination = ByteArray(100)
        val read = handle.read(10L, destination, 0, 100)
        assertEquals(bytes.size - 10, read)
        assertEquals(bytes.decodeToString(10, bytes.size), destination.decodeToString(0, read))
    }

    @Test
    fun readingExactlyAtEndOfFileReportsEndOfStream() {
        val handle = ByteArrayFileHandle(bytes)
        val destination = ByteArray(10)
        assertEquals(-1, handle.read(bytes.size.toLong(), destination, 0, 10))
    }

    @Test
    fun readingPastEndOfFileReportsEndOfStream() {
        val handle = ByteArrayFileHandle(bytes)
        val destination = ByteArray(10)
        assertEquals(-1, handle.read(bytes.size + 5L, destination, 0, 10))
    }

    @Test
    fun repeatedReadsAtDifferentOffsetsReconstructTheWholeFile() {
        // This is the exact access pattern FFmpeg's MOV/MP4 demuxer needs: seeking to the
        // end for the moov atom, then back to the start for sample data.
        val handle = ByteArrayFileHandle(bytes)
        val tail = ByteArray(6)
        val head = ByteArray(5)
        assertEquals(6, handle.read(11L, tail, 0, 6))
        assertEquals(5, handle.read(0L, head, 0, 5))
        assertEquals("egKMP!", tail.decodeToString())
        assertEquals("Hello", head.decodeToString())
    }
}
