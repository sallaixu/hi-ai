package com.example.hiai.domain.contract

import com.example.hiai.domain.model.ServiceState
import kotlinx.coroutines.flow.Flow

/**
 * 服务状态管理接口
 */
interface IServiceStateManager {
    /**
     * 服务状态流
     */
    val serviceState: Flow<ServiceState>

    /**
     * 当前状态
     */
    val currentState: ServiceState

    /**
     * 更新服务状态
     * @param state 新状态
     */
    suspend fun updateState(state: ServiceState)

    /**
     * 设置监听状态
     * @param isListening 是否正在监听
     */
    suspend fun setListening(isListening: Boolean)

    /**
     * 重置状态
     */
    suspend fun reset()
}
