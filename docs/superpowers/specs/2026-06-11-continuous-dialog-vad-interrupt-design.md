# 连续对话与 VAD 打断功能设计

**日期**: 2026-06-11
**状态**: 待实现
**优先级**: 高

## 1. 概述

### 1.1 目标

为 hiai Android 项目添加两个核心功能：
1. **实时连续对话模式**：TTS 结束后自动继续监听，实现多轮对话
2. **VAD 打断功能**：在 TTS 播放过程中检测到用户说话时立即打断

### 1.2 背景

参考官方 xiaozhi-esp32 项目实现，该功能在 ESP32 客户端已完整实现：
- ESP32 使用 ESP-ADF 的 Audio Front-End (AFE) 库实现 VAD
- 通过 `OnVadStateChange(bool speaking)` 回调通知状态
- 支持 `listening_mode` 机制（auto/manual/realtime）

### 1.3 用户需求

- **默认启用实时模式**：TTS 结束后继续监听
- **VAD 打断**：检测到用户说话就打断 TTS
- **立即重新监听**：打断后清空队列重新开始录音
- **可配置 VAD 灵敏度**：允许用户调整检测阈值

## 2. 技术方案

### 2.1 VAD 实现方案：WebRTC VAD

**选择理由**：
- Google 官方实现，稳定可靠
- 轻量级，性能好
- 已有成熟的 Android 移植版本
- 支持三种灵敏度模式：High、Medium、Low

**依赖库**：
```gradle
// 使用 WebRTC VAD 的 Android 封装
implementation 'com.github.axet:android-library:1.0.0'
```

**核心类**：
```kotlin
class WebRtcVadDetector(
    private val mode: VadMode = VadMode.MEDIUM,  // 可配置灵敏度
    private val sampleRate: Int = 16000,
    private val frameSizeMs: Int = 10  // 10ms 帧长
) {
    enum class VadMode {
        QUALITY,    // 高质量（低灵敏度）
        LOW_BITRATE, // 低比特率（中灵敏度）
        AGGRESSIVE  // 激进（高灵敏度）
    }

    fun init(): Boolean
    fun detect(pcmData: ShortArray): Boolean
    fun release()
}
```

### 2.2 监听模式设计

```kotlin
enum class ListeningMode {
    REALTIME,    // 实时模式：TTS 结束后继续监听，VAD 可打断
    MANUAL_STOP  // 手动模式：TTS 结束后回到 Idle（当前行为）
}
```

**默认值**：`REALTIME`

### 2.3 Abort 消息协议

参考 xiaozhi-esp32 的 `SendAbortSpeaking()` 实现：

```kotlin
@Serializable
data class AbortRequest(
    @SerialName("type") override val type: String = "abort",
    @SerialName("reason") val reason: String? = null
) : WebSocketMessage()
```

**reason 字段**：
- `"vad_detected"` - VAD 检测到用户说话
- `"wake_word_detected"` - 检测到唤醒词（未来扩展）

## 3. 状态流转设计

### 3.1 正常流程（实时模式）

```
Idle → Listening → Speaking → Listening → Speaking → ...
         ↑_______________|
         (TTS stop 后继续监听)
```

### 3.2 VAD 打断流程

```
Speaking (检测到 VAD)
  → 发送 abort 消息
  → 停止 TTS 播放
  → 清空音频队列
  → 切换到 Listening
  → 重新开始录音
```

### 3.3 状态机变更

**当前状态**：
```kotlin
sealed class ServiceState {
    object Idle : ServiceState()
    object Listening : ServiceState()
    object Speaking : ServiceState()
    object Error : ServiceState()
}
```

**新增成员变量**：
```kotlin
private var listeningMode: ListeningMode = ListeningMode.REALTIME
private var isVadInterruptEnabled: Boolean = true
private var vadDetector: WebRtcVadDetector? = null
```

## 4. 详细实现设计

### 4.1 文件修改清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `ProtocolModels.kt` | 新增 | 添加 `AbortRequest` 消息类型 |
| `NetworkManager.kt` | 新增方法 | 添加 `sendAbort()` 方法 |
| `WebRtcVadDetector.kt` | 新建 | WebRTC VAD 封装类 |
| `AudioProcessor.kt` | 新增功能 | 集成 VAD 检测回调 |
| `AudioPlayer.kt` | 新增方法 | 添加 `clearQueue()` 方法 |
| `VoiceAssistantService.kt` | 核心修改 | 实现连续对话和打断逻辑 |
| `build.gradle.kts` | 新增依赖 | 添加 WebRTC VAD 依赖 |

