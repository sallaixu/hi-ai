package com.example.hiai.audio

/**
 * WebRTC VAD JNI 接口
 *
 * 提供 JNI 方法的声明，实际实现在 libwebrtc_vad.so 中
 */
object WebRtcVadJni {

    init {
        try {
            System.loadLibrary("webrtc_vad")
        } catch (e: UnsatisfiedLinkError) {
            throw RuntimeException("Failed to load webrtc_vad library", e)
        }
    }

    /**
     * 创建 VAD 实例
     *
     * @param mode VAD 模式（0=Quality, 1=LowBitrate, 2=Aggressive）
     * @return VAD 实例句柄，失败返回 0
     */
    external fun create(mode: Int): Long

    /**
     * 检测音频帧是否包含语音
     *
     * @param handle VAD 实例句柄
     * @param data PCM 音频数据（Short 数组）
     * @param sampleRate 采样率（8000, 16000, 32000, 48000）
     * @return true 表示检测到语音，false 表示静音
     */
    external fun detect(handle: Long, data: ShortArray, sampleRate: Int): Boolean

    /**
     * 销毁 VAD 实例
     *
     * @param handle VAD 实例句柄
     */
    external fun destroy(handle: Long)
}
