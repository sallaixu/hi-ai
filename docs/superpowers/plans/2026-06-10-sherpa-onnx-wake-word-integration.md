# Sherpa-ONNX 唤醒词检测集成实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 Sherpa-ONNX 唤醒词检测功能集成到小智 Android App，实现桌面模式下的离线语音唤醒。

**架构：** WakeWordDetector 封装 Sherpa-ONNX KeywordSpotter，在 VoiceAssistantService 中按桌面模式启停。唤醒词触发后通过 WebSocket 发送 listen 消息，开始录音上传进入对话状态。

**技术栈：** Sherpa-ONNX Android SDK (1.10.30) + AudioRecord (16kHz 单声道) + Kotlin Coroutines

---

## 文件结构

### 新增文件
| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/example/hiai/audio/WakeWordDetector.kt` | 唤醒词检测器封装类，管理 Sherpa-ONNX 和音频采集 |
| `app/src/main/assets/keywords.txt` | 默认唤醒词配置文件 |
| `app/src/main/assets/sherpa-onnx-models/` | 模型文件目录（用户提供） |

### 修改文件
| 文件 | 修改内容 |
|------|----------|
| `app/build.gradle.kts` | 启用 sherpa-onnx 依赖 |
| `app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt` | 集成 WakeWordDetector，添加桌面模式启停逻辑 |

---

## 任务分解

### 任务 1：启用 Sherpa-ONNX 依赖

**文件：**
- 修改：`app/build.gradle.kts`

- [ ] **步骤 1：取消 sherpa-onnx 依赖注释**

修改 `app/build.gradle.kts` 第 42-43 行，取消注释：

```kotlin
// 唤醒词检测 - Sherpa-ONNX
implementation(libs.sherpa.onnx)
```

- [ ] **步骤 2：同步 Gradle 验证依赖**

运行：`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
预期：输出中包含 `sherpa-onnx` 依赖

- [ ] **步骤 3：Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: 启用 sherpa-onnx 依赖"
```

---

### 任务 2：创建唤醒词配置文件

**文件：**
- 创建：`app/src/main/assets/keywords.txt`

- [ ] **步骤 1：创建 keywords.txt**

创建文件 `app/src/main/assets/keywords.txt`：

```
你好小智
嘿你好呀
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/assets/keywords.txt
git commit -m "feat: 添加默认唤醒词配置文件"
```

---

### 任务 3：实现 WakeWordDetector 类

**文件：**
- 创建：`app/src/main/java/com/example/hiai/audio/WakeWordDetector.kt`

- [ ] **步骤 1：创建 WakeWordDetector 基础结构**

创建文件 `app/src/main/java/com/example/hiai/audio/WakeWordDetector.kt`：

```kotlin
package com.example.hiai.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * 唤醒词检测器
 * 
 * 使用 Sherpa-ONNX 进行本地离线唤醒词检测
 * 
 * @param context Android Context，用于访问 assets 和文件系统
 * @param onDetected 检测到唤醒词时的回调
 */
