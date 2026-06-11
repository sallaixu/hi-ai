package com.example.hiai.audio

import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder

/**
 * Opus 音频编解码器接口
 * 
 * 负责 PCM 和 Opus 格式之间的转换
 * - PCM: 16bit, 16kHz, 单声道
 * - Opus: 压缩格式，用于网络传输
 */
interface OpusCodecInterface {
    /**
     * 编码 PCM 为 Opus
     * 
     * @param pcmData PCM 数据（16bit 有符号整数，小端序）
     * @return Opus 压缩数据
     */
    fun encode(pcmData: ByteArray): ByteArray
    
    /**
     * 解码 Opus 为 PCM
     * 
     * @param opusData Opus 压缩数据
     * @return PCM 数据（16bit）
     */
    fun decode(opusData: ByteArray): ByteArray
    
    /**
     * 获取推荐的 AudioRecord 缓冲区大小
     */
    fun getAudioRecordBufferSize(): Int
    
    /**
     * 释放资源
     */
    fun release()
}

/**
 * Opus 编解码器实现
 * 
 * 使用 Concentus 库（纯 Java 实现），避免 C++ 库的编译和运行时冲突
 * 
 * @param sampleRate 采样率，默认 16000Hz
 * @param channels 通道数，默认 1（单声道）
 * @param frameSizeMs 帧大小（毫秒），默认 60ms
 * @param bitrate 比特率，默认 24000 bps（语音通话推荐值）
 */
class OpusCodec(
    val sampleRate: Int = 16000,  // 公开，供外部获取当前采样率
    private val channels: Int = 1,
    private val frameSizeMs: Int = 60,
    private val bitrate: Int = 24000,
    private val audioRecordBufferCalculator: AudioRecordBufferCalculator = DefaultAudioRecordBufferCalculator()
) : OpusCodecInterface {
    
    // 每帧样本数 = 采样率 * 帧大小 (ms) / 1000
    private val frameSize: Int = (sampleRate * frameSizeMs) / 1000
    
    // PCM 缓冲区大小 = 每帧样本数 * 通道数 * 每样本字节数 (16bit=2bytes)
    val pcmBufferSize: Int = frameSize * channels * 2
    
    // Concentus 编码器和解码器
    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null
    private val encodeLock = Any()  // 编码器锁，Concentus OpusEncoder 非线程安全
    
    init {
        try {
            // 初始化 Concentus 编码器
            encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
                bitrate = this@OpusCodec.bitrate
                complexity = 10  // 与服务端一致
            }
            
            // 初始化 Concentus 解码器（解码器不需要指定 Application 类型）
            decoder = OpusDecoder(sampleRate, channels)
        } catch (e: Exception) {
            release()
            throw IllegalStateException("Failed to initialize Opus codec: ${e.message}", e)
        }
    }
    
    override fun encode(pcmData: ByteArray): ByteArray {
        require(pcmData.size % 2 == 0) { "PCM data must be even length (16-bit samples)" }

        return synchronized(encodeLock) {
            val encoderInstance = encoder ?: throw IllegalStateException("Encoder not initialized")

            // 将字节数组转换为 short 数组（小端序）
            val samples = pcmData.size / 2
            val shortData = ShortArray(samples)
            for (i in 0 until samples) {
                shortData[i] = ((pcmData[i * 2 + 1].toInt() shl 8) or (pcmData[i * 2].toInt() and 0xFF)).toShort()
            }

            // 编码为 Opus
            val maxPacketSize = samples * 2  // 最大不超过输入大小
            val output = ByteArray(maxPacketSize)
            val encodedSize = encoderInstance.encode(shortData, 0, frameSize, output, 0, output.size)

            if (encodedSize > 0) output.copyOf(encodedSize) else ByteArray(0)
        }
    }
    
    override fun decode(opusData: ByteArray): ByteArray {
        val decoderInstance = decoder ?: throw IllegalStateException("Decoder not initialized")
        
        // 诊断：打印输入 Opus 数据大小
        Log.d("OpusCodec", "[DIAG] decode: opusData.size=${opusData.size}, sampleRate=$sampleRate, frameSize=$frameSize")
        
        // 解码为 PCM
        val output = ShortArray(frameSize)
        val decodedSamples = try {
            decoderInstance.decode(opusData, 0, opusData.size, output, 0, frameSize, false)
        } catch (e: Exception) {
            Log.e("OpusCodec", "Concentus decode throws exception for input size ${opusData.size}, expected frameSize ${frameSize}", e)
            throw e
        }
        
        if (decodedSamples <= 0) {
            Log.w("OpusCodec", "Concentus decode returned <= 0: $decodedSamples")
            return ByteArray(0)
        }
        
        // 将 short 数组转换为字节数组（小端序）
        val pcmData = ByteArray(decodedSamples * 2)
        for (i in 0 until decodedSamples) {
            pcmData[i * 2] = (output[i].toInt() and 0xFF).toByte()
            pcmData[i * 2 + 1] = (output[i].toInt() shr 8).toByte()
        }
        
        // 诊断：打印解码后的 PCM 样本值（前8个采样点 = 16字节）
        val samplesHex = pcmData.take(16).joinToString(" ") { "%02x".format(it) }
        val samplesShort = (0 until minOf(8, decodedSamples)).map { i ->
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val value = (high shl 8) or low
            if (value >= 0x8000) value - 0x10000 else value
        }
        
        // 诊断：计算 PCM 数据的统计信息（最大值、最小值、RMS）
        val allSamples = (0 until decodedSamples).map { i ->
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val value = (high shl 8) or low
            if (value >= 0x8000) value - 0x10000 else value
        }
        val maxSample = allSamples.maxOrNull() ?: 0
        val minSample = allSamples.minOrNull() ?: 0
        val rms = kotlin.math.sqrt(allSamples.map { it.toLong() * it }.sum().toDouble() / decodedSamples)
        val peakToPeak = maxSample - minSample
        
        Log.d("OpusCodec", "[DIAG] decode: decodedSamples=$decodedSamples, pcm_hex=$samplesHex, pcm_shorts=$samplesShort")
        Log.d("OpusCodec", "[DIAG] PCM stats: min=$minSample, max=$maxSample, peakToPeak=$peakToPeak, rms=${"%.1f".format(rms)}")
        
        return pcmData
    }
    
    override fun getAudioRecordBufferSize(): Int {
        return audioRecordBufferCalculator.calculate(sampleRate, channels)
    }
    
    override fun release() {
        encoder = null
        decoder = null
    }
}

/**
 * AudioRecord 缓冲区大小计算器接口
 */
interface AudioRecordBufferCalculator {
    fun calculate(sampleRate: Int, channels: Int): Int
}

/**
 * 默认实现，使用 Android API
 */
class DefaultAudioRecordBufferCalculator : AudioRecordBufferCalculator {
    override fun calculate(sampleRate: Int, channels: Int): Int {
        return android.media.AudioRecord.getMinBufferSize(
            sampleRate,
            if (channels == 1) android.media.AudioFormat.CHANNEL_IN_MONO else android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
    }
}