# Sherpa-ONNX 唤醒词检测集成规格说明

**创建日期：** 2026-06-10
**版本：** 1.0
**状态：** 待审查

---

## 1. 概述

### 1.1 目标
将 Sherpa-ONNX 唤醒词检测功能集成到小智 Android App，实现桌面模式下的离线语音唤醒。

### 1.2 核心需求
- 使用 Sherpa-ONNX 进行本地离线唤醒词检测
- 仅桌面模式启用唤醒词检测
- WebSocket 常驻连接，唤醒后直接进入对话
- 支持用户自定义唤醒词

### 1.3 技术选型
- **模型：** `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`（中英文混合，~3MB）
- **库：** `com.k2fsa.sherpa.onnx:sherpa-onnx:1.10.30`
- **唤醒词配置：** 关键词文件方式（keywords.txt）

---

## 2. 架构设计

### 2.1 组件关系

```
┌─────────────────────────────────────────────────────────────────┐
│                  VoiceAssistantService                          │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  NetworkManager（WebSocket 常驻连接）                     │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  WakeWordDetector（桌面模式时启动）                       │   │
│  │  ┌───────────────────────────────────────────────────┐  │   │
│  │  │  KeywordSpotter (Sherpa-ONNX)                      │  │   │
│  │  │  - 模型: sherpa-onnx-kws-zipformer-zh-en-3M        │  │   │
│  │  │  - 关键词: keywords.txt                            │  │   │
│  │  └───────────────────────────────────────────────────┘  │   │
│  │  ┌───────────────────────────────────────────────────┐  │   │
│  │  │  AudioRecord (16kHz, 单声道)                       │  │   │
│  │  └───────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  AudioProcessor（录音上传 + 播放）                        │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 状态机

```
[IDLE] ──唤醒词检测──► [LISTENING] ──STT结束──► [THINKING]
  ▲                         │                      │
  │                         │                      │
  │                    上传音频                 AI处理
  │                         │                      │
  │                         ▼                      ▼
  │                   [SPEAKING] ◄──────────── TTS流
  │                         │
  └───────── TTS结束 ───────┘
```

### 2.3 唤醒词检测生命周期

```
进入桌面模式 → 启动 WakeWordDetector → 开始监听
                                        │
                                        ▼
                              检测到唤醒词 → 回调通知
                                        │
                                        ▼
                              sendListen("start") → 开始录音上传
                                        │
                                        ▼
                              进入 LISTENING 状态

退出桌面模式 → 停止 WakeWordDetector → 释放资源
```

---

## 3. 组件设计

### 3.1 WakeWordDetector 类

**职责：**
- 封装 Sherpa-ONNX KeywordSpotter
- 管理音频采集和检测
- 提供唤醒词回调

**接口：**
```kotlin
class WakeWordDetector(
    context: Context,
    private val onDetected: (keyword: String) -> Unit
) {
    /**
     * 启动唤醒词检测
     */
    fun start()

    /**
     * 停止唤醒词检测
     */
    fun stop()

    /**
     * 更新关键词列表
     */
    fun updateKeywords(keywords: List<String>)

    /**
     * 释放资源
     */
    fun release()
}
```

**内部实现：**
1. 从 assets 复制模型文件到内部存储
2. 创建 KeywordSpotter 配置
3. 创建 AudioRecord 采集音频
4. 循环读取音频帧并送入检测器
5. 检测到关键词时触发回调

### 3.2 VoiceAssistantService 集成

**新增成员：**
```kotlin
class VoiceAssistantService : Service() {
    // 唤醒词检测器
    private var wakeWordDetector: WakeWordDetector? = null

    // 桌面模式状态
    private val _isDeskMode = MutableStateFlow(false)
    val isDeskMode: StateFlow<Boolean> = _isDeskMode.asStateFlow()
}
```

**新增方法：**
```kotlin
/**
 * 进入桌面模式
 */
