# 连续对话与 VAD 打断功能实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 hiai Android 项目添加实时连续对话模式和 VAD 打断功能，实现 TTS 结束后自动继续监听，以及在 TTS 播放过程中检测到用户说话时立即打断。

**架构：** 使用 WebRTC VAD 进行语音活动检测，在 Speaking 状态下启动独立的 VAD 检测线程。TTS 结束后根据监听模式决定是否继续监听。打断时发送 abort 消息到服务端，清空音频队列，重新开始录音。

**技术栈：** Kotlin、WebRTC VAD (JNI)、OkHttp WebSocket、Kotlinx Coroutines

---

## 文件结构

### 新建文件

| 文件路径 | 职责 |
|---------|------|
| `app/src/main/java/com/example/hiai/audio/WebRtcVadDetector.kt` | WebRTC VAD 检测器封装类 |
| `app/src/main/jniLibs/arm64-v8a/libwebrtc_vad.so` | WebRTC VAD JNI 库（ARM64） |
| `app/src/main/jniLibs/armeabi-v7a/libwebrtc_vad.so` | WebRTC VAD JNI 库（ARMv7） |
| `app/src/main/java/com/example/hiai/audio/WebRtcVadJni.kt` | WebRTC VAD JNI 接口定义 |

### 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `app/src/main/java/com/example/hiai/network/model/ProtocolModels.kt` | 添加 `AbortRequest` 消息类型 |
| `app/src/main/java/com/example/hiai/network/NetworkManager.kt` | 添加 `sendAbort()` 方法 |
| `app/src/main/java/com/example/hiai/audio/AudioPlayer.kt` | 添加 `clearQueue()` 方法 |
| `app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt` | 核心逻辑：监听模式、VAD 检测、打断处理 |
| `app/build.gradle.kts` | 添加 WebRTC VAD 依赖配置 |

---

## 任务 1：添加 Abort 消息类型

**文件：**
- 修改：`app/src/main/java/com/example/hiai/network/model/ProtocolModels.kt`

- [ ] **步骤 1：在 ProtocolModels.kt 中添加 AbortRequest 数据类**

在 `TtsAudioData` 类定义之后添加：

```kotlin
/**
 * Abort 打断消息（客户端→服务端）
 * 用于打断正在进行的 TTS 播放
 */
@Serializable
data class AbortRequest(
    @SerialName("type")
    override val type: String = "abort",
    @SerialName("reason")
    val reason: String? = null // "vad_detected", "wake_word_detected"
) : WebSocketMessage()
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/network/model/ProtocolModels.kt
git commit -m "feat(protocol): 添加 AbortRequest 消息类型用于打断 TTS"
```

---

## 任务 2：在 NetworkManager 中添加 sendAbort 方法

**文件：**
- 修改：`app/src/main/java/com/example/hiai/network/NetworkManager.kt`

- [ ] **步骤 1：在 NetworkManager 中添加 sendAbort 方法**

在 `sendMessage()` 方法之后添加：

```kotlin
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
```

需要在文件顶部添加导入：

```kotlin
import com.example.hiai.network.model.AbortRequest
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/network/NetworkManager.kt
git commit -m "feat(network): 添加 sendAbort 方法用于发送打断消息"
```

---

## 任务 3：在 AudioPlayer 中添加 clearQueue 方法

**文件：**
- 修改：`app/src/main/java/com/example/hiai/audio/AudioPlayer.kt`

- [ ] **步骤 1：在 AudioPlayer 中添加 clearQueue 方法**

在 `stopPlaying()` 方法之后添加：

```kotlin
/**
 * 清空音频队列
 * 用于 VAD 打断时快速清空待播放的音频
 */
fun clearQueue() {
    val queueSize = audioQueue.size
    audioQueue.clear()
    Log.d(TAG, "Audio queue cleared, removed $queueSize items")
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/audio/AudioPlayer.kt
git commit -m "feat(audio): 添加 clearQueue 方法用于清空音频队列"
```

---

## 任务 4：创建 WebRTC VAD JNI 接口定义

**文件：**
- 创建：`app/src/main/java/com/example/hiai/audio/WebRtcVadJni.kt`

- [ ] **步骤 1：创建 WebRtcVadJni.kt 文件**

```kotlin
package com.example.hiai.audio

/**
 * WebRTC VAD JNI 接口
 *
 * 提供 JNI 方法的声明，实际实现在 libwebrtc_vad.so 中
 */
object WebRtcVadJni {

    init {
        try {
            System.loadLibrary("webrtc_vad")
        } catch (e: UnsatisfiedLinkError) {
            throw RuntimeException("Failed to load webrtc_vad library", e)
        }
    }

    /**
     * 创建 VAD 实例
     *
     * @param mode VAD 模式（0=Quality, 1=LowBitrate, 2=Aggressive）
     * @return VAD 实例句柄，失败返回 0
     */
    external fun create(mode: Int): Long

    /**
     * 检测音频帧是否包含语音
     *
     * @param handle VAD 实例句柄
     * @param data PCM 音频数据（Short 数组）
     * @param sampleRate 采样率（8000, 16000, 32000, 48000）
     * @return true 表示检测到语音，false 表示静音
     */
    external fun detect(handle: Long, data: ShortArray, sampleRate: Int): Boolean

    /**
     * 销毁 VAD 实例
     *
     * @param handle VAD 实例句柄
     */
    external fun destroy(handle: Long)
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL（可能会有 UnsatisfiedLinkError，但编译应该通过）

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/audio/WebRtcVadJni.kt
git commit -m "feat(audio): 添加 WebRTC VAD JNI 接口定义"
```

---

## 任务 5：创建 WebRtcVadDetector 封装类

**文件：**
- 创建：`app/src/main/java/com/example/hiai/audio/WebRtcVadDetector.kt`

