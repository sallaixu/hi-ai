package com.example.hiai.service.manager

import android.util.Log
import com.example.hiai.audio.AudioProcessor
import com.example.hiai.domain.contract.IAudioSessionManager
import com.example.hiai.domain.model.AudioSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * 音频会话管理器
 *
 * 实现 IAudioSessionManager 接口，包装 AudioProcessor
 */
class AudioSessionManager(
    private val audioProcessor: AudioProcessor
) : IAudioSessionManager {

    companion object {
        private const val TAG = "AudioSessionManager"
    }

    private val _sessionState = MutableStateFlow<AudioSessionState>(AudioSessionState.Idle)

    override val sessionState: StateFlow<AudioSessionState> = _sessionState.asStateFlow()

    override val currentState: AudioSessionState
        get() = _sessionState.value

    private var currentAudioStream: Flow<ByteArray>? = null

    override suspend fun startRecording(): Result<Unit> {
        return try {
            Log.d(TAG, "Starting recording")
            _sessionState.value = AudioSessionState.Recording

            currentAudioStream = audioProcessor.startRecording()
            Log.d(TAG, "Recording started successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _sessionState.value = AudioSessionState.Idle
            Result.failure(e)
        }
    }

    override suspend fun stopRecording(): Result<ByteArray> {
        return try {
            Log.d(TAG, "Stopping recording")
            audioProcessor.stopRecording()
            _sessionState.value = AudioSessionState.Idle
            currentAudioStream = null

            // 返回空数组，实际音频数据通过 audioStream() 获取
            Result.success(ByteArray(0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            Result.failure(e)
        }
    }

    override suspend fun playAudio(audioData: ByteArray): Result<Unit> {
        return try {
            Log.d(TAG, "Playing audio: ${audioData.size} bytes")
            _sessionState.value = AudioSessionState.Playing("")

            // 使用 startPlaying 播放音频流
            audioProcessor.startPlaying(kotlinx.coroutines.flow.flowOf(audioData))
            _sessionState.value = AudioSessionState.Idle

            Log.d(TAG, "Audio playback completed")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            _sessionState.value = AudioSessionState.Idle
            Result.failure(e)
        }
    }

    override suspend fun stopPlayback() {
        Log.d(TAG, "Stopping playback")
        audioProcessor.stopPlaying()
        _sessionState.value = AudioSessionState.Idle
    }

    override fun audioStream(): Flow<ByteArray> {
        return currentAudioStream ?: audioProcessor.startRecording()
    }
}
