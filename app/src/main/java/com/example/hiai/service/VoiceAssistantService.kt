package com.example.hiai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.hiai.MainActivity
import com.example.hiai.R
import com.example.hiai.audio.AudioProcessor
import com.example.hiai.audio.OpusCodec
import com.example.hiai.audio.WakeWordDetector
import com.example.hiai.data.AppDatabase
import com.example.hiai.data.repository.ChatHistoryRepository
import com.example.hiai.data.repository.SettingRepository
import com.example.hiai.network.NetworkManager
import com.example.hiai.network.model.HelloResponse
import com.example.hiai.network.model.SttMessage
import com.example.hiai.network.model.TtsMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音助手前台服务
 * 
 * 核心功能：
 * - 24 小时后台常驻（前台服务 + 通知栏）
 * - 语音唤醒检测
 * - 与服务端 WebSocket 通信
 * - 音频编解码和播放
 */
class VoiceAssistantService : Service() {
    
    // 服务状态
    sealed class ServiceState {
        object Idle : ServiceState()
        object Listening : ServiceState()
        object Speaking : ServiceState()
        object Error : ServiceState()
    }
    
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    
    private val _isDeskMode = MutableStateFlow(false)
    val isDeskMode: StateFlow<Boolean> = _isDeskMode.asStateFlow()
    
    // 组件
    private lateinit var networkManager: NetworkManager
    private lateinit var audioProcessor: AudioProcessor
    private lateinit var opusCodec: OpusCodecInterface
    private lateinit var settingRepository: SettingRepository
    private lateinit var chatHistoryRepository: ChatHistoryRepository
    
    // 唤醒词检测器
    private var wakeWordDetector: WakeWordDetector? = null
    
    // 协程
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Binder
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化数据库
        val database = AppDatabase.getDatabase(this)
        settingRepository = SettingRepository(database.settingDao())
        chatHistoryRepository = ChatHistoryRepository(database.chatHistoryDao())
        
        // 初始化网络管理器
        val baseUrl = settingRepository.get("server_url") ?: "http://localhost:8000"
        val deviceId = settingRepository.get("device_id") ?: getDeviceId()
        networkManager = NetworkManager(
            baseUrl = baseUrl,
            deviceId = deviceId,
            deviceName = "Android Assistant",
            token = settingRepository.get("token") ?: ""
        )
        
        // 初始化音频组件
        opusCodec = OpusCodec()
        audioProcessor = AudioProcessor()
        
        // 初始化唤醒词检测器
        wakeWordDetector = WakeWordDetector(this) { keyword ->
            onWakeWordDetected(keyword)
        }
        wakeWordDetector?.init()
        
        // 创建通知渠道
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopService()
            ACTION_TOGGLE_DESK_MODE -> toggleDeskMode()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        
        // 释放唤醒词检测器
        wakeWordDetector?.release()
        wakeWordDetector = null
        
        audioProcessor.release()
        opusCodec.release()
        networkManager.disconnect()
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // 连接服务端
        connectToServer()
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 语音助手")
            .setContentText("正在运行...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音助手服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持语音助手后台运行"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 连接服务端
     */
    private fun connectToServer() {
        serviceScope.launch {
            // 获取 OTA 信息
            val otaResponse = networkManager.fetchOtaInfo()
            if (otaResponse != null) {
                // 连接 WebSocket
                networkManager.connect(otaResponse.websocket.url, otaResponse.websocket.token)
                
                // 监听消息
                networkManager.messageFlow.collect { message ->
                    when (message) {
                        is HelloResponse -> handleHelloResponse(message)
                        is SttMessage -> handleSttMessage(message)
                        is TtsMessage -> handleTtsMessage(message)
                        // 其他消息类型...
                    }
                }
            } else {
                _serviceState.value = ServiceState.Error
            }
        }
    }
    
    /**
     * 处理 Hello 响应
     */
    private fun handleHelloResponse(response: HelloResponse) {
        // 保存会话 ID
        serviceScope.launch {
            settingRepository.set("session_id", response.sessionId)
        }
        
        // 发送聆听状态（唤醒检测）
        networkManager.sendListen(state = "detect")
        
        _serviceState.value = ServiceState.Idle
    }
    
    /**
     * 处理 STT 消息
     */
    private fun handleSttMessage(message: SttMessage) {
        // 保存聊天记录
        serviceScope.launch {
            val sessionId = settingRepository.get("session_id") ?: ""
            chatHistoryRepository.addMessage(sessionId, "user", message.text)
        }
        
        // TODO: 触发对话处理
    }
    
    /**
     * 处理 TTS 消息
     */
    private fun handleTtsMessage(message: TtsMessage) {
        when (message.state) {
            "start" -> {
                _serviceState.value = ServiceState.Speaking
            }
            "stop" -> {
                _serviceState.value = ServiceState.Idle
            }
        }
    }
    
    /**
     * 切换桌面模式
     */
    private fun toggleDeskMode() {
        val newMode = !_isDeskMode.value
        _isDeskMode.value = newMode
        
        if (newMode) {
            // 进入桌面模式，启动唤醒词检测
            wakeWordDetector?.start()
            Log.i(TAG, "Desktop mode enabled, wake word detection started")
        } else {
            // 退出桌面模式，停止唤醒词检测
            wakeWordDetector?.stop()
            Log.i(TAG, "Desktop mode disabled, wake word detection stopped")
        }
    }
    
    /**
     * 唤醒词触发回调
     */
    private fun onWakeWordDetected(keyword: String) {
        Log.i(TAG, "Wake word detected: $keyword")
        
        // 发送 listen 消息，开始录音上传
        networkManager.sendListen(state = "start", text = keyword)
        
        // 进入 LISTENING 状态
        _serviceState.value = ServiceState.Listening
        
        // 开始录音并上传音频流
        startRecordingAndUpload()
    }
    
    /**
     * 开始录音并上传音频流
     */
    private fun startRecordingAndUpload() {
        serviceScope.launch {
            audioProcessor.startRecording().collect { pcmData ->
                // 编码为 Opus
                val opusData = opusCodec.encode(pcmData)
                // 发送到服务端
                networkManager.sendAudio(opusData)
            }
        }
    }
    
    /**
     * 停止服务
     */
    private fun stopService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    /**
     * 获取设备 ID
     */
    private fun getDeviceId(): String {
        return Build.SERIAL.ifBlank { "unknown" }
    }
    
    companion object {
        const val ACTION_START = "com.example.hiai.START_SERVICE"
        const val ACTION_STOP = "com.example.hiai.STOP_SERVICE"
        const val ACTION_TOGGLE_DESK_MODE = "com.example.hiai.TOGGLE_DESK_MODE"
        
        const val CHANNEL_ID = "voice_assistant_channel"
        const val NOTIFICATION_ID = 1001
        
        private const val TAG = "VoiceAssistantService"
    }
}
