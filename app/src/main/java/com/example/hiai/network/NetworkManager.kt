package com.example.hiai.network

import android.util.Log
import com.example.hiai.network.model.AbortRequest
import com.example.hiai.network.model.HelloRequest
import com.example.hiai.network.model.HelloResponse
import com.example.hiai.network.model.ListenRequest
import com.example.hiai.network.model.McpToolRequest
import com.example.hiai.network.model.McpToolResponse
import com.example.hiai.network.model.OtaRequest
import com.example.hiai.network.model.OtaResponse
import com.example.hiai.network.model.SttMessage
import com.example.hiai.network.model.TtsAudioData
import com.example.hiai.network.model.TtsMessage
import com.example.hiai.network.model.WebSocketMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.subclass
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * 网络管理器
 * 
 * 负责：
 * - OTA 请求：获取 WebSocket 连接地址
 * - WebSocket 连接：与服务端进行双向通信
 * - 消息编解码：JSON 序列化/反序列化
 */
class NetworkManager(
    private val baseUrl: String,
    private val deviceId: String,
    private val deviceName: String = "Android Assistant",
    private val token: String = ""
) {
    companion object {
        private const val TAG = "NetworkManager"
    }

    // 基于 deviceId 生成一个唯一的虚拟 MAC 地址，统一用于 OTA 和 WebSocket
    val virtualMac: String = generateVirtualMac(deviceId)
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // 无限读取超时
        .writeTimeout(0, TimeUnit.MILLISECONDS) // 无限写入超时
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
    
    private var webSocket: WebSocket? = null
    // 使用 replay = 1 缓存最后一个消息，避免 collect 启动延迟导致消息丢失
    private val _messageFlow = MutableSharedFlow<Any>(replay = 1)
    val messageFlow: SharedFlow<Any> = _messageFlow.asSharedFlow()
    
    private var isConnected = false
    
    /**
     * OTA 请求：获取 WebSocket 连接信息
     */
    suspend fun fetchOtaInfo(): OtaResponse? = withContext(Dispatchers.IO) {
        try {
            // baseUrl 已经是完整的 OTA URL
            Log.d(TAG, "  - OTA Request URL: $baseUrl")
            Log.d(TAG, "  - Using virtual MAC: $virtualMac")
            
            // 构建符合服务端要求的 OTA 请求体
            val otaRequest = OtaRequest(
                version = 0,
                uuid = "", // 官方测试页面使用空字符串
                application = OtaRequest.Application(
                    name = "android-hi-ai",
                    version = "1.0.0",
                    compileTime = "2025-04-16 10:00:00",
                    idfVersion = "4.4.3",
                    elfSha256 = "1234567890abcdef1234567890abcdef1234567890abcdef"
                ),
                ota = OtaRequest.OtaInfo(label = "android-hi-ai"),
                board = OtaRequest.BoardInfo(
                    type = deviceName,
                    ssid = "android-hi-ai",
                    rssi = -50,
                    channel = 6,
                    ip = "192.168.1.100",
                    mac = virtualMac
                ),
                flashSize = 0,
                minimumFreeHeapSize = 0,
                macAddress = virtualMac,
                chipModelName = "",
                chipInfo = OtaRequest.ChipInfo(
                    model = 0,
                    cores = 0,
                    revision = 0,
                    features = 0
                ),
                partitionTable = listOf(
                    OtaRequest.Partition(
                        label = "",
                        type = 0,
                        subtype = 0,
                        address = 0,
                        size = 0
                    )
                )
            )
            
            val requestBody = json.encodeToString(otaRequest)
            Log.d(TAG, "  - OTA Request body: $requestBody")
            
            // 添加必要的 headers - Device-Id 应该是 MAC 地址格式
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Device-Id", virtualMac) // 使用 MAC 地址作为 Device-Id
                .addHeader("Client-Id", virtualMac) // 使用 MAC 地址作为 Client-Id
                .post(
                    requestBody.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )
                .build()
            
            Log.d(TAG, "  - Executing OTA POST request...")
            val response = client.newCall(request).execute()
            Log.d(TAG, "  - OTA Response code: ${response.code}")
            
            return@withContext if (response.isSuccessful) {
                val body = response.body?.string()
                Log.d(TAG, "  - OTA Response body: ${body?.take(200)}")
                try {
                    val otaResponse = body?.let { json.decodeFromString<OtaResponse>(it) }
                    Log.d(TAG, "  - OTA Response parsed successfully: ${otaResponse?.websocket?.url}")
                    otaResponse
                } catch (e: Exception) {
                    Log.e(TAG, "  - Failed to parse OTA response", e)
                    null
                }
            } else {
                Log.e(TAG, "  - OTA Response unsuccessful: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "  - OTA request failed", e)
            null
        }
    }
    
    /**
     * 基于 deviceId 生成一个虚拟 MAC 地址
     * 格式：XX:XX:XX:XX:XX:XX
     */
    private fun generateVirtualMac(deviceId: String): String {
        val hash = deviceId.hashCode()
        val macBytes = ByteArray(6)
        macBytes[0] = ((hash shr 24) and 0xFF).toByte()
        macBytes[1] = ((hash shr 16) and 0xFF).toByte()
        macBytes[2] = ((hash shr 8) and 0xFF).toByte()
        macBytes[3] = (hash and 0xFF).toByte()
        macBytes[4] = 0x77.toByte() // 固定字节，确保是单播地址
        macBytes[5] = 0x34.toByte() // 固定字节
        
        return macBytes.joinToString(":") { "%02X".format(it) }
    }
    
    /**
     * 连接 WebSocket
     * 
     * @param url WebSocket URL（从 OTA 获取）
     * @param token 认证 token
     */
    fun connect(url: String, token: String) {
        Log.d(TAG, "=== connect: START ===")
        Log.d(TAG, "  - WebSocket URL: $url")
        Log.d(TAG, "  - Token: ${if (token.isEmpty()) "(empty)" else "***"}")
        
        // 构建完整的 WebSocket URL，添加必要的查询参数
        val wsUrl = buildString {
            append(url.trimEnd('/'))
            append("?device-id=")
            append(java.net.URLEncoder.encode(virtualMac, "UTF-8"))
            append("&client-id=")
            append(java.net.URLEncoder.encode(deviceId, "UTF-8"))
        }
        Log.d(TAG, "  - Final WebSocket URL: $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Device-Id", virtualMac)
            .addHeader("Client-Id", virtualMac)
            .addHeader("Origin", "https://android-hi-ai.local")
            .build()
        
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "  - WebSocket opened")
                isConnected = true
                // 发送 Hello 握手消息
                sendHello()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "  - Received text message: ${text.take(200)}")
                
                // 忽略非 JSON 消息（如服务端提示信息）
                if (!text.trim().startsWith("{")) {
                    Log.d(TAG, "  - Ignoring non-JSON message")
                    return
                }
                
                try {
                    // 手动解析 type 字段，然后选择对应的序列化器
                    val type = extractTypeFromJson(text)
                    Log.d(TAG, "  - Parsed message type: $type")
                    
                    val message = when (type) {
                        "hello" -> {
                            Log.d(TAG, "  - Decoding HelloResponse...")
                            json.decodeFromString<HelloResponse>(text)
                        }
                        "stt" -> json.decodeFromString<SttMessage>(text)
                        "tts" -> json.decodeFromString<TtsMessage>(text)
                        "error" -> json.decodeFromString<SttMessage>(text) // 错误消息也使用 SttMessage 格式
                        else -> {
                            Log.w(TAG, "  - Unknown message type: $type")
                            null
                        }
                    }
                    
                    message?.let { 
                        Log.d(TAG, "  - Emitting message to flow: ${it::class.java.simpleName}")
                        _messageFlow.tryEmit(it) 
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  - Failed to parse message", e)
                }
            }
            
            /**
             * 从 JSON 中提取 type 字段
             */
            private fun extractTypeFromJson(json: String): String? {
                return try {
                    val pattern = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
                    val match = pattern.find(json)
                    match?.groupValues?.get(1)
                } catch (e: Exception) {
                    null
                }
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制消息（Opus 音频数据）
                val opusData = bytes.toByteArray()
                // 将 Opus 数据通过消息流发送给监听者
                // 使用 runBlocking 确保消息被发送
                kotlinx.coroutines.runBlocking {
                    _messageFlow.emit(TtsAudioData(opusData))
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "  - WebSocket closing: code=$code, reason=$reason")
                isConnected = false
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "  - WebSocket closed: code=$code, reason=$reason")
                isConnected = false
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "  - WebSocket failure", t)
                isConnected = false
                // TODO: 错误处理
            }
        }
        
        Log.d(TAG, "  - Creating WebSocket connection...")
        webSocket = client.newWebSocket(request, listener)
        Log.d(TAG, "=== connect: DONE ===")
    }
    
    /**
     * 发送 Hello 握手消息
     */
    private fun sendHello() {
        // 手动构建 Hello JSON，确保包含 type 字段
        // device_id 和 device_mac 应该相同（MAC 地址）
        val helloJson = buildString {
            append("{")
            append("\"type\":\"hello\",")
            append("\"device_id\":\"$virtualMac\",")
            append("\"device_name\":\"$deviceName\",")
            append("\"device_mac\":\"$virtualMac\",")
            append("\"features\":{\"mcp\":true,\"emoji\":false}")
            append("}")
        }
        
        Log.d(TAG, "=== sendHello: Sending Hello message ===")
        Log.d(TAG, "  - Hello JSON: $helloJson")
        val sent = webSocket?.send(helloJson)
        Log.d(TAG, "  - Send result: $sent")
    }
    
    /**
     * 发送消息
     */
    fun sendMessage(message: String) {
        webSocket?.send(message)
    }
    
    /**
     * 发送 abort 消息
     *
     * @param reason 打断原因（可选）
     */
    fun sendAbort(reason: String? = null) {
        try {
            val message = AbortRequest(reason = reason)
            val jsonStr = json.encodeToString(message)
            val sent = webSocket?.send(jsonStr)
            Log.d(TAG, "Sent abort message, reason: $reason, sent: $sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send abort message", e)
        }
    }
    
    /**
     * 发送 Listen 控制消息
     */
    fun sendListen(state: String, mode: String? = null, text: String? = null) {
        val message = ListenRequest(state = state, mode = mode, text = text)
        sendMessage(json.encodeToString(message))
    }
    
    /**
     * 发送音频数据
     */
    fun sendAudio(opusData: ByteArray) {
        webSocket?.send(opusData.toByteString())
    }
    
    /**
     * 发送 MCP 工具响应
     */
    fun sendMcpResponse(toolName: String, result: String, success: Boolean) {
        val message = McpToolResponse(toolName = toolName, result = result, success = success)
        sendMessage(json.encodeToString(message))
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        isConnected = false
    }
    
    /**
     * 获取连接状态
     */
    fun isConnectionOpen(): Boolean = isConnected
    
    /**
     * 获取 Hello 响应（从消息流中过滤）
     */
    suspend fun getHelloResponse(): HelloResponse? = withContext(Dispatchers.IO) {
        // TODO: 实现超时逻辑
        null
    }
}
