package com.example.hiai.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OTA 请求模型
 * 发送到服务端 OTA 接口的设备上报信息
 */
@Serializable
data class OtaRequest(
    @SerialName("version")
    val version: Int,
    @SerialName("uuid")
    val uuid: String,
    @SerialName("application")
    val application: Application,
    @SerialName("ota")
    val ota: OtaInfo,
    @SerialName("board")
    val board: BoardInfo,
    @SerialName("flash_size")
    val flashSize: Int,
    @SerialName("minimum_free_heap_size")
    val minimumFreeHeapSize: Int,
    @SerialName("mac_address")
    val macAddress: String,
    @SerialName("chip_model_name")
    val chipModelName: String,
    @SerialName("chip_info")
    val chipInfo: ChipInfo,
    @SerialName("partition_table")
    val partitionTable: List<Partition>
) {
    @Serializable
    data class Application(
        @SerialName("name")
        val name: String,
        @SerialName("version")
        val version: String,
        @SerialName("compile_time")
        val compileTime: String,
        @SerialName("idf_version")
        val idfVersion: String,
        @SerialName("elf_sha256")
        val elfSha256: String
    )

    @Serializable
    data class OtaInfo(
        @SerialName("label")
        val label: String
    )

    @Serializable
    data class BoardInfo(
        @SerialName("type")
        val type: String,
        @SerialName("ssid")
        val ssid: String,
        @SerialName("rssi")
        val rssi: Int,
        @SerialName("channel")
        val channel: Int,
        @SerialName("ip")
        val ip: String,
        @SerialName("mac")
        val mac: String
    )

    @Serializable
    data class ChipInfo(
        @SerialName("model")
        val model: Int,
        @SerialName("cores")
        val cores: Int,
        @SerialName("revision")
        val revision: Int,
        @SerialName("features")
        val features: Int
    )

    @Serializable
    data class Partition(
        @SerialName("label")
        val label: String,
        @SerialName("type")
        val type: Int,
        @SerialName("subtype")
        val subtype: Int,
        @SerialName("address")
        val address: Int,
        @SerialName("size")
        val size: Int
    )
}

/**
 * OTA 响应模型
 * 服务端返回 WebSocket 连接信息
 */
@Serializable
data class OtaResponse(
    @SerialName("websocket")
    val websocket: WebsocketInfo,
    @SerialName("activation")
    val activation: ActivationInfo? = null
) {
    @Serializable
    data class WebsocketInfo(
        @SerialName("url")
        val url: String,
        @SerialName("token")
        val token: String
    )

    @Serializable
    data class ActivationInfo(
        @SerialName("code")
        val code: String,
        @SerialName("message")
        val message: String,
        @SerialName("challenge")
        val challenge: String
    )
}

/**
 * WebSocket 消息基类
 */
@Serializable
sealed class WebSocketMessage {
    abstract val type: String
}

/**
 * Hello 握手消息（客户端→服务端）
 * 用于建立连接时的设备信息交换
 */
@Serializable
data class HelloRequest(
    @SerialName("type")
    override val type: String = "hello",
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("device_mac")
    val deviceMac: String,
    @SerialName("token")
    val token: String,
    @SerialName("features")
    val features: Features = Features()
) : WebSocketMessage() {
    @Serializable
    data class Features(
        @SerialName("mcp")
        val mcp: Boolean = true,
        @SerialName("emoji")
        val emoji: Boolean = false
    )
}

/**
 * Hello 响应消息（服务端→客户端）
 * 服务端返回会话 ID 和音频参数
 */
@Serializable
data class HelloResponse(
    @SerialName("type")
    override val type: String = "hello",
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("audio_params")
    val audioParams: AudioParams? = null
) : WebSocketMessage() {
    @Serializable
    data class AudioParams(
        @SerialName("format")
        val format: String = "opus",
        @SerialName("sample_rate")
        val sampleRate: Int = 16000,  // 默认 16000Hz（Concentus 在 24000Hz 下有 bug）
        @SerialName("channels")
        val channels: Int = 1,
        @SerialName("frame_duration")
        val frameDuration: Int = 60
    )
}

/**
 * Listen 控制消息（客户端→服务端）
 * 控制聆听状态：唤醒检测、开始录音、停止录音
 */
@Serializable
data class ListenRequest(
    @SerialName("type")
    override val type: String = "listen",
    @SerialName("state")
    val state: String, // "detect", "start", "stop"
    @SerialName("mode")
    val mode: String? = null, // "auto", "manual"
    @SerialName("text")
    val text: String? = null // 唤醒词文本
) : WebSocketMessage()

/**
 * STT 识别结果（服务端→客户端）
 * 服务端返回语音识别的文本结果
 */
@Serializable
data class SttMessage(
    @SerialName("type")
    override val type: String = "stt",
    @SerialName("text")
    val text: String,
    @SerialName("session_id")
    val sessionId: String? = null
) : WebSocketMessage()

/**
 * TTS 控制消息（服务端→客户端）
 * 控制 TTS 播放状态
 */
@Serializable
data class TtsMessage(
    @SerialName("type")
    override val type: String = "tts",
    @SerialName("state")
    val state: String, // "start", "stop", "sentence_start"
    @SerialName("text")
    val text: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null
) : WebSocketMessage()

/**
 * MCP 工具调用消息（服务端→客户端）
 * 服务端请求客户端执行 MCP 工具
 */
@Serializable
data class McpToolRequest(
    @SerialName("type")
    override val type: String = "tool",
    @SerialName("tool_name")
    val toolName: String,
    @SerialName("arguments")
    val arguments: Map<String, String>
) : WebSocketMessage()

/**
 * MCP 工具响应（客户端→服务端）
 * 客户端返回工具执行结果
 */
@Serializable
data class McpToolResponse(
    @SerialName("type")
    override val type: String = "tool_result",
    @SerialName("tool_name")
    val toolName: String,
    @SerialName("result")
    val result: String,
    @SerialName("success")
    val success: Boolean
) : WebSocketMessage()

/**
 * TTS 音频数据（服务端→客户端）
 * Opus 格式的音频数据
 */
data class TtsAudioData(
    val opusData: ByteArray
)
