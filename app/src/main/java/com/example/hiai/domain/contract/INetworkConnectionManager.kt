package com.example.hiai.domain.contract

import com.example.hiai.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow

/**
 * 网络连接管理接口
 */
interface INetworkConnectionManager {
    /**
     * 连接状态流
     */
    val connectionState: Flow<ConnectionState>

    /**
     * 当前连接状态
     */
    val currentState: ConnectionState

    /**
     * 连接到服务器
     * @param serverAddress 服务器地址
     */
    suspend fun connect(serverAddress: String): Result<Unit>

    /**
     * 断开连接
     */
    suspend fun disconnect()

    /**
     * 发送音频数据
     * @param audioData 音频数据
     */
    suspend fun sendAudio(audioData: ByteArray): Result<Unit>

    /**
     * 接收音频数据流
     */
    fun receiveAudio(): Flow<ByteArray>
}
