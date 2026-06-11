package com.example.hiai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 音频处理器
 * 
 * 负责录音和播放：
 * - 录音：使用 AudioRecord 采集麦克风音频
 * - 播放：使用 AudioTrack 播放扬声器音频
 */
class AudioProcessor(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val audioBits: Int = 16
) {
    
    private val channelConfig = if (channels == 1) {
        AudioFormat.CHANNEL_IN_MONO
    } else {
        AudioFormat.CHANNEL_IN_STEREO
    }
    
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isRecording = false
    private var isPlaying = false
    
    /**
     * 开始录音
     * 
     * @param requestedBufferSize 可选：请求的缓冲区字节数（例如，为了匹配编码器要求的特定帧大小）
     * @return Flow<ByteArray> 音频数据流
     */
    fun startRecording(requestedBufferSize: Int = -1): Flow<ByteArray> {
        val channel = Channel<ByteArray>(Channel.BUFFERED)
        
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (requestedBufferSize > 0) {
            // 确保不低于系统推荐的最小 BufferSize
            maxOf(minBufferSize, requestedBufferSize)
        } else {
            minBufferSize
        }
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2 // 双倍缓冲区
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }
        
        audioRecord?.startRecording()
        isRecording = true
        
        recordingScope.launch {
            try {
                // 如果指定了请求的包大小，每次读取就使用该大小的 buffer
                val readBufferSize = if (requestedBufferSize > 0) requestedBufferSize else bufferSize
                val buffer = ByteArray(readBufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        channel.send(buffer.copyOf(read))
                    }
                }
            } catch (e: Exception) {
                // 录音过程中出错或取消
            } finally {
                channel.close()
            }
        }
        
        return channel.receiveAsFlow()
    }
    
    /**
     * 停止录音
     */
    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
    
    /**
     * 开始播放
     * 
     * @param audioFlow 音频数据流
     */
    fun startPlaying(audioFlow: Flow<ByteArray>) {
        isPlaying = true
        
        playbackScope.launch {
            try {
                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                audioTrack = AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize * 2, // 双倍缓冲区
                    AudioTrack.MODE_STREAM
                )
                
                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack initialization failed")
                }
                
                audioTrack?.play()
                
                audioFlow.collect { data ->
                    if (isPlaying) {
                        audioTrack?.write(data, 0, data.size)
                    }
                }
            } catch (e: Exception) {
                // 播放过程中出错或取消
            }
        }
    }
    
    /**
     * 停止播放
     */
    fun stopPlaying() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
    
    /**
     * 释放所有资源
     */
    fun release() {
        if (isRecording) {
            stopRecording()
        }
        if (isPlaying) {
            stopPlaying()
        }
        recordingScope.cancel()
        playbackScope.cancel()
    }
}