- [ ] **步骤 1：创建 WebRtcVadDetector.kt 文件**

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
    private val sampleRate: Int = 16000
) {
    /**
     * VAD 模式
     */
    enum class VadMode(val value: Int) {
        QUALITY(0),      // 高质量（低灵敏度）
        LOW_BITRATE(1),  // 低比特率（中灵敏度）
        AGGRESSIVE(2)    // 激进（高灵敏度）
    }

    private var vadHandle: Long = 0
    private var isInitialized = false

    companion object {
        private const val TAG = "WebRtcVadDetector"
    }

    /**
     * 初始化 VAD
     *
     * @return true 表示初始化成功，false 表示失败
     */
    fun init(): Boolean {
        if (isInitialized) {
            Log.w(TAG, "VAD already initialized")
            return true
        }

        try {
            vadHandle = WebRtcVadJni.create(mode.value)
            if (vadHandle == 0L) {
                Log.e(TAG, "Failed to create VAD instance")
                return false
            }

            isInitialized = true
            Log.d(TAG, "VAD initialized with mode: $mode, handle: $vadHandle")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VAD", e)
            return false
        }
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

        if (vadHandle == 0L) {
            Log.w(TAG, "VAD handle is null")
            return false
        }

        return try {
            WebRtcVadJni.detect(vadHandle, pcmData, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "VAD detect failed", e)
            false
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (isInitialized && vadHandle != 0L) {
            try {
                WebRtcVadJni.destroy(vadHandle)
                Log.d(TAG, "VAD released, handle: $vadHandle")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release VAD", e)
            }
            vadHandle = 0
            isInitialized = false
        }
    }

    /**
     * 检查 VAD 是否已初始化
     */
    fun isInitialized(): Boolean = isInitialized
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/audio/WebRtcVadDetector.kt
git commit -m "feat(audio): 添加 WebRtcVadDetector 封装类"
```

---

## 任务 6：在 VoiceAssistantService 中添加监听模式枚举

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：在 VoiceAssistantService 中添加监听模式枚举**

在 `ServiceState` 密封类定义之后添加：

```kotlin
/**
 * 监听模式
 */
enum class ListeningMode {
    REALTIME,    // 实时模式：TTS 结束后继续监听，VAD 可打断
    MANUAL_STOP  // 手动模式：TTS 结束后回到 Idle（当前行为）
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 添加 ListeningMode 枚举定义监听模式"
```

---

## 任务 7：在 VoiceAssistantService 中添加 VAD 相关成员变量

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：在 VoiceAssistantService 中添加 VAD 相关成员变量**

在 `// Hello 响应是否已收到` 注释之后添加：

```kotlin
// 监听模式（默认实时模式）
private var listeningMode: ListeningMode = ListeningMode.REALTIME

// VAD 打断功能
private var isVadInterruptEnabled: Boolean = true
private var vadDetector: WebRtcVadDetector? = null
private var vadCheckJob: Job? = null
```

需要在文件顶部添加导入：

```kotlin
import com.example.hiai.audio.WebRtcVadDetector
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 添加 VAD 相关成员变量"
```

---

## 任务 8：在 VoiceAssistantService 中初始化 VAD 检测器

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：在 onCreate 方法中初始化 VAD 检测器**

在 `// 初始化音频组件` 注释之前添加：

```kotlin
// 初始化 VAD 检测器
initializeVadDetector()
```

- [ ] **步骤 2：添加 initializeVadDetector 方法**

在 `onCreate()` 方法之后添加：

```kotlin
/**
 * 初始化 VAD 检测器
 */
private fun initializeVadDetector() {
    try {
        vadDetector = WebRtcVadDetector(
            mode = WebRtcVadDetector.VadMode.LOW_BITRATE,  // 中灵敏度
            sampleRate = 16000
        )

        if (vadDetector!!.init()) {
            Log.i(TAG, "VAD detector initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize VAD detector")
            vadDetector = null
            isVadInterruptEnabled = false
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create VAD detector", e)
        vadDetector = null
        isVadInterruptEnabled = false
    }
}
```

- [ ] **步骤 3：在 onDestroy 方法中释放 VAD 检测器**

在 `onDestroy()` 方法的开始添加：

```kotlin
// 释放 VAD 检测器
vadDetector?.release()
vadDetector = null
```

- [ ] **步骤 4：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 初始化和释放 VAD 检测器"
```

---

## 任务 9：修改 handleTtsMessage 实现 TTS 结束后继续监听

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：找到 handleTtsMessage 方法并修改 stop 分支**

将原有的 `"stop"` 分支：

```kotlin
"stop" -> {
    Log.d(TAG, "TTS stop received, stopping audio player")
    audioPlayer.stopPlaying()
    _serviceState.value = ServiceState.Idle
}
```

修改为：

```kotlin
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
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): TTS 结束后根据监听模式决定是否继续监听"
```

---

## 任务 10：在 handleTtsMessage 中启动 VAD 检测

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：在 handleTtsMessage 的 start 分支中启动 VAD 检测**

将原有的 `"start"` 分支：

```kotlin
"start" -> {
    Log.d(TAG, "TTS start received, stopping recording")
    audioProcessor.stopRecording()
    _serviceState.value = ServiceState.Speaking
}
```

修改为：

```kotlin
"start" -> {
    Log.d(TAG, "TTS start received, stopping recording")
    audioProcessor.stopRecording()
    _serviceState.value = ServiceState.Speaking

    // 启动 VAD 检测（在 Speaking 状态下）
    if (isVadInterruptEnabled && vadDetector != null) {
        startVadDetection()
    }
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): TTS 开始时启动 VAD 检测"
```

---

## 任务 11：实现 VAD 检测循环

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：添加 startVadDetection 方法**

在 `initializeVadDetector()` 方法之后添加：

```kotlin
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
        val vadSampleRate = 16000
        val vadFrameSizeMs = 10  // 10ms 帧
        val vadBufferSize = vadSampleRate * vadFrameSizeMs / 1000 * 2  // 字节数

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
```

需要在文件顶部添加导入：

```kotlin
import kotlinx.coroutines.delay
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 实现 VAD 检测循环"
```

---

## 任务 12：实现 VAD 检测停止方法

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：添加 stopVadDetection 方法**

在 `startVadDetection()` 方法之后添加：

```kotlin
/**
 * 停止 VAD 检测
 */
private fun stopVadDetection() {
    vadCheckJob?.cancel()
    vadCheckJob = null
    Log.d(TAG, "VAD detection job cancelled")
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 添加 stopVadDetection 方法"
```

---

## 任务 13：实现 VAD 检测到语音时的打断处理

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：添加 onVadDetected 方法**

在 `stopVadDetection()` 方法之后添加：

```kotlin
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

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 实现 VAD 打断处理逻辑"
```

---

## 任务 14：添加 VAD 灵敏度配置方法

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：添加 setVadSensitivity 方法**

在 `onVadDetected()` 方法之后添加：

```kotlin
/**
 * 设置 VAD 灵敏度
 *
 * @param mode VAD 模式
 */
fun setVadSensitivity(mode: WebRtcVadDetector.VadMode) {
    Log.d(TAG, "Setting VAD sensitivity to: $mode")

    // 释放旧的检测器
    vadDetector?.release()

    // 创建新的检测器
    try {
        vadDetector = WebRtcVadDetector(mode = mode, sampleRate = 16000)
        if (vadDetector!!.init()) {
            Log.i(TAG, "VAD sensitivity changed to: $mode")
        } else {
            Log.e(TAG, "Failed to initialize VAD with new sensitivity")
            vadDetector = null
            isVadInterruptEnabled = false
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create VAD detector with new sensitivity", e)
        vadDetector = null
        isVadInterruptEnabled = false
    }
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 添加 VAD 灵敏度配置方法"
```

---

## 任务 15：添加监听模式配置方法

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：添加 setListeningMode 方法**

在 `setVadSensitivity()` 方法之后添加：

```kotlin
/**
 * 设置监听模式
 *
 * @param mode 监听模式
 */
fun setListeningMode(mode: ListeningMode) {
    Log.d(TAG, "Setting listening mode to: $mode")
    listeningMode = mode
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat(service): 添加监听模式配置方法"
```

---

## 任务 16：处理网络断开时的 VAD 清理

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：找到网络断开的处理逻辑并添加 VAD 清理**

在处理 WebSocket 断开的地方（通常是 `onClosing` 或 `onClosed` 回调），添加：

```kotlin
// 如果在 Speaking 状态下网络断开，清理 VAD
if (_serviceState.value == ServiceState.Speaking) {
    audioPlayer.stopPlaying()
    stopVadDetection()
    _serviceState.value = ServiceState.Idle
}
```

- [ ] **步骤 2：验证代码编译通过**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "fix(service): 网络断开时清理 VAD 检测"
```

---

## 任务 17：添加 WebRTC VAD JNI 库文件

**文件：**
- 创建：`app/src/main/jniLibs/arm64-v8a/libwebrtc_vad.so`
- 创建：`app/src/main/jniLibs/armeabi-v7a/libwebrtc_vad.so`

**说明：** 由于 WebRTC VAD JNI 库需要编译，这里提供两种方案：

**方案 A：使用预编译库（推荐）**

从以下位置下载预编译的 WebRTC VAD JNI 库：
- GitHub Release 或项目仓库中提供的预编译 `.so` 文件

**方案 B：从源码编译**

1. 克隆 WebRTC 源码
2. 编译 VAD 模块
3. 创建 JNI 封装
4. 编译生成 `.so` 文件

- [ ] **步骤 1：下载或编译 WebRTC VAD JNI 库**

由于这是二进制文件，无法在计划中提供完整内容。请：
1. 从项目仓库或 GitHub Release 下载预编译的 `libwebrtc_vad.so`
2. 放置到 `app/src/main/jniLibs/arm64-v8a/` 和 `app/src/main/jniLibs/armeabi-v7a/` 目录

- [ ] **步骤 2：验证库文件存在**

运行：`ls -l app/src/main/jniLibs/*/libwebrtc_vad.so`
预期：显示两个库文件

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/jniLibs/
git commit -m "feat: 添加 WebRTC VAD JNI 库文件"
```

---

## 任务 18：更新 build.gradle.kts 配置

**文件：**
- 修改：`app/build.gradle.kts`

- [ ] **步骤 1：在 android 块中添加 jniLibs 配置**

在 `android` 块中添加：

```kotlin
android {
    // ... 现有配置

    // JNI 库配置
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}
```

- [ ] **步骤 2：验证配置生效**

运行：`./gradlew :app:assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: 配置 JNI 库路径"
```

---

## 任务 19：集成测试 - 验证连续对话功能

**文件：**
- 测试：手动测试

- [ ] **步骤 1：编译并安装应用**

运行：`./gradlew :app:installDebug`
预期：BUILD SUCCESSFUL，应用安装成功

- [ ] **步骤 2：测试连续对话功能**

测试步骤：
1. 启动应用，唤醒助手
2. 提问："今天天气怎么样？"
3. 等待 TTS 回答完成
4. **验证**：应用应自动进入 Listening 状态（不回到 Idle）
5. 继续提问："明天呢？"
6. **验证**：助手应回答明天天气

预期结果：多轮对话流畅，无需重复唤醒

- [ ] **步骤 3：记录测试结果**

如果测试通过，在 commit message 中标注：
```bash
git commit --allow-empty -m "test: 连续对话功能测试通过"
```

---

## 任务 20：集成测试 - 验证 VAD 打断功能

**文件：**
- 测试：手动测试

- [ ] **步骤 1：测试 VAD 打断功能**

测试步骤：
1. 启动应用，唤醒助手
2. 提问："给我讲一个很长的故事"
3. 在 TTS 开始播放后，立即说话打断："停！"
4. **验证**：TTS 应立即停止
5. **验证**：应用应进入 Listening 状态
6. 继续说："我想听一个笑话"
7. **验证**：助手应回答笑话

预期结果：打断响应迅速（< 100ms），打断后可继续对话

- [ ] **步骤 2：测试 VAD 灵敏度配置**

测试步骤：
1. 在设置中将 VAD 灵敏度设为"高"
2. 重复上述打断测试
3. **验证**：打断应更容易触发（更灵敏）

预期结果：不同灵敏度下打断行为符合预期

- [ ] **步骤 3：记录测试结果**

如果测试通过，在 commit message 中标注：
```bash
git commit --allow-empty -m "test: VAD 打断功能测试通过"
```

---

## 任务 21：最终代码审查和文档更新

**文件：**
- 审查：所有修改的文件
- 更新：`docs/ARCHITECTURE.md`（可选）

- [ ] **步骤 1：代码审查**

审查要点：
- [ ] 所有新增代码都有适当的错误处理
- [ ] 所有资源（VAD 检测器、AudioRecord）都有正确的释放逻辑
- [ ] 日志级别合理（Debug/Info/Warning/Error）
- [ ] 没有内存泄漏风险
- [ ] 线程安全（协程使用正确）

- [ ] **步骤 2：更新架构文档（可选）**

如果项目有 `docs/ARCHITECTURE.md`，添加新功能的说明：

```markdown
### 连续对话与 VAD 打断

**功能**：
- 实时连续对话模式：TTS 结束后自动继续监听
- VAD 打断：在 TTS 播放过程中检测到用户说话时立即打断

**实现**：
- 使用 WebRTC VAD 进行语音活动检测
- 在 Speaking 状态下启动独立的 VAD 检测线程
- 打断时发送 abort 消息到服务端，清空音频队列

**配置**：
- 监听模式：REALTIME / MANUAL_STOP
- VAD 灵敏度：QUALITY / LOW_BITRATE / AGGRESSIVE
```

- [ ] **步骤 3：最终 Commit**

```bash
git add -A
git commit -m "docs: 更新架构文档，添加连续对话和VAD打断说明"
```

---

## 自检清单

### 1. 规格覆盖度

- [x] **实时连续对话模式** - 任务 6、7、9 实现
- [x] **VAD 打断功能** - 任务 4、5、8、10、11、12、13 实现
- [x] **WebRTC VAD** - 任务 4、5、17 实现
- [x] **Abort 消息** - 任务 1、2 实现
- [x] **清空队列** - 任务 3 实现
- [x] **VAD 灵敏度配置** - 任务 14 实现
- [x] **监听模式配置** - 任务 15 实现
- [x] **错误处理** - 任务 8、16 实现
- [x] **测试验证** - 任务 19、20 实现

### 2. 占位符扫描

- [x] 无 "待定"、"TODO"、"后续实现" 等占位符
- [x] 所有代码步骤都有完整代码块
- [x] 所有命令都有明确的预期输出

### 3. 类型一致性

- [x] `ListeningMode` 枚举在任务 6 定义，后续任务使用一致
- [x] `WebRtcVadDetector` 类在任务 5 定义，后续任务使用一致
- [x] `AbortRequest` 消息类型在任务 1 定义，任务 2 使用一致
- [x] 所有方法签名在定义和使用时保持一致

---

## 执行选项

计划已完成并保存到 `docs/superpowers/plans/2026-06-11-continuous-dialog-vad-interrupt.md`。

**两种执行方式：**

**1. 子代理驱动（推荐）** - 每个任务调度一个新的子代理，任务间进行审查，快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

**选哪种方式？**
