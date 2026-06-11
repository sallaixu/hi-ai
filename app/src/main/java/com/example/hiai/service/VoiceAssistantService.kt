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
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
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
    
    // 网络管理器初始化状态
    private var isNetworkManagerInitialized = false
    
    // Hello 响应是否已收到（表示音频参数已确定）
    private var isHelloReceived = false

    // 监听模式（默认实时模式）
    private var listeningMode: ListeningMode = ListeningMode.REALTIME

    // VAD 打断功能
    private var isVadInterruptEnabled: Boolean = true
    private var vadDetector: VadWebRTC? = null
    private var vadCheckJob: Job? = null
    
    // Binder
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化数据库
        val database = DatabaseInitializer.getDatabase(this)
        settingRepository = SettingRepository(database.settingDao())
        chatHistoryRepository = ChatHistoryRepository(database.chatHistoryDao())

        // 初始化 VAD 检测器
        initializeVadDetector()

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

        // 释放 VAD 检测器
        vadDetector?.close()
        vadDetector = null

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
                val messageJob = launch {
                    networkManager?.messageFlow?.collect { message ->
                        Log.d(TAG, "    - Received message: ${message::class.java.simpleName}, type: ${message::class.java.name}")
                        when (message) {
                            is HelloResponse -> {
                                Log.d(TAG, "      -> Handling HelloResponse")
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
                                Log.d(TAG, "      -> Handling TtsAudioData")
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
                    stopVadDetection()
                    _serviceState.value = ServiceState.Error
                    messageJob.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== connectToServer: FAILED ===", e)
                stopVadDetection()
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
        
        // 发送聆听状态（唤醒检测）
        networkManager?.sendListen(state = "detect")
        
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

                // 启动 VAD 检测（在 Speaking 状态下）
                if (isVadInterruptEnabled && vadDetector != null) {
                    startVadDetection()
                }
            }
            "stop" -> {
                Log.d(TAG, "TTS stop received, stopping audio player")
                audioPlayer.stopPlaying()

                // 停止 VAD 检测
                stopVadDetection()

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
            else -> {
                Log.d(TAG, "TTS state: ${message.state}")
            }
        }
    }
    
    /**
     * 处理 TTS 音频数据
     */
    private fun handleTtsAudioData(data: TtsAudioData) {
        Log.d(TAG, "handleTtsAudioData: ${data.opusData.size} bytes")
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
            } else {
                // 退出桌面模式，停止唤醒词检测
                Log.d(TAG, "  - Stopping wake word detector...")
                wakeWordDetector?.stop()
                Log.d(TAG, "  - Wake word detector stopped")
                Log.i(TAG, "Desktop mode disabled, wake word detection stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "=== toggleDeskMode: FAILED ===", e)
        }
    }
    
    /**
     * 唤醒词触发回调
     */
    private fun onWakeWordDetected(keyword: String) {
        Log.i(TAG, "Wake word detected: $keyword")
        
        // 等待 WebSocket 连接和 Hello 响应
        serviceScope.launch {
            Log.d(TAG, "Waiting for WebSocket connection and Hello response...")
            var waitCount = 0
            while (!isHelloReceived || networkManager?.isConnectionOpen() != true) {
                if (waitCount % 10 == 0) {  // 每 1 秒打印一次日志
                    Log.d(TAG, "  - isHelloReceived=$isHelloReceived, isConnected=${networkManager?.isConnectionOpen()}")
                }
                kotlinx.coroutines.delay(100)
                waitCount++
                if (waitCount > 100) {  // 超时 10 秒
                    Log.e(TAG, "Timeout waiting for WebSocket connection and Hello response")
                    return@launch
                }
            }
            Log.d(TAG, "WebSocket connected and Hello received, proceeding with wake word handling")
            
            // 发送 listen 消息，开始录音上传
            networkManager?.sendListen(state = "start", text = keyword)
            
            // 进入 LISTENING 状态
            _serviceState.value = ServiceState.Listening
            
            // 开始录音并上传音频流
            startRecordingAndUpload()
        }
    }
    
    /**
     * 初始化 VAD 检测器
     */
    private fun initializeVadDetector() {
        try {
            vadDetector = VadWebRTC(
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_320,
                mode = Mode.VERY_AGGRESSIVE,
                silenceDurationMs = 300,
                speechDurationMs = 50
            )
            Log.i(TAG, "VAD detector initialized successfully (android-vad WebRTC)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VAD detector", e)
            vadDetector = null
            isVadInterruptEnabled = false
        }
    }

    /**
     * 启动 VAD 检测（在 Speaking 状态下）
     */
    private fun startVadDetection() {
        if (vadCheckJob?.isActive == true) {
            Log.w(TAG, "VAD detection already running")
            return
        }

        vadCheckJob = serviceScope.launch {
            Log.d(TAG, "VAD detection started in Speaking state")

            // 创建一个轻量级的录音器用于 VAD 检测
            // FrameSize.FRAME_SIZE_320 = 320 samples = 640 bytes (16bit) = 20ms at 16KHz
            val vadSampleRate = 16000
            val vadFrameSize = 320  // 对应 FrameSize.FRAME_SIZE_320
            val vadBufferSize = vadFrameSize * 2  // 字节数（16bit = 2 bytes/sample）

            val vadAudioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                vadSampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                vadBufferSize * 4  // 4 倍缓冲
            )

            if (vadAudioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "VAD AudioRecord initialization failed")
                return@launch
            }

            vadAudioRecord.startRecording()
            val buffer = ShortArray(vadFrameSize)
            val byteBuffer = ByteArray(vadBufferSize)

            try {
                while (_serviceState.value == ServiceState.Speaking && isActive) {
                    val read = vadAudioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // ShortArray 转 ByteArray（android-vad 需要 ByteArray）
                        for (i in buffer.indices) {
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        if (vadDetector?.isSpeech(byteBuffer) == true) {
                            Log.i(TAG, "VAD detected speech during TTS playback")
                            onVadDetected()
                            break
                        }
                    }
                    delay(20)  // 20ms 检测间隔（匹配 320 frame size）
                }
            } catch (e: Exception) {
                Log.e(TAG, "VAD detection error", e)
            } finally {
                try {
                    vadAudioRecord.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop VAD AudioRecord", e)
                }
                vadAudioRecord.release()
                Log.d(TAG, "VAD detection stopped")
            }
        }
    }

    /**
     * 停止 VAD 检测
     */
    private fun stopVadDetection() {
        vadCheckJob?.cancel()
        vadCheckJob = null
        Log.d(TAG, "VAD detection job cancelled")
    }

    /**
     * VAD 检测到语音时的处理
     */
    private fun onVadDetected() {
        Log.i(TAG, "VAD detected, aborting TTS")

        // 发送 abort 消息到服务端
        networkManager?.sendAbort(reason = "vad_detected")

        // 停止 TTS 播放
        audioPlayer.stopPlaying()

        // 清空音频队列
        audioPlayer.clearQueue()

        // 停止 VAD 检测
        stopVadDetection()

        // 切换到监听状态
        _serviceState.value = ServiceState.Listening

        // 重新开始录音
        startRecordingAndUpload()
    }

    /**
     * 设置 VAD 灵敏度
     *
     * @param mode VAD 模式
     */
    fun setVadSensitivity(mode: Mode) {
        Log.d(TAG, "Setting VAD sensitivity to: $mode")

        // 释放旧的检测器
        vadDetector?.close()

        // 创建新的检测器
        try {
            vadDetector = VadWebRTC(
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_320,
                mode = mode,
                silenceDurationMs = 300,
                speechDurationMs = 50
            )
            Log.i(TAG, "VAD sensitivity changed to: $mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VAD detector with new sensitivity", e)
            vadDetector = null
            isVadInterruptEnabled = false
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
    
    companion object {
        const val ACTION_START = "com.example.hiai.START_SERVICE"
        const val ACTION_STOP = "com.example.hiai.STOP_SERVICE"
        const val ACTION_TOGGLE_DESK_MODE = "com.example.hiai.TOGGLE_DESK_MODE"
        
        const val CHANNEL_ID = "voice_assistant_channel"
        const val NOTIFICATION_ID = 1001
        
        private const val TAG = "VoiceAssistantService"
    }
}
