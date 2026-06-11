package com.example.hiai.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TTS 音频播放器
 * 
 * 负责解码 Opus 音频数据并播放
 * - 接收 Opus 压缩数据
 * - 使用 OpusCodec 解码为 PCM
 * - 使用 AudioTrack 播放 PCM 数据
 */
class AudioPlayer(
    private val context: Context,
    private val opusCodec: OpusCodec,
    private val sampleRate: Int = 16000,  // 从服务端 Hello 响应动态获取
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "AudioPlayer"
        private const val CHANNELS = 1
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playJob: Job? = null
    private var isMuted = false
    private var audioFocusRequested = false
    private val focusLock = Any()

    /**
     * 将 Opus 数据加入播放队列
     * 
     * @param opusData Opus 压缩数据
     */
    fun enqueueAudioData(opusData: ByteArray) {
        Log.d(TAG, "enqueueAudioData: ${opusData.size} bytes, isPlaying=$isPlaying, queueSize=${audioQueue.size}")
        if (isMuted) {
            Log.w(TAG, "AudioPlayer is muted, dropping audio data")
            return
        }
        
        audioQueue.offer(opusData.copyOf())
        
        // 如果还没有开始播放，启动播放线程
        if (!isPlaying) {
            Log.d(TAG, "Starting playback thread")
            startPlaying()
        }
    }

    /**
     * 开始播放音频
     */
    private fun startPlaying() {
        if (isPlaying) {
            Log.w(TAG, "Already playing, ignoring startPlaying call")
            return
        }

        isPlaying = true
        playJob = scope.launch {
            Log.d(TAG, "Playback thread started")
            try {
                initializeAudioTrack()
                
                while (isPlaying) {
                    // 从队列中取出 Opus 数据
                    val opusData = audioQueue.poll()
                    
                    if (opusData != null) {
                        Log.d(TAG, "Processing Opus data: ${opusData.size} bytes, queue size: ${audioQueue.size}")
                        // 解码为 PCM
                        val pcmData = try {
                            val decoded = opusCodec.decode(opusData)
                            Log.d(TAG, "decode success: input size = ${opusData.size}, output pcm size = ${decoded.size}")
                            decoded
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decode Opus data, input size = ${opusData.size}", e)
                            continue
                        }
                        
                        if (pcmData.isNotEmpty()) {
                            // 播放 PCM 数据
                            val written = audioTrack?.write(pcmData, 0, pcmData.size)
                            Log.d(TAG, "Written to AudioTrack: $written bytes (expected: ${pcmData.size})")
                            if (written != null && written < 0) {
                                Log.e(TAG, "AudioTrack.write returned error code: $written")
                            }
                        } else {
                            Log.w(TAG, "Decoded PCM is empty")
                        }
                    } else {
                        // 队列为空，等待一下
                        delay(10)
                        
                        // 如果队列持续为空且没有更多数据，停止播放
                        if (audioQueue.isEmpty()) {
                            // 检查是否还有更多数据 coming
                            delay(100)
                            if (audioQueue.isEmpty()) {
                                Log.d(TAG, "Audio queue empty, stopping playback")
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error", e)
            } finally {
                stopPlaying()
            }
        }
    }

    /**
     * 初始化 AudioTrack
     * 
     * OPPO/ColorOS 兼容策略：
     * - 使用 AudioTrack.Builder() + PERFORMANCE_MODE_LOW_LATENCY 绕过 oplusAudioFade 系统
     * - 添加 FLAG_AUDIBILITY_ENFORCED 确保音频不被静音
     * - 内置测试音验证硬件播放能力
     */
    private fun initializeAudioTrack() {
        if (audioTrack != null) {
            Log.d(TAG, "AudioTrack already initialized, skipping")
            return
        }

        try {
            val channelConfig = if (CHANNELS == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize * 2, 8192)
            Log.d(TAG, "Creating AudioTrack: sr=$sampleRate, ch=$CHANNELS, buffer=$bufferSize, minBuff=$minBufferSize")

            // 关键：FLAG_AUDIBILITY_ENFORCED 告诉 OPPO HAL 不应用 fade 抑制
            val audioAttributesBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            // 使用 Builder 模式（非旧构造函数），OPPO 的 AudioTrackExtImpl 对 Builder 创建的 track 行为不同
            val audioTrackBuilder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributesBuilder.build())
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            // API 29+: 设置低延迟模式绕过 OPPO 音频后处理
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioTrackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                Log.d(TAG, "Performance mode: LOW_LATENCY")
            }

            audioTrack = audioTrackBuilder.build()

            val state = audioTrack?.state
            Log.d(TAG, "AudioTrack state: $state (INITIALIZED=${AudioTrack.STATE_INITIALIZED})")
            
            if (state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack init FAILED! state=$state")
                audioTrack?.release()
                audioTrack = null
                throw IllegalStateException("AudioTrack initialization failed")
            }

            // 先播放测试音验证硬件链路正常
            // NOTE: 测试音已验证通过，正常流程中不调用避免干扰 AudioTrack 状态
            // playTestTone()

            audioTrack?.play()
            val playState = audioTrack?.playState
            Log.d(TAG, "AudioTrack play() done, playState=$playState (PLAYING=${AudioTrack.PLAYSTATE_PLAYING})")

            audioTrack?.setVolume(1.0f)
            Log.d(TAG, "Volume set to 1.0")

            // 请求音频焦点
            requestAudioFocus()
            
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.d(TAG, "System music volume: $currentVolume/$maxVolume")
            
            Log.d(TAG, "AudioTrack initialized OK (sr=$sampleRate, buffer=$bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            throw e
        }
    }

    /**
     * 播放 440Hz 测试正弦波（200ms），验证 AudioTrack 硬件链路是否正常
     * 如果这个也没声音，说明问题在设备/系统层而非数据层
     */
    private fun playTestTone() {
        try {
            val track = audioTrack ?: return
            val durationMs = 200
            val numSamples = (sampleRate * durationMs / 1000)
            val testPcm = ShortArray(numSamples)
            val amplitude = 16384 // 50% of max (32767)
            
            for (i in 0 until numSamples) {
                val angle = 2.0 * Math.PI * 440.0 * i / sampleRate
                testPcm[i] = (Math.sin(angle) * amplitude).toInt().toShort()
            }
            
            // ShortArray → ByteArray (little-endian)
            val byteBuffer = ByteArray(testPcm.size * 2)
            for (i in testPcm.indices) {
                val sample = testPcm[i].toInt()
                byteBuffer[i * 2] = (sample and 0xFF).toByte()
                byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }

            // 先 play 再写数据
            track.play()
            val written = track.write(byteBuffer, 0, byteBuffer.size)
            Log.d(TAG, "Test tone (440Hz, ${durationMs}ms): wrote $written/${byteBuffer.size} bytes")
            
            // 等待测试音播完再开始真正内容
            Thread.sleep((durationMs + 50).toLong())
        } catch (e: Exception) {
            Log.w(TAG, "Test tone failed (non-fatal)", e)
        }
    }

    /**
     * 停止播放音频
     */
    fun stopPlaying() {
        Log.d(TAG, "stopPlaying called, isPlaying=$isPlaying, audioTrack state=${audioTrack?.state}, playState=${audioTrack?.playState}")
        isPlaying = false
        playJob?.cancel()
        playJob = null

        // 释放音频焦点
        abandonAudioFocus()

        // 先 pause 再 stop，确保音频缓冲区被正确刷新
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                // 刷新剩余数据
                track.flush()
                Log.d(TAG, "AudioTrack paused and flushed")
            }
            track.stop()
            track.release()
            Log.d(TAG, "AudioTrack stopped and released")
        }
        audioTrack = null
        
        audioQueue.clear()
        Log.d(TAG, "Audio playback stopped")
    }

    /**
     * 清空音频队列
     * 用于 VAD 打断时快速清空待播放的音频
     */
    fun clearQueue() {
        val queueSize = audioQueue.size
        audioQueue.clear()
        Log.d(TAG, "Audio queue cleared, removed $queueSize items")
    }

    /**
     * 请求音频焦点
     * 使用 GAIN 而非 GAIN_TRANSIENT_MAY_DUCK，避免被系统降级为静音（OPPO/OnePlus 设备上 duck 可能等于 mute）
     */
    private fun requestAudioFocus() {
        synchronized(focusLock) {
            if (audioFocusRequested) return
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "AudioFocus changed: $focusChange")
                        // 即使失去焦点也继续播放（TTS 不应被中断）
                    }
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
            audioFocusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "AudioFocus requested (GAIN): granted=$audioFocusRequested, result=$result")
        }
    }

    /**
     * 释放音频焦点
     */
    private fun abandonAudioFocus() {
        synchronized(focusLock) {
            if (!audioFocusRequested) return
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.abandonAudioFocusRequest(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioFocusRequested = false
            Log.d(TAG, "AudioFocus abandoned")
        }
    }

    /**
     * 静音/取消静音
     */
    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            audioQueue.clear()
        }
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean = isPlaying

    /**
     * 释放资源
     */
    fun release() {
        stopPlaying()
        scope.cancel()
    }
}
