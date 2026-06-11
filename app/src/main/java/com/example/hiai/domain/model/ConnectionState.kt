package com.example.hiai.domain.model

/**
 * 连接状态
 */
sealed class ConnectionState {
    /** 未连接 */
    data object Disconnected : ConnectionState()

    /** 连接中 */
    data object Connecting : ConnectionState()

    /** 已连接 */
    data class Connected(val serverAddress: String) : ConnectionState()

    /** 连接失败 */
    data class Failed(val error: String) : ConnectionState()
}
