package com.example.hiai.service.manager

import android.util.Log
import com.example.hiai.domain.contract.IServiceStateManager
import com.example.hiai.domain.model.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 服务状态管理器
 *
 * 实现 IServiceStateManager 接口
 */
class ServiceStateManager : IServiceStateManager {

    companion object {
        private const val TAG = "ServiceStateManager"
    }

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)

    override val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    override val currentState: ServiceState
        get() = _serviceState.value

    override suspend fun updateState(state: ServiceState) {
        Log.d(TAG, "Updating state: ${_serviceState.value} -> $state")
        _serviceState.value = state
    }

    override suspend fun setListening(isListening: Boolean) {
        val currentState = _serviceState.value
        if (currentState is ServiceState.Running) {
            Log.d(TAG, "Setting listening: $isListening")
            _serviceState.value = ServiceState.Running(isListening)
        }
    }

    override suspend fun reset() {
        Log.d(TAG, "Resetting state")
        _serviceState.value = ServiceState.Stopped
    }
}
