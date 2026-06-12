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
import com.example.hiai.audio.AudioPlayer
import com.example.hiai.audio.OpusCodec
import com.example.hiai.audio.OpusCodecInterface
import com.example.hiai.audio.WakeWordDetector
import com.example.hiai.data.AppDatabase
import com.example.hiai.data.DatabaseInitializer
import com.example.hiai.data.repository.ChatHistoryRepository
import com.example.hiai.data.repository.SettingRepository
import com.example.hiai.network.NetworkManager
import com.example.hiai.network.model.HelloRequest
import com.example.hiai.network.model.HelloResponse
import com.example.hiai.network.model.ListenRequest
import com.example.hiai.network.model.McpToolRequest
import com.example.hiai.network.model.McpToolResponse
import com.example.hiai.network.model.SttMessage
import com.example.hiai.network.model.TtsAudioData
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

    /**
     * 监听模式
     */
    enum class ListeningMode {
        REALTIME,    // 实时模式：TTS 结束后继续监听，VAD 可打断
        MANUAL_STOP  // 手动模式：TTS 结束后回到 Idle（当前行为）
    }
    
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    
    private val _isDeskMode = MutableStateFlow(false)
    val isDeskMode: StateFlow<Boolean> = _isDeskMode.asStateFlow()
    
    // 连接状态：true 表示已连接，false 表示已断开
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // 当前 TTS 文本（空表示无 TTS 正在播放）
    private val _ttsText = MutableStateFlow("")
    val ttsText: StateFlow<String> = _ttsText.asStateFlow()
    
    // TTS 消息历史（保留最近的消息用于界面滚动显示）
    private val _ttsMessages = MutableStateFlow<List<String>>(emptyList())
    val ttsMessages: StateFlow<List<String>> = _ttsMessages.asStateFlow()
    
    // 组件
    private var networkManager: NetworkManager? = null
    private lateinit var audioProcessor: AudioProcessor
    private lateinit var opusCodec: OpusCodecInterface
    private lateinit var audioPlayer: AudioPlayer
    internal lateinit var settingRepository: SettingRepository
    private lateinit var chatHistoryRepository: ChatHistoryRepository
    
    // 唤醒词检测器
    private var wakeWordDetector: WakeWordDetector? = null
    
    // 协程
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 消息监听 Job（需要在重连时取消旧的，避免重复处理）
    private var messageJob: Job? = null
    
    // 屏幕常亮 WakeLock（桌面模式使用）
    private var screenWakeLock: android.os.PowerManager.WakeLock? = null
    
    // 防止唤醒词并发重连
    @Volatile
    private var isReconnecting = false
    
    // 网络管理器初始化状态
    private var isNetworkManagerInitialized = false
    
    // Hello 响应是否已收到（表示音频参数已确定）
    private var isHelloReceived = false

    // 监听模式（默认实时模式）
    private var listeningMode: ListeningMode = ListeningMode.REALTIME

    // Binder
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }
    
    companion object {
        private const val TAG = "VoiceAssistantService"
        const val ACTION_START = "com.example.hiai.START_SERVICE"
        const val ACTION_STOP = "com.example.hiai.STOP_SERVICE"
        const val ACTION_TOGGLE_DESK_MODE = "com.example.hiai.TOGGLE_DESK_MODE"
        const val CHANNEL_ID = "voice_assistant_channel"
        const val NOTIFICATION_ID = 1001
        private const val KEY_WAKE_WORDS = "wake_words"
        
        // 默认唤醒词
        val DEFAULT_WAKE_WORDS = listOf(
            "n ǐ h ǎo x iǎo zh ì @你好小智",
            "h ēi n ǐ h ǎo y a @嘿你好呀"
        )
    }
    
    /**
     * 获取当前唤醒词列表
     */
    fun getWakeWords(): List<String> {
        val saved = runCatching { 
            kotlinx.coroutines.runBlocking {
                settingRepository.get(KEY_WAKE_WORDS)
            }
        }.getOrNull()
        
        return if (saved.isNullOrBlank()) {
            DEFAULT_WAKE_WORDS
        } else {
            saved.split("\n").filter { it.isNotBlank() }
        }
    }
    
    /**
     * 更新唤醒词列表
     */
    fun updateWakeWords(keywords: List<String>): Boolean {
        val result = wakeWordDetector?.updateKeywords(keywords) ?: false
        if (result) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    settingRepository.set(KEY_WAKE_WORDS, keywords.joinToString("\n"))
                }
            }
        }
        return result
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化数据库
        val database = DatabaseInitializer.getDatabase(this)
        settingRepository = SettingRepository(database.settingDao())
        chatHistoryRepository = ChatHistoryRepository(database.chatHistoryDao())

        // 初始化音频组件（使用默认采样率 16000Hz，Hello 响应后可能重新初始化）
        opusCodec = OpusCodec(sampleRate = 16000)
        audioProcessor = AudioProcessor(sampleRate = 16000)
        audioPlayer = AudioPlayer(this, opusCodec as OpusCodec, 16000, serviceScope)
        
        // 初始化唤醒词检测器
        wakeWordDetector = WakeWordDetector(this) { keyword ->
            onWakeWordDetected(keyword)
        }
        wakeWordDetector?.init()
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 在协程中初始化网络管理器（需要读取设置）
        serviceScope.launch {
            initializeNetworkManager()
        }
    }
    
    /**
     * 初始化网络管理器
     */
    private suspend fun initializeNetworkManager() {
        Log.d(TAG, "=== initializeNetworkManager: START ===")
        try {
            val baseUrl = settingRepository.get("server_url") ?: "http://localhost:8000"
            val deviceId = settingRepository.get("device_id") ?: getDeviceIdString()
            val token = settingRepository.get("token") ?: ""
            
            Log.d(TAG, "  - server_url: $baseUrl")
            Log.d(TAG, "  - device_id: $deviceId")
            Log.d(TAG, "  - token: ${if (token.isEmpty()) "(empty)" else "***"}")
            
            networkManager = NetworkManager(
                baseUrl = baseUrl,
                deviceId = deviceId,
                deviceName = "Android Assistant",
                token = token
            )
            isNetworkManagerInitialized = true
            Log.d(TAG, "=== initializeNetworkManager: SUCCESS ===")
        } catch (e: Exception) {
            Log.e(TAG, "=== initializeNetworkManager: FAILED ===", e)
        }
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

        // 释放屏幕常亮 WakeLock
        releaseScreenWakeLock()
        
        // 释放唤醒词检测器
        wakeWordDetector?.release()
        wakeWordDetector = null
        
        audioPlayer.release()
        audioProcessor.release()
        opusCodec.release()
        networkManager?.disconnect()
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        Log.d(TAG, "=== startForegroundService: START ===")
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "  - Notification created and service started")
        
            // 等待网络管理器初始化完成后连接服务端
            serviceScope.launch {
                Log.d(TAG, "  - Waiting for network manager initialization...")
                // 等待网络管理器初始化
                while (!isNetworkManagerInitialized) {
                    kotlinx.coroutines.delay(100)
                }
                Log.d(TAG, "  - Network manager initialized, connecting to server...")
                connectToServer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "=== startForegroundService: FAILED ===", e)
        }
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
        Log.d(TAG, "=== connectToServer: START ===")
        serviceScope.launch {
            try {
                // 先开始监听消息（在连接之前），避免丢失 Hello 响应
                Log.d(TAG, "  - Starting message flow listener...")
                // 取消旧的消息监听，避免重连时出现重复 collector
                messageJob?.cancel()
                messageJob = launch {
                    networkManager?.messageFlow?.collect { message ->
                        when (message) {
                            is String -> {
                                if (message == "websocket_disconnected") {
                                    Log.d(TAG, "      -> WebSocket disconnected")
                                    _isConnected.value = false
                                    _ttsText.value = ""
                                    _ttsMessages.value = emptyList()
                                }
                            }
                            is HelloResponse -> {
                                Log.d(TAG, "      -> Handling HelloResponse")
                                _isConnected.value = true
                                handleHelloResponse(message)
                            }
                            is SttMessage -> {
                                Log.d(TAG, "      -> Handling SttMessage")
                                handleSttMessage(message)
                            }
                            is TtsMessage -> {
                                Log.d(TAG, "      -> Handling TtsMessage")
                                handleTtsMessage(message)
                            }
                            is TtsAudioData -> {
                                handleTtsAudioData(message)
                            }
                            else -> {
                                Log.w(TAG, "      -> Ignoring unknown message type: ${message::class.java.name}")
                            }
                        }
                    }
                }
                
                // 获取 OTA 信息
                Log.d(TAG, "  - Fetching OTA info...")
                val otaResponse = networkManager?.fetchOtaInfo()
                Log.d(TAG, "  - OTA response: ${otaResponse != null}")
                
                if (otaResponse != null && networkManager != null) {
                    // 检查设备是否需要激活（仅记录日志，不影响连接）
                    if (otaResponse.activation != null) {
                        Log.d(TAG, "  - Device requires activation!")
                        Log.d(TAG, "  - Activation code: ${otaResponse.activation.code}")
                        Log.d(TAG, "  - Activation message: ${otaResponse.activation.message}")
                        Log.d(TAG, "  - Will connect WebSocket to receive activation audio...")
                    }
                    
                    Log.d(TAG, "  - WebSocket URL: ${otaResponse.websocket.url}")
                    Log.d(TAG, "  - Connecting to WebSocket...")
                    // 连接 WebSocket（即使设备需要激活也要连接，因为服务端会通过 WebSocket 发送激活码播报）
                    networkManager?.connect(otaResponse.websocket.url, otaResponse.websocket.token)
                    Log.d(TAG, "  - WebSocket connect called")
                } else {
                    Log.e(TAG, "  - OTA response or network manager is null")
                    _serviceState.value = ServiceState.Error
                    messageJob?.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== connectToServer: FAILED ===", e)
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
        
        // 根据服务端配置动态初始化音频组件（如果采样率不同）
        val serverSampleRate = response.audioParams?.sampleRate ?: 16000
        val currentSampleRate = (opusCodec as? OpusCodec)?.sampleRate ?: 16000
        
        Log.d(TAG, "Hello response: sample_rate=$serverSampleRate, format=${response.audioParams?.format}")
        
        if (serverSampleRate != currentSampleRate) {
            Log.d(TAG, "Sample rate changed: $currentSampleRate -> $serverSampleRate, reinitializing audio components")
            
            // 释放旧的音频组件
            audioPlayer?.stopPlaying()
            audioPlayer?.release()
            opusCodec?.release()
            
            // 创建新的音频组件
            opusCodec = OpusCodec(sampleRate = serverSampleRate)
            audioProcessor = AudioProcessor(sampleRate = serverSampleRate)
            audioPlayer = AudioPlayer(this, opusCodec as OpusCodec, serverSampleRate, serviceScope)
            Log.d(TAG, "Audio components reinitialized with sample_rate=$serverSampleRate")
        } else {
            Log.d(TAG, "Sample rate unchanged ($currentSampleRate), keeping existing audio components")
        }
        
        // 标记 Hello 响应已收到
        isHelloReceived = true
        Log.d(TAG, "Hello response processed, isHelloReceived=true")
        
        // 保持本地唤醒检测，不再发送空的 listen.detect 给服务端，避免服务端把 null text 当成有效文本处理
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
                Log.d(TAG, "TTS start received, stopping recording")
                // 停止录音，避免与播放冲突
                audioProcessor.stopRecording()
                _serviceState.value = ServiceState.Speaking
                _ttsText.value = message.text ?: ""
                addTtsMessage(message.text ?: "")
            }
            "stop" -> {
                Log.d(TAG, "TTS stop received, stopping audio player")
                audioPlayer.stopPlaying()

                // 根据监听模式决定下一步状态
                if (listeningMode == ListeningMode.REALTIME) {
                    Log.d(TAG, "Realtime mode: continue listening after TTS")
                    _serviceState.value = ServiceState.Listening
                    // 重新开始录音
                    startRecordingAndUpload()
                } else {
                    Log.d(TAG, "Manual mode: back to idle after TTS")
                    _serviceState.value = ServiceState.Idle
                }
            }
            "sentence_start" -> {
                // 检查当前状态：如果已经被 VAD 打断（状态不是 Speaking），忽略后续 TTS 数据
                val currentState = _serviceState.value
                if (currentState != ServiceState.Speaking) {
                    Log.w(TAG, "TTS sentence_start received but state=$currentState (already interrupted), ignoring")
                    // 确保停止播放
                    audioPlayer.stopPlaying()
                    audioPlayer.clearQueue()
                    return
                }
                Log.d(TAG, "TTS state: sentence_start")
                _ttsText.value = message.text ?: ""
                addTtsMessage(message.text ?: "")
            }
            else -> {
                Log.d(TAG, "TTS state: ${message.state}")
            }
        }
    }
    
    private fun addTtsMessage(text: String) {
        if (text.isBlank()) return
        val current = _ttsMessages.value.toMutableList()
        // 避免重复添加相同文本
        if (current.lastOrNull() == text) return
        current.add(text)
        // 只保留最近 20 条
        if (current.size > 20) {
            _ttsMessages.value = current.takeLast(20)
        } else {
            _ttsMessages.value = current
        }
    }
    
    /**
     * 处理 TTS 音频数据
     */
    private fun handleTtsAudioData(data: TtsAudioData) {
        // 将 Opus 数据送入播放器
        audioPlayer.enqueueAudioData(data.opusData)
    }
    
    /**
     * 切换桌面模式
     */
    private fun toggleDeskMode() {
        Log.d(TAG, "=== toggleDeskMode: START ===")
        try {
            val newMode = !_isDeskMode.value
            _isDeskMode.value = newMode
            
            Log.d(TAG, "  - New mode: $newMode")
            
            if (newMode) {
                // 进入桌面模式，启动唤醒词检测
                Log.d(TAG, "  - Starting wake word detector...")
                wakeWordDetector?.start()
                Log.d(TAG, "  - Wake word detector started")
                Log.i(TAG, "Desktop mode enabled, wake word detection started")
                
                // 获取屏幕常亮 WakeLock
                acquireScreenWakeLock()
            } else {
                // 退出桌面模式，停止唤醒词检测
                Log.d(TAG, "  - Stopping wake word detector...")
                wakeWordDetector?.stop()
                Log.d(TAG, "  - Wake word detector stopped")
                Log.i(TAG, "Desktop mode disabled, wake word detection stopped")
                
                // 释放屏幕常亮 WakeLock
                releaseScreenWakeLock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "=== toggleDeskMode: FAILED ===", e)
        }
    }
    
    private fun acquireScreenWakeLock() {
        try {
            if (screenWakeLock?.isHeld == true) return
            
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            screenWakeLock = powerManager.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "hiai::DeskModeScreen"
            )
            screenWakeLock?.acquire()
            Log.d(TAG, "Screen WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire screen WakeLock", e)
        }
    }
    
    private fun releaseScreenWakeLock() {
        try {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
                Log.d(TAG, "Screen WakeLock released")
            }
            screenWakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release screen WakeLock", e)
        }
    }
    
    /**
     * 唤醒词触发回调
     */
    private fun onWakeWordDetected(keyword: String) {
        Log.i(TAG, "Wake word detected: $keyword, state=${_serviceState.value}")

        // 防止并发重连：如果正在重连中，忽略新的唤醒
        if (isReconnecting) {
            Log.w(TAG, "Already reconnecting, ignoring wake word")
            return
        }

        // 等待 WebSocket 连接和 Hello 响应，如果断连则自动重连
        serviceScope.launch {
            Log.d(TAG, "Waiting for WebSocket connection and Hello response...")
            var waitCount = 0
            while (!isHelloReceived || networkManager?.isConnectionOpen() != true) {
                if (waitCount % 10 == 0) {  // 每 1 秒打印一次日志
                    Log.d(TAG, "  - isHelloReceived=$isHelloReceived, isConnected=${networkManager?.isConnectionOpen()}")
                }
                
                // 如果 WebSocket 断开，尝试自动重连
                if (networkManager?.isConnectionOpen() == false) {
                    if (isReconnecting) {
                        // 已有重连流程在运行，等待即可
                        Log.d(TAG, "  - Reconnection already in progress, waiting...")
                    } else {
                        isReconnecting = true
                        try {
                            Log.d(TAG, "  - WebSocket disconnected, attempting to reconnect...")
                            // 重新获取 OTA 信息并连接
                            val otaResponse = networkManager?.fetchOtaInfo()
                            if (otaResponse != null) {
                                Log.d(TAG, "  - Reconnecting to WebSocket: ${otaResponse.websocket.url}")
                                networkManager?.connect(otaResponse.websocket.url, otaResponse.websocket.token)
                            } else {
                                Log.e(TAG, "  - Failed to fetch OTA info for reconnection")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "  - Reconnection attempt failed", e)
                        } finally {
                            isReconnecting = false
                        }
                    }
                }
                
                kotlinx.coroutines.delay(100)
                waitCount++
                if (waitCount > 100) {  // 超时 10 秒
                    Log.e(TAG, "Timeout waiting for WebSocket connection and Hello response")
                    isReconnecting = false
                    return@launch
                }
            }
            Log.d(TAG, "WebSocket connected and Hello received, proceeding with wake word handling")
            isReconnecting = false
            
            // 发送 listen 消息，开始录音上传
            networkManager?.sendListen(state = "start", text = keyword)
            
            // 进入 LISTENING 状态
            _serviceState.value = ServiceState.Listening
            
            // 开始录音并上传音频流
            startRecordingAndUpload()
        }
    }

    /**
     * 设置监听模式
     *
     * @param mode 监听模式
     */
    fun setListeningMode(mode: ListeningMode) {
        Log.d(TAG, "Setting listening mode to: $mode")
        listeningMode = mode
    }

    /**
     * 开始录音并上传音频流
     */
    private fun startRecordingAndUpload() {
        serviceScope.launch {
            // 获取 OpusCodec 期望的单帧 PCM 缓冲区大小 (pcmBufferSize)
            val expectedBufferSize = (opusCodec as? OpusCodec)?.pcmBufferSize ?: -1
            audioProcessor.startRecording(expectedBufferSize).collect { pcmData ->
                // 确保每次送入编码的数据大小完全符合 frameSize 的要求
                if (expectedBufferSize > 0 && pcmData.size != expectedBufferSize) {
                    Log.w(TAG, "Recording buffer size mismatch. Expected: $expectedBufferSize, Got: ${pcmData.size}. Skipping frame to avoid crash.")
                    return@collect
                }
                // 编码为 Opus
                val opusData = opusCodec.encode(pcmData)
                // 发送到服务端
                networkManager?.sendAudio(opusData)
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
    private fun getDeviceIdString(): String {
        return Build.SERIAL.ifBlank { "unknown" }
    }
}
