package com.example.hiai.domain.model

/**
 * 音频会话状态
 */
sealed class AudioSessionState {
    /** 空闲 */
    data object Idle : AudioSessionState()

    /** 正在录音 */
    data object Recording : AudioSessionState()

    /** 正在播放 */
    data class Playing(val text: String) : AudioSessionState()

    /** 处理中 */
    data object Processing : AudioSessionState()
}
