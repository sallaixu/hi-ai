package com.example.hiai.audio

import java.util.ArrayDeque

class PcmPreRollBuffer(
    private val capacityBytes: Int
) {
    private val lock = Any()
    private val buffer = ArrayDeque<Byte>(capacityBytes)

    fun add(data: ByteArray) {
        add(data, data.size)
    }

    fun add(data: ByteArray, length: Int) {
        if (length <= 0 || capacityBytes <= 0) {
            return
        }

        val safeLength = minOf(length, data.size)
        synchronized(lock) {
            for (i in 0 until safeLength) {
                buffer.addLast(data[i])
            }
            while (buffer.size > capacityBytes) {
                buffer.removeFirst()
            }
        }
    }

    fun drain(): ByteArray {
        synchronized(lock) {
            if (buffer.isEmpty()) {
                return ByteArray(0)
            }

            val output = ByteArray(buffer.size)
            var index = 0
            while (buffer.isNotEmpty()) {
                output[index++] = buffer.removeFirst()
            }
            return output
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
}
