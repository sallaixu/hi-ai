package com.example.hiai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.hiai.MainActivity
import com.example.hiai.R
import com.example.hiai.domain.contract.IAudioSessionManager
import com.example.hiai.domain.contract.INetworkConnectionManager
import com.example.hiai.domain.contract.IServiceStateManager
import com.example.hiai.domain.contract.IWakeWordManager
import com.example.hiai.domain.model.AudioSessionState
import com.example.hiai.domain.model.ConnectionState
import com.example.hiai.domain.model.ServiceState
import com.example.hiai.domain.model.WakeWordState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 语音助手服务协调者
 *
 * 职责：
 * - 协调各个 Manager 的工作
 * - 管理服务生命周期
 * - 提供前台服务通知
 *
 * 注意：这是重构后的新版本，逐步替换原 VoiceAssistantService
 */
class VoiceAssistantServiceCoordinator : LifecycleService() {

    companion object {
        private const val TAG = "VoiceAssistantServiceCoordinator"
        private const val NOTIFICATION_CHANNEL_ID = "voice_assistant_channel"
        private const val NOTIFICATION_ID = 1

        // Intent Actions
        const val ACTION_START = "com.example.hiai.action.START"
        const val ACTION_STOP = "com.example.hiai.action.STOP"
        const val ACTION_CONNECT = "com.example.hiai.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.hiai.action.DISCONNECT"
    }

    // 依赖注入
    private val networkManager: INetworkConnectionManager by inject()
    private val audioManager: IAudioSessionManager by inject()
    private val wakeWordManager: IWakeWordManager by inject()
    private val stateManager: IServiceStateManager by inject()

    // Binder for client binding
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantServiceCoordinator = this@VoiceAssistantServiceCoordinator
    }

    // Jobs
    private var connectionJob: Job? = null
    private var audioJob: Job? = null
    private var wakeWordJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        observeStates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
            ACTION_CONNECT -> {
                val serverAddress = intent.getStringExtra("server_address") ?: ""
                connectToServer(serverAddress)
            }
            ACTION_DISCONNECT -> disconnectFromServer()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        cleanup()
        super.onDestroy()
    }

    /**
     * 启动服务
     */
    private fun startService() {
        Log.d(TAG, "Starting service")

        lifecycleScope.launch {
            stateManager.updateState(ServiceState.Starting)
        }

        // 启动前台服务
        val notification = createNotification("语音助手运行中")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )

        lifecycleScope.launch {
            stateManager.updateState(ServiceState.Running(false))
        }
    }

    /**
     * 停止服务
     */
    private fun stopService() {
        Log.d(TAG, "Stopping service")

        lifecycleScope.launch {
            stateManager.updateState(ServiceState.Stopping)
            cleanup()
            stateManager.updateState(ServiceState.Stopped)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 连接到服务器
     */
    private fun connectToServer(serverAddress: String) {
        Log.d(TAG, "Connecting to server: $serverAddress")

        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            networkManager.connect(serverAddress)
                .onSuccess {
                    Log.d(TAG, "Connected successfully")
                }
                .onFailure { error ->
                    Log.e(TAG, "Connection failed", error)
                }
        }
    }

    /**
     * 断开服务器连接
     */
    private fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")

        lifecycleScope.launch {
            networkManager.disconnect()
        }
    }

    /**
     * 观察各 Manager 的状态
     */
    private fun observeStates() {
        // 观察网络连接状态
        lifecycleScope.launch {
            networkManager.connectionState.collectLatest { state ->
                Log.d(TAG, "Connection state: $state")
                when (state) {
                    is ConnectionState.Connected -> {
                        // 连接成功，开始音频处理
                        startAudioProcessing()
                    }
                    is ConnectionState.Disconnected -> {
                        // 断开连接，停止音频处理
                        stopAudioProcessing()
                    }
                    else -> {}
                }
            }
        }

        // 观察音频会话状态
        lifecycleScope.launch {
            audioManager.sessionState.collectLatest { state ->
                Log.d(TAG, "Audio session state: $state")
                when (state) {
                    is AudioSessionState.Recording -> {
                        lifecycleScope.launch {
                            stateManager.setListening(true)
                        }
                    }
                    else -> {
                        lifecycleScope.launch {
                            stateManager.setListening(false)
                        }
                    }
                }
            }
        }

        // 观察唤醒词状态
        lifecycleScope.launch {
            wakeWordManager.wakeWordState.collectLatest { state ->
                Log.d(TAG, "Wake word state: $state")
                when (state) {
                    is WakeWordState.Detected -> {
                        // 检测到唤醒词，开始录音
                        lifecycleScope.launch {
                            audioManager.startRecording()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 开始音频处理
     */
    private fun startAudioProcessing() {
        Log.d(TAG, "Starting audio processing")

        audioJob?.cancel()
        audioJob = lifecycleScope.launch {
            // 初始化唤醒词检测
            wakeWordManager.initialize()
                .onSuccess {
                    wakeWordManager.startDetection()
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to initialize wake word detection", error)
                }
        }
    }

    /**
     * 停止音频处理
     */
    private fun stopAudioProcessing() {
        Log.d(TAG, "Stopping audio processing")

        audioJob?.cancel()
        audioJob = null

        lifecycleScope.launch {
            wakeWordManager.stopDetection()
            audioManager.stopRecording()
        }
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        connectionJob?.cancel()
        audioJob?.cancel()
        wakeWordJob?.cancel()

        lifecycleScope.launch {
            networkManager.disconnect()
            wakeWordManager.release()
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "语音助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "语音助手服务通知"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("语音助手")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }
}
