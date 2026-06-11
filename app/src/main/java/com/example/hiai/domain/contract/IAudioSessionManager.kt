package com.example.hiai.domain.contract

import com.example.hiai.domain.model.AudioSessionState
import kotlinx.coroutines.flow.Flow

/**
 * 音频会话管理接口
 */
interface IAudioSessionManager {
    /**
     * 音频会话状态流
     */
    val sessionState: Flow<AudioSessionState>

    /**
     * 当前会话状态
     */
    val currentState: AudioSessionState

    /**
     * 开始录音
     */
    suspend fun startRecording(): Result<Unit>

    /**
     * 停止录音
     */
    suspend fun stopRecording(): Result<ByteArray>

    /**
     * 播放音频
     * @param audioData 音频数据
     */
    suspend fun playAudio(audioData: ByteArray): Result<Unit>

    /**
     * 停止播放
     */
    suspend fun stopPlayback()

    /**
     * 音频数据流（录音输出）
     */
    fun audioStream(): Flow<ByteArray>
}