fun startDeskMode() {
    _isDeskMode.value = true
    // 启动唤醒词检测
    initWakeWordDetector()
    wakeWordDetector?.start()
}

/**
 * 退出桌面模式
 */
fun stopDeskMode() {
    _isDeskMode.value = false
    // 停止唤醒词检测
    wakeWordDetector?.stop()
}

/**
 * 唤醒词触发回调
 */
private fun onWakeWordDetected(keyword: String) {
    // 发送 listen 消息
    networkManager.sendListen(state = "start", text = keyword)
    // 进入 LISTENING 状态
    _serviceState.value = ServiceState.Listening
    // 开始录音并上传
    startRecordingAndUpload()
}
```

---

## 4. 文件结构

### 4.1 新增文件

| 文件路径 | 说明 |
|----------|------|
| `app/src/main/java/com/example/hiai/audio/WakeWordDetector.kt` | 唤醒词检测器封装类 |
| `app/src/main/assets/sherpa-onnx-models/` | 模型文件目录 |
| `app/src/main/assets/sherpa-onnx-models/encoder-epoch-12-avg-1-chunk-16-left-128.onnx` | 编码器模型 |
| `app/src/main/assets/sherpa-onnx-models/decoder-epoch-12-avg-1-chunk-16-left-128.onnx` | 解码器模型 |
| `app/src/main/assets/sherpa-onnx-models/joiner-epoch-12-avg-1-chunk-16-left-128.onnx` | 联合器模型 |
| `app/src/main/assets/sherpa-onnx-models/tokens.txt` | 词表文件 |
| `app/src/main/assets/keywords.txt` | 默认唤醒词文件 |

### 4.2 修改文件

| 文件路径 | 修改内容 |
|----------|----------|
| `app/build.gradle.kts` | 添加 sherpa-onnx 依赖 |
| `app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt` | 集成唤醒词检测 |

---

## 5. 配置

### 5.1 默认唤醒词

`keywords.txt` 内容：
```
你好小智
嘿你好呀
```

### 5.2 音频参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 采样率 | 16000 Hz | 与服务端一致 |
| 声道数 | 1 | 单声道 |
| 采样格式 | 16bit PCM | |
| 帧大小 | 16 samples | Sherpa-ONNX 要求 |

### 5.3 检测参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 置信度阈值 | 0.5 | 可调整 |
| 最大关键词数 | 10 | |

---

## 6. 依赖

### 6.1 Gradle 配置

`app/build.gradle.kts`:
```kotlin
dependencies {
    // 唤醒词检测
    implementation(libs.sherpa.onnx)
}
```

`gradle/libs.versions.toml`:
```toml
[versions]
sherpaOnnx = "1.10.30"

[libraries]
sherpa-onnx = { module = "com.k2fsa.sherpa.onnx:sherpa-onnx", version.ref = "sherpaOnnx" }
```

---

## 7. 错误处理

| 场景 | 处理方式 |
|------|----------|
| 模型文件缺失 | 日志警告，禁用唤醒词检测 |
| AudioRecord 初始化失败 | 日志错误，通知 UI |
| 检测器初始化失败 | 日志错误，禁用唤醒词检测 |
| 内存不足 | 释放并重建检测器 |

---

## 8. 测试要点

### 8.1 单元测试
- WakeWordDetector 初始化和释放
- 关键词更新功能

### 8.2 集成测试
- 桌面模式切换时唤醒词检测启停
- 唤醒词触发后进入 LISTENING 状态
- WebSocket 消息发送正确

### 8.3 手动测试
- 在安静环境下测试唤醒词识别
- 在嘈杂环境下测试误唤醒率
- 长时间运行测试内存占用

---

## 9. 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 唤醒延迟 | < 500ms | 从说完唤醒词到触发 |
| CPU 占用 | < 15% | 桌面模式待机时 |
| 内存占用 | < 100MB | 模型加载后 |
| 误唤醒率 | < 1次/小时 | 安静环境 |

---

**文档结束**
