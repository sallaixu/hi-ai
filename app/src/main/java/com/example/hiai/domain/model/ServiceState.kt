package com.example.hiai.domain.model

/**
 * 服务状态
 */
sealed class ServiceState {
    /** 已停止 */
    data object Stopped : ServiceState()

    /** 启动中 */
    data object Starting : ServiceState()

    /** 运行中 */
    data class Running(val isListening: Boolean = false) : ServiceState()

    /** 停止中 */
    data object Stopping : ServiceState()
}
