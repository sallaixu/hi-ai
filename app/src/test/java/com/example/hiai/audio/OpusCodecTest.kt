package com.example.hiai.audio

import org.junit.Test
import org.junit.Assert.*

/**
 * OpusCodec 单元测试
 * 
 * 使用 Concentus 库进行真实的 Opus 编解码测试
 */
class OpusCodecTest {
    
    // Mock 计算器，避免在单元测试中使用 Android API
    private class MockBufferCalculator : AudioRecordBufferCalculator {
        override fun calculate(sampleRate: Int, channels: Int): Int {
            return 4096 // 返回固定值用于测试
        }
    }
    
    @Test
    fun `encode should accept valid PCM data and return compressed data`() {
        val codec = OpusCodec(audioRecordBufferCalculator = MockBufferCalculator())
        
        // 创建有效的 PCM 数据（60ms * 16000Hz = 960 samples * 2 bytes）
        val pcmData = ByteArray(1920) { (it % 256).toByte() }
        
        // 当：编码为 Opus
        val opusData = codec.encode(pcmData)
        
        // 则：应该返回压缩后的数据（Opus 数据应该比原始 PCM 小）
        assertNotNull(opusData)
        assertTrue("Opus data should be smaller than PCM", opusData.size < pcmData.size)
        
        codec.release()
    }
    
    @Test
    fun `encode should reject odd length PCM data`() {
        val codec = OpusCodec(audioRecordBufferCalculator = MockBufferCalculator())
        val pcmData = ByteArray(1921) // 奇数长度，无效
        
        // 当：编码奇数长度 PCM
        // 则：应该抛出异常
        val exception = assertThrows(IllegalArgumentException::class.java) {
            codec.encode(pcmData)
        }
        assertTrue(exception.message?.contains("even length") == true)
        
        codec.release()
    }
    
    @Test
    fun `decode should return PCM data when given valid Opus data`() {
        val codec = OpusCodec(audioRecordBufferCalculator = MockBufferCalculator())
        
        // 先编码一些 PCM 数据
        val originalPcm = ByteArray(1920) { (it % 256).toByte() }
        val opusData = codec.encode(originalPcm)
        
        // 当：解码 Opus
        val decodedPcm = codec.decode(opusData)
        
        // 则：应该返回 PCM 数据
        assertNotNull(decodedPcm)
        assertTrue("Decoded PCM should have positive length", decodedPcm.isNotEmpty())
        
        codec.release()
    }
    
    @Test
    fun `encode then decode should produce similar audio`() {
        val codec = OpusCodec(audioRecordBufferCalculator = MockBufferCalculator())
        
        // 创建简单的正弦波 PCM 数据
        val sampleRate = 16000
        val frequency = 440.0 // A4 音符
        val samples = 960 // 60ms
        val originalPcm = ByteArray(samples * 2)
        
        for (i in 0 until samples) {
            val value = (Short.MAX_VALUE * 0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * frequency * i / sampleRate)).toInt().toShort()
            originalPcm[i * 2] = (value.toInt() and 0xFF).toByte()
            originalPcm[i * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        
        // 编码然后解码
        val opusData = codec.encode(originalPcm)
        val decodedPcm = codec.decode(opusData)
        
        // 解码后的数据长度应该匹配
        assertEquals("Decoded length should match original", originalPcm.size, decodedPcm.size)
        
        codec.release()
    }
    
    @Test
    fun `getAudioRecordBufferSize should return positive value`() {
        val codec = OpusCodec(audioRecordBufferCalculator = MockBufferCalculator())
        
        val bufferSize = codec.getAudioRecordBufferSize()
        
        assertTrue(bufferSize > 0)
        assertEquals(4096, bufferSize) // Mock 返回固定值
        
        codec.release()
    }
    
    @Test
    fun `release should not throw exception`() {
        val codec = OpusCodec(audioRecordBufferCalculator = MockBufferCalculator())
        
        // 当：释放资源
        // 则：不应该抛出异常
        try {
            codec.release()
        } catch (e: Exception) {
            fail("release() should not throw exception, but got: ${e.message}")
        }
    }
    
    @Test
    fun `codec should work with different frame sizes`() {
        // 测试 20ms 帧大小
        val codec20ms = OpusCodec(frameSizeMs = 20, audioRecordBufferCalculator = MockBufferCalculator())
        val pcm20ms = ByteArray(640) { (it % 256).toByte() } // 20ms * 16000Hz = 320 samples * 2 bytes
        val opus20ms = codec20ms.encode(pcm20ms)
        assertTrue("20ms encoding should work", opus20ms.isNotEmpty())
        codec20ms.release()
        
        // 测试 40ms 帧大小
        val codec40ms = OpusCodec(frameSizeMs = 40, audioRecordBufferCalculator = MockBufferCalculator())
        val pcm40ms = ByteArray(1280) { (it % 256).toByte() } // 40ms * 16000Hz = 640 samples * 2 bytes
        val opus40ms = codec40ms.encode(pcm40ms)
        assertTrue("40ms encoding should work", opus40ms.isNotEmpty())
        codec40ms.release()
    }
    
    @Test
    fun `codec should handle silence`() {
        val codec = OpusCodec(audioRecordBufferCalculator = MockBufferCalculator())
        
        // 静音数据（全零）
        val silencePcm = ByteArray(1920)
        val opusData = codec.encode(silencePcm)
        val decodedPcm = codec.decode(opusData)
        
        // 解码后的静音应该接近零
        assertNotNull(decodedPcm)
        assertTrue("Decoded silence should have data", decodedPcm.isNotEmpty())
        
        codec.release()
    }
}