package com.example.hiai.service.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.hiai.service.VoiceAssistantServiceCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 服务连接管理器
 *
 * 负责 Activity 和 Service 之间的连接管理
 */
class ServiceConnectionManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "ServiceConnectionManager"
    }

    /**
     * 服务连接状态
     */
    sealed class ConnectionStatus {
        data object Disconnected : ConnectionStatus()
        data object Connecting : ConnectionStatus()
        data class Connected(val service: VoiceAssistantServiceCoordinator) : ConnectionStatus()
    }

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private var service: VoiceAssistantServiceCoordinator? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Service connected: $name")
            val localBinder = binder as? VoiceAssistantServiceCoordinator.LocalBinder
            service = localBinder?.getService()
            if (service != null) {
                _connectionStatus.value = ConnectionStatus.Connected(service!!)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected: $name")
            service = null
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    /**
     * 绑定服务
     */
    fun bindService(): Boolean {
        Log.d(TAG, "Binding service")

        if (_connectionStatus.value is ConnectionStatus.Connected) {
            Log.d(TAG, "Service already connected")
            return true
        }

        _connectionStatus.value = ConnectionStatus.Connecting

        val intent = Intent(context, VoiceAssistantServiceCoordinator::class.java)
        val result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        if (!result) {
            Log.e(TAG, "Failed to bind service")
            _connectionStatus.value = ConnectionStatus.Disconnected
        }

        return result
    }

    /**
     * 解绑服务
     */
    fun unbindService() {
        Log.d(TAG, "Unbinding service")

        if (_connectionStatus.value is ConnectionStatus.Connected) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind service", e)
            }
        }

        service = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    /**
     * 启动服务
     */
    fun startService() {
        Log.d(TAG, "Starting service")

        val intent = Intent(context, VoiceAssistantServiceCoordinator::class.java).apply {
            action = VoiceAssistantServiceCoordinator.ACTION_START
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * 停止服务
     */
    fun stopService() {
        Log.d(TAG, "Stopping service")

        val intent = Intent(context, VoiceAssistantServiceCoordinator::class.java).apply {
            action = VoiceAssistantServiceCoordinator.ACTION_STOP
        }

        context.startService(intent)
        unbindService()
    }

    /**
     * 连接到服务器
     */
    fun connectToServer(serverAddress: String) {
        Log.d(TAG, "Connecting to server: $serverAddress")

        val intent = Intent(context, VoiceAssistantServiceCoordinator::class.java).apply {
            action = VoiceAssistantServiceCoordinator.ACTION_CONNECT
            putExtra("server_address", serverAddress)
        }

        context.startService(intent)
    }

    /**
     * 断开服务器连接
     */
    fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")

        val intent = Intent(context, VoiceAssistantServiceCoordinator::class.java).apply {
            action = VoiceAssistantServiceCoordinator.ACTION_DISCONNECT
        }

        context.startService(intent)
    }

    /**
     * 获取服务实例
     */
    fun getService(): VoiceAssistantServiceCoordinator? = service
}