### 4.2 核心代码实现

#### 4.2.1 WebRtcVadDetector.kt

```kotlin
package com.example.hiai.audio

import android.util.Log

/**
 * WebRTC VAD 检测器
 *
 * 封装 WebRTC 的 Voice Activity Detection 功能
 */
class WebRtcVadDetector(
    private val mode: VadMode = VadMode.MEDIUM,
    private val sampleRate: Int = 16000,
    private val frameSizeMs: Int = 10
) {
    enum class VadMode(val value: Int) {
        QUALITY(0),      // 高质量（低灵敏度）
        LOW_BITRATE(1),  // 低比特率（中灵敏度）
        AGGRESSIVE(2)    // 激进（高灵敏度）
    }

    private var vadHandle: Long = 0
    private var isInitialized = false

    companion object {
        private const val TAG = "WebRtcVadDetector"

        init {
            try {
                System.loadLibrary("webrtc_vad")
                Log.d(TAG, "WebRTC VAD library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load WebRTC VAD library", e)
            }
        }
    }

    /**
     * 初始化 VAD
     */
    fun init(): Boolean {
        if (isInitialized) return true

        vadHandle = nativeCreate(mode.value)
        if (vadHandle == 0L) {
            Log.e(TAG, "Failed to create VAD instance")
            return false
        }

        isInitialized = true
        Log.d(TAG, "VAD initialized with mode: $mode")
        return true
    }

    /**
     * 检测音频帧是否包含语音
     *
     * @param pcmData PCM 音频数据（Short 数组）
     * @return true 表示检测到语音，false 表示静音
     */
    fun detect(pcmData: ShortArray): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "VAD not initialized")
            return false
        }

        return nativeDetect(vadHandle, pcmData, sampleRate)
    }

    /**
     * 释放资源
     */
    fun release() {
        if (isInitialized && vadHandle != 0L) {
            nativeDestroy(vadHandle)
            vadHandle = 0
            isInitialized = false
            Log.d(TAG, "VAD released")
        }
    }

    // JNI 方法
    private external fun nativeCreate(mode: Int): Long
    private external fun nativeDetect(handle: Long, data: ShortArray, sampleRate: Int): Boolean
    private external fun nativeDestroy(handle: Long)
}
```

#### 4.2.2 VoiceAssistantService.kt 核心修改

**新增成员变量**：
```kotlin
// 监听模式
private var listeningMode: ListeningMode = ListeningMode.REALTIME

// VAD 打断
private var isVadInterruptEnabled: Boolean = true
private var vadDetector: WebRtcVadDetector? = null
private var vadCheckJob: Job? = null
```

**初始化 VAD**：
```kotlin
private fun initializeVad() {
    vadDetector = WebRtcVadDetector(
        mode = WebRtcVadDetector.VadMode.MEDIUM,
        sampleRate = 16000
    )
    if (!vadDetector!!.init()) {
        Log.e(TAG, "Failed to initialize VAD detector")
        vadDetector = null
    }
}
```

**修改 handleTtsMessage**：
```kotlin
private fun handleTtsMessage(message: TtsMessage) {
    when (message.state) {
        "start" -> {
            Log.d(TAG, "TTS start received, stopping recording")
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
```

**VAD 检测循环**：
```kotlin
/**
 * 启动 VAD 检测（在 Speaking 状态下）
 */
private fun startVadDetection() {
    if (vadCheckJob?.isActive == true) return

    vadCheckJob = serviceScope.launch {
        Log.d(TAG, "VAD detection started in Speaking state")

        // 创建一个轻量级的录音器用于 VAD 检测
        val vadBufferSize = 16000 * 10 / 1000 * 2  // 10ms 帧
        val vadAudioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            vadBufferSize * 4
        )

        if (vadAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "VAD AudioRecord initialization failed")
            return@launch
        }

        vadAudioRecord.startRecording()
        val buffer = ShortArray(vadBufferSize / 2)

        try {
            while (_serviceState.value == ServiceState.Speaking && isActive) {
                val read = vadAudioRecord.read(buffer, 0, buffer.size)
                if (read > 0 && vadDetector?.detect(buffer) == true) {
                    Log.i(TAG, "VAD detected speech during TTS playback")
                    onVadDetected()
                    break
                }
                delay(10)  // 10ms 检测间隔
            }
        } finally {
            vadAudioRecord.stop()
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
```

