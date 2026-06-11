package com.example.hiai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hiai.domain.contract.IAudioSessionManager
import com.example.hiai.domain.contract.INetworkConnectionManager
import com.example.hiai.domain.contract.IServiceStateManager
import com.example.hiai.domain.contract.IWakeWordManager
import com.example.hiai.domain.model.AudioSessionState
import com.example.hiai.domain.model.ConnectionState
import com.example.hiai.domain.model.ServiceState
import com.example.hiai.domain.model.WakeWordState
import com.example.hiai.service.manager.ServiceConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 语音助手 ViewModel（重构版）
 *
 * 职责：
 * - 管理 UI 状态
 * - 观察各 Manager 状态
 * - 提供用户操作接口
 */
class VoiceAssistantViewModelRefactored(
    private val networkManager: INetworkConnectionManager,
    private val audioManager: IAudioSessionManager,
    private val wakeWordManager: IWakeWordManager,
    private val stateManager: IServiceStateManager,
    private val serviceConnectionManager: ServiceConnectionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        observeStates()
    }

    /**
     * 观察各 Manager 的状态
     */
    private fun observeStates() {
        // 观察服务状态
        viewModelScope.launch {
            stateManager.serviceState.collectLatest { state ->
                _uiState.value = _uiState.value.copy(serviceState = state)
            }
        }

        // 观察网络连接状态
        viewModelScope.launch {
            networkManager.connectionState.collectLatest { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }

        // 观察音频会话状态
        viewModelScope.launch {
            audioManager.sessionState.collectLatest { state ->
                _uiState.value = _uiState.value.copy(audioSessionState = state)
            }
        }

        // 观察唤醒词状态
        viewModelScope.launch {
            wakeWordManager.wakeWordState.collectLatest { state ->
                _uiState.value = _uiState.value.copy(wakeWordState = state)
            }
        }
    }

    /**
     * 启动服务
     */
    fun startService() {
        serviceConnectionManager.startService()
    }

    /**
     * 停止服务
     */
    fun stopService() {
        serviceConnectionManager.stopService()
    }

    /**
     * 连接到服务器
     */
    fun connectToServer(serverAddress: String) {
        serviceConnectionManager.connectToServer(serverAddress)
    }

    /**
     * 断开服务器连接
     */
    fun disconnectFromServer() {
        serviceConnectionManager.disconnectFromServer()
    }

    /**
     * 切换桌面模式
     */
    fun toggleDeskMode() {
        val currentMode = _uiState.value.isDeskMode
        _uiState.value = _uiState.value.copy(isDeskMode = !currentMode)
    }

    /**
     * 更新服务器地址
     */
    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url)
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * UI 状态
     */
    data class UiState(
        val serviceState: ServiceState = ServiceState.Stopped,
        val connectionState: ConnectionState = ConnectionState.Disconnected,
        val audioSessionState: AudioSessionState = AudioSessionState.Idle,
        val wakeWordState: WakeWordState = WakeWordState.Uninitialized,
        val isDeskMode: Boolean = false,
        val serverUrl: String = "",
        val errorMessage: String? = null
    ) {
        /**
         * 是否正在连接
         */
        val isConnecting: Boolean
            get() = connectionState is ConnectionState.Connecting

        /**
         * 是否已连接
         */
        val isConnected: Boolean
            get() = connectionState is ConnectionState.Connected

        /**
         * 是否正在录音
         */
        val isRecording: Boolean
            get() = audioSessionState is AudioSessionState.Recording

        /**
         * 是否正在播放
         */
        val isPlaying: Boolean
            get() = audioSessionState is AudioSessionState.Playing

        /**
         * 服务是否运行中
         */
        val isServiceRunning: Boolean
            get() = serviceState is ServiceState.Running
    }
}
