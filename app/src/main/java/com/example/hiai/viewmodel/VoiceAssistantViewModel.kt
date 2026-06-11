package com.example.hiai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hiai.service.VoiceAssistantService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音助手 ViewModel
 * 
 * 管理 UI 状态和业务逻辑
 */
class VoiceAssistantViewModel(
    private val service: VoiceAssistantService? = null
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    init {
        // 观察服务状态
        service?.let { svc ->
            viewModelScope.launch {
                svc.serviceState.collect { serviceState ->
                    _uiState.value = _uiState.value.copy(
                        serviceState = serviceState
                    )
                }
            }
            
            viewModelScope.launch {
                svc.isDeskMode.collect { isDeskMode ->
                    _uiState.value = _uiState.value.copy(
                        isDeskMode = isDeskMode
                    )
                }
            }
        }
    }
    
    /**
     * 切换桌面模式
     */
    fun toggleDeskMode() {
        // 通过服务广播发送指令
        // 实际实现在 MainActivity 中
    }
    
    /**
     * 更新服务器地址
     */
    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            // TODO: 保存到数据库
        }
    }
    
    data class UiState(
        val serviceState: VoiceAssistantService.ServiceState = VoiceAssistantService.ServiceState.Idle,
        val isDeskMode: Boolean = false,
        val errorMessage: String? = null
    )
}