#### 4.2.3 AudioPlayer.kt 新增方法

```kotlin
/**
 * 清空音频队列
 * 用于 VAD 打断时快速清空待播放的音频
 */
fun clearQueue() {
    audioQueue.clear()
    Log.d(TAG, "Audio queue cleared")
}
```

#### 4.2.4 NetworkManager.kt 新增方法

```kotlin
/**
 * 发送 abort 消息
 *
 * @param reason 打断原因（可选）
 */
fun sendAbort(reason: String? = null) {
    val message = AbortRequest(reason = reason)
    sendMessage(json.encodeToString(message))
    Log.d(TAG, "Sent abort message, reason: $reason")
}
```

## 5. 配置与扩展

### 5.1 VAD 灵敏度配置

```kotlin
// 设置 VAD 灵敏度
fun setVadSensitivity(mode: WebRtcVadDetector.VadMode) {
    vadDetector?.release()
    vadDetector = WebRtcVadDetector(mode = mode)
    vadDetector?.init()
}
```

### 5.2 监听模式配置

```kotlin
// 设置监听模式
fun setListeningMode(mode: ListeningMode) {
    listeningMode = mode
    Log.d(TAG, "Listening mode changed to: $mode")
}
```

### 5.3 设置持久化（可选）

```kotlin
// SettingRepository.kt
suspend fun setListeningMode(mode: ListeningMode) {
    set("listening_mode", mode.name)
}

suspend fun getListeningMode(): ListeningMode {
    val modeName = get("listening_mode") ?: ListeningMode.REALTIME.name
    return try {
        ListeningMode.valueOf(modeName)
    } catch (e: Exception) {
        ListeningMode.REALTIME
    }
}
```

## 6. 错误处理

### 6.1 VAD 初始化失败

```kotlin
if (vadDetector == null) {
    Log.w(TAG, "VAD not available, interrupt feature disabled")
    isVadInterruptEnabled = false
}
```

### 6.2 网络断开处理

```kotlin
// 如果在 Speaking 状态下网络断开
if (state == ServiceState.Speaking) {
    audioPlayer.stopPlaying()
    stopVadDetection()
    _serviceState.value = ServiceState.Idle
}
```

### 6.3 Abort 消息发送失败

- 如果发送 abort 失败，仍然执行本地打断
- 记录错误日志，不影响用户体验

## 7. 性能考虑

### 7.1 VAD 检测性能

- WebRTC VAD 是轻量级算法，CPU 占用低
- 10ms 检测间隔，不会造成性能问题
- 独立的 AudioRecord，不影响主录音流程

### 7.2 内存占用

- VAD 检测器占用内存 < 100KB
- 独立的 10ms 音频缓冲区，内存占用可忽略

## 8. 测试计划

### 8.1 功能测试

- [ ] 实时模式下 TTS 结束后自动继续监听
- [ ] VAD 检测到语音时打断 TTS
- [ ] 打断后清空队列并重新监听
- [ ] 手动模式下 TTS 结束后回到 Idle

### 8.2 边界测试

- [ ] VAD 初始化失败时的降级处理
- [ ] 网络断开时的状态清理
- [ ] 快速连续打断的稳定性

### 8.3 性能测试

- [ ] VAD 检测的 CPU 占用
- [ ] 内存泄漏检查
- [ ] 长时间运行的稳定性

## 9. 未来扩展

### 9.1 唤醒词打断

在 Speaking 状态下检测唤醒词并打断（需要唤醒词检测器在 Speaking 状态下运行）。

### 9.2 自动模式

添加 `AUTO_STOP` 模式，VAD 检测静音后自动停止录音。

### 9.3 服务端 AEC

支持服务端回声消除，减少 VAD 误触发。

## 10. 参考资料

- [xiaozhi-esp32 AfeAudioProcessor](file:///d:\project\android\xiaozhi-esp32\main\audio\processors\afe_audio_processor.cc)
- [xiaozhi-esp32 Application](file:///d:\project\android\xiaozhi-esp32\main\application.cc)
- [WebRTC VAD 文档](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/vad/)
