package com.example.hiai.service.manager

import android.util.Log
import com.example.hiai.domain.contract.INetworkConnectionManager
import com.example.hiai.domain.model.ConnectionState
import com.example.hiai.network.NetworkManager
import com.example.hiai.network.model.TtsAudioData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * 网络连接管理器
 *
 * 实现 INetworkConnectionManager 接口，包装 NetworkManager
 */
class NetworkConnectionManager(
    private val networkManager: NetworkManager
) : INetworkConnectionManager {

    companion object {
        private const val TAG = "NetworkConnectionManager"
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val currentState: ConnectionState
        get() = _connectionState.value

    override suspend fun connect(serverAddress: String): Result<Unit> {
        return try {
            Log.d(TAG, "Connecting to: $serverAddress")
            _connectionState.value = ConnectionState.Connecting

            // 获取 OTA 信息
            val otaResponse = networkManager.fetchOtaInfo()
            if (otaResponse == null) {
                _connectionState.value = ConnectionState.Failed("Failed to fetch OTA info")
                return Result.failure(Exception("Failed to fetch OTA info"))
            }

            // 连接 WebSocket
            networkManager.connect(otaResponse.websocket.url, "")
            _connectionState.value = ConnectionState.Connected(serverAddress)

            Log.d(TAG, "Connected successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        Log.d(TAG, "Disconnecting")
        networkManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun sendAudio(audioData: ByteArray): Result<Unit> {
        return try {
            networkManager.sendAudio(audioData)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio", e)
            Result.failure(e)
        }
    }

    override fun receiveAudio(): Flow<ByteArray> {
        // 从 messageFlow 中过滤 TtsAudioData 并提取音频数据
        return networkManager.messageFlow
            .filterIsInstance<TtsAudioData>()
            .map { it.opusData }
    }
}
