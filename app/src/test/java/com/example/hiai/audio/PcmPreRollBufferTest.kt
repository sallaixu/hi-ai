package com.example.hiai.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPreRollBufferTest {

    @Test
    fun `drain should keep only the latest bytes in order`() {
        val buffer = PcmPreRollBuffer(capacityBytes = 10)

        buffer.add(byteArrayOf(1, 2, 3, 4))
        buffer.add(byteArrayOf(5, 6, 7))
        buffer.add(byteArrayOf(8, 9, 10, 11))

        val drained = buffer.drain()

        assertArrayEquals(byteArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11), drained)
    }

    @Test
    fun `drain should clear buffer after reading`() {
        val buffer = PcmPreRollBuffer(capacityBytes = 10)

        buffer.add(byteArrayOf(1, 2, 3, 4))
        assertTrue(buffer.drain().isNotEmpty())
        assertTrue(buffer.drain().isEmpty())
    }
}
