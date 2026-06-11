package com.example.hiai.domain.contract

import com.example.hiai.domain.model.WakeWordState
import kotlinx.coroutines.flow.Flow

/**
 * 唤醒词检测接口
 */
interface IWakeWordManager {
    /**
     * 唤醒词状态流
     */
    val wakeWordState: Flow<WakeWordState>

    /**
     * 当前状态
     */
    val currentState: WakeWordState

    /**
     * 初始化唤醒词检测
     */
    suspend fun initialize(): Result<Unit>

    /**
     * 开始检测
     */
    suspend fun startDetection(): Result<Unit>

    /**
     * 停止检测
     */
    suspend fun stopDetection()

    /**
     * 处理音频数据
     * @param audioData 音频数据
     * @return 是否检测到唤醒词
     */
    fun processAudio(audioData: FloatArray): Boolean

    /**
     * 释放资源
     */
    fun release()
}
