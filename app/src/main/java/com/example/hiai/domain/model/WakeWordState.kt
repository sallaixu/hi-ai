package com.example.hiai.domain.model

/**
 * 唤醒词状态
 */
sealed class WakeWordState {
    /** 未初始化 */
    data object Uninitialized : WakeWordState()

    /** 初始化中 */
    data object Initializing : WakeWordState()

    /** 就绪（等待唤醒） */
    data object Ready : WakeWordState()

    /** 已检测到唤醒词 */
    data object Detected : WakeWordState()

    /** 错误 */
    data class Error(val message: String) : WakeWordState()
}