class WakeWordDetector(
    private val context: Context,
    private val onDetected: (keyword: String) -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val MODEL_DIR = "sherpa-onnx-models"
    }
    
    private var spotter: KeywordSpotter? = null
    private var audioRecord: AudioRecord? = null
    private var detectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isRunning = false
    
    /**
     * 初始化检测器
     * 将模型文件从 assets 复制到内部存储并创建 KeywordSpotter
     */
    fun init(): Boolean {
        try {
            // 复制模型文件到内部存储
            val modelPath = copyModelFiles()
            if (modelPath == null) {
                Log.e(TAG, "Failed to copy model files")
                return false
            }
            
            // 创建 KeywordSpotter 配置
            val config = KeywordSpotterConfig(
                modelConfig = KeywordSpotterConfig.ModelConfig(
                    transducer = KeywordSpotterConfig.ModelConfig.TransducerConfig(
                        encoder = "$modelPath/encoder-epoch-12-avg-1-chunk-16-left-128.onnx",
                        decoder = "$modelPath/decoder-epoch-12-avg-1-chunk-16-left-128.onnx",
                        joiner = "$modelPath/joiner-epoch-12-avg-1-chunk-16-left-128.onnx",
                        tokens = "$modelPath/tokens.txt"
                    )
                ),
                keywordsFile = copyKeywordsFile() ?: ""
            )
            
            spotter = KeywordSpotter(config)
            Log.i(TAG, "WakeWordDetector initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WakeWordDetector", e)
            return false
        }
    }
    
    /**
     * 启动唤醒词检测
     */
    fun start() {
        if (spotter == null) {
            Log.e(TAG, "Spotter not initialized")
            return
        }
        
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }
        
        // 创建 AudioRecord
        val channelConfig = if (CHANNELS == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }
        
        audioRecord?.startRecording()
        isRunning = true
        
        // 启动检测循环
        detectionJob = scope.launch {
            val buffer = ShortArray(bufferSize / 2)
            
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // 送入检测器
                    val result = spotter?.detect(buffer)
                    if (result != null && result.keyword.isNotEmpty()) {
                        Log.i(TAG, "Detected keyword: ${result.keyword}")
                        onDetected(result.keyword)
                    }
                }
            }
        }
        
        Log.i(TAG, "WakeWordDetector started")
    }
    
    /**
     * 停止唤醒词检测
     */
    fun stop() {
        isRunning = false
        detectionJob?.cancel()
        detectionJob = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        Log.i(TAG, "WakeWordDetector stopped")
    }
    
    /**
     * 更新关键词列表
     */
    fun updateKeywords(keywords: List<String>) {
        // 将关键词写入临时文件
        val keywordsFile = File(context.cacheDir, "keywords.txt")
        keywordsFile.writeText(keywords.joinToString("\n"))
        
        // 重新创建 spotter
        // TODO: Sherpa-ONNX 可能需要重新初始化
        Log.i(TAG, "Keywords updated: $keywords")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        spotter?.close()
        spotter = null
        scope.cancel()
        Log.i(TAG, "WakeWordDetector released")
    }
    
    /**
     * 复制模型文件到内部存储
     */
    private fun copyModelFiles(): String? {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        
        try {
            val assetsFiles = context.assets.list(MODEL_DIR) ?: return null
            
            for (fileName in assetsFiles) {
                val destFile = File(modelDir, fileName)
                if (!destFile.exists()) {
                    context.assets.open("$MODEL_DIR/$fileName").use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Copied model file: $fileName")
                }
            }
            
            return modelDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model files", e)
            return null
        }
    }
    
    /**
     * 复制关键词文件到内部存储
     */
    private fun copyKeywordsFile(): String? {
        try {
            val keywordsFile = File(context.filesDir, "keywords.txt")
            if (!keywordsFile.exists()) {
                context.assets.open("keywords.txt").use { input ->
                    FileOutputStream(keywordsFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied keywords file")
            }
            return keywordsFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy keywords file", e)
            return null
        }
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`./gradlew :app:compileDebugKotlin`
预期：编译成功，无错误

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/audio/WakeWordDetector.kt
git commit -m "feat: 实现 WakeWordDetector 唤醒词检测器"
```

---

### 任务 4：集成 WakeWordDetector 到 VoiceAssistantService

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：添加 WakeWordDetector 成员变量**

在 `VoiceAssistantService.kt` 第 50 行附近，添加成员变量：

```kotlin
// 唤醒词检测器
private var wakeWordDetector: WakeWordDetector? = null
```

- [ ] **步骤 2：在 onCreate 中初始化 WakeWordDetector**

在 `onCreate()` 方法末尾（约第 70 行）添加：

```kotlin
// 初始化唤醒词检测器
wakeWordDetector = WakeWordDetector(this) { keyword ->
    onWakeWordDetected(keyword)
}
wakeWordDetector?.init()
```

- [ ] **步骤 3：添加唤醒词触发回调方法**

在类末尾（约第 230 行）添加：

```kotlin
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
```

- [ ] **步骤 4：修改 toggleDeskMode 方法启动/停止唤醒词检测**

修改 `toggleDeskMode()` 方法（约第 195 行）：

```kotlin
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
```

- [ ] **步骤 5：在 onDestroy 中释放 WakeWordDetector**

修改 `onDestroy()` 方法（约第 60 行）：

```kotlin
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
```

- [ ] **步骤 6：验证编译**

运行：`./gradlew :app:compileDebugKotlin`
预期：编译成功，无错误

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat: 集成唤醒词检测到 VoiceAssistantService"
```

---

### 任务 5：验证完整功能

**文件：**
- 无新增

- [ ] **步骤 1：构建 APK**

运行：`./gradlew :app:assembleDebug`
预期：构建成功，生成 APK

- [ ] **步骤 2：检查 APK 内容**

运行：`./gradlew :app:assembleDebug` 后检查 APK 中是否包含模型文件：
```bash
# 查看 APK 内容
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep sherpa
```
预期：输出包含 `sherpa-onnx-models/` 目录下的模型文件

- [ ] **步骤 3：最终 Commit（如有未提交的更改）**

```bash
git status
# 如有未提交的更改，添加并提交
```

---

## 完成检查

- [ ] WakeWordDetector 类可正常初始化
- [ ] 模型文件正确复制到内部存储
- [ ] 桌面模式启停唤醒词检测正常
- [ ] 唤醒词触发后正确发送 listen 消息
- [ ] 唤醒词触发后进入 LISTENING 状态
- [ ] 资源释放正常

---

**文档结束**