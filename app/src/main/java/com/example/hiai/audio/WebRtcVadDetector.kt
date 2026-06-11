package com.example.hiai.audio

import android.util.Log

/**
 * WebRTC VAD 检测器
 *
 * 封装 WebRTC 的 Voice Activity Detection 功能
 */
class WebRtcVadDetector(
    private val mode: VadMode = VadMode.LOW_BITRATE,
    private val sampleRate: Int = 16000
) {
    /**
     * VAD 模式
     */
    enum class VadMode(val value: Int) {
        QUALITY(0),      // 高质量（低灵敏度）
        LOW_BITRATE(1),  // 低比特率（中灵敏度）
        AGGRESSIVE(2)    // 激进（高灵敏度）
    }

    private var vadHandle: Long = 0
    private var isInitialized = false

    companion object {
        private const val TAG = "WebRtcVadDetector"
    }

    /**
     * 初始化 VAD
     *
     * @return true 表示初始化成功，false 表示失败
     */
    fun init(): Boolean {
        if (isInitialized) {
            Log.w(TAG, "VAD already initialized")
            return true
        }

        try {
            vadHandle = WebRtcVadJni.create(mode.value)
            if (vadHandle == 0L) {
                Log.e(TAG, "Failed to create VAD instance")
                return false
            }

            isInitialized = true
            Log.d(TAG, "VAD initialized with mode: $mode, handle: $vadHandle")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VAD", e)
            return false
        }
    }

    /**
     * 检测音频帧是否包含语音
     *
     * @param pcmData PCM 音频数据（Short 数组）
     * @return true 表示检测到语音，false 表示静音
     */
    fun detect(pcmData: ShortArray): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "VAD not initialized")
            return false
        }

        if (vadHandle == 0L) {
            Log.w(TAG, "VAD handle is null")
            return false
        }

        return try {
            WebRtcVadJni.detect(vadHandle, pcmData, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "VAD detect failed", e)
            false
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (isInitialized && vadHandle != 0L) {
            try {
                WebRtcVadJni.destroy(vadHandle)
                Log.d(TAG, "VAD released, handle: $vadHandle")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release VAD", e)
            }
            vadHandle = 0
            isInitialized = false
        }
    }

    /**
     * 检查 VAD 是否已初始化
     */
    fun isInitialized(): Boolean = isInitialized
}
