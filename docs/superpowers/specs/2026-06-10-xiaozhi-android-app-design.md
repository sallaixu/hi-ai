# 小智 Android App 设计规格说明

**创建日期：** 2026-06-10  
**版本：** 1.0  
**状态：** 待审查

---

## 1. 项目概述

### 1.1 项目背景
基于开源项目 `xiaozhi-esp32-server` 开发 Android 客户端，实现 24 小时语音助手功能。原服务端主要面向 ESP32 硬件设备，本项目将其扩展为手机 App 形态。

### 1.2 核心功能
- **语音唤醒**：本地离线唤醒词检测（Sherpa-ONNX）
- **语音对话**：全双工语音交流，支持打断
- **24 小时常驻**：后台服务持续运行
- **桌面助手模式**：屏幕常亮，全屏沉浸式交互

### 1.3 目标用户
需要将手机作为固定桌面助手使用的用户（类似智能音箱形态）

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI 层 (Compose)                           │
├─────────────────────────────────────────────────────────────────┤
│  MainActivity                                                   │
│  ├── DesktopModeScreen（桌面模式 - 全屏）                        │
│  ├── NormalModeScreen（普通模式 - 设置/记录）                    │
│  └── OverlayScreen（悬浮窗/锁屏）                                │
├─────────────────────────────────────────────────────────────────┤
│  ViewModel 层                                                    │
│  ├── VoiceAssistantViewModel（核心状态管理）                     │
│  ├── SettingsViewModel（配置管理）                               │
│  └── ChatHistoryViewModel（历史记录）                            │
├─────────────────────────────────────────────────────────────────┤
│  Domain 层                                                       │
│  ├── VoiceAssistant（领域服务）                                  │
│  ├── WakeWordDetector（唤醒词检测）                              │
│  └── AudioProcessor（音频处理）                                  │
├─────────────────────────────────────────────────────────────────┤
│  Data 层                                                         │
│  ├── Repository（数据仓库）                                      │
│  ├── Network（网络：WebSocket/OTA）                              │
│  ├── LocalDB（Room：聊天记录/配置）                              │
│  └── AudioCodec（Opus 编解码）                                   │
├─────────────────────────────────────────────────────────────────┤
│  Service 层                                                      │
│  ├── VoiceAssistantService（前台服务 - 24h 常驻）                 │
│  └── WakeWordService（唤醒词检测服务）                           │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖关系

```
UI ←→ ViewModel ←→ Domain ←→ Data ←→ Service
                          ↑
                    (通过回调/事件总线)
```

---

## 3. 核心组件设计

### 3.1 语音助手服务 (VoiceAssistantService)

**职责：**
- 24 小时后台常驻（前台服务 + 通知栏）
- 管理 WebSocket 连接生命周期
- 协调唤醒词检测与对话状态

**关键方法：**
```kotlin
class VoiceAssistantService : Service() {
    fun startDesktopMode()      // 进入桌面模式
    fun stopDesktopMode()       // 退出桌面模式
    fun toggleWakeWordDetection(enable: Boolean)
    fun sendAudioData(pcmData: ByteArray)
    fun disconnect()
}
```

**保活策略：**
- 前台服务（Foreground Service）+ 持久通知
- 通知栏显示"语音助手运行中"，点击可返回 App
- Android 12+ 需声明 `FOREGROUND_SERVICE_TYPE_MICROPHONE`

### 3.2 唤醒词检测器 (WakeWordDetector)

**技术选型：** Sherpa-ONNX Android

**职责：**
- 本地离线关键词检测
- 从服务端同步唤醒词配置
- 触发后通知服务建立连接

**模型配置：**
- 模型：`sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`
- 采样率：16kHz
- 输入：单声道 PCM
- 输出：检测到的关键词文本

**接口：**
```kotlin
interface WakeWordDetector {
    fun startListening(callback: (String) -> Unit)
    fun stopListening()
    fun updateKeywords(keywords: List<String>)
    fun release()
}
```

### 3.3 音频处理器 (AudioProcessor)

**职责：**
- 麦克风录音（PCM 16kHz 单声道）
- PCM ↔ Opus 编解码
- 音频流式传输

**技术栈：**
- 录音：`AudioRecord` (Android API)
- Opus 编码：`opus-android` 库
- 播放：`AudioTrack` 或 `ExoPlayer`

**数据流：**
```
麦克风 → AudioRecord → PCM(16bit, 16kHz) 
                     → OpusEncoder → Opus 帧 → WebSocket
                     
WebSocket → Opus 帧 → OpusDecoder → PCM → AudioTrack → 扬声器
```

### 3.4 网络管理器 (NetworkManager)

**职责：**
- OTA HTTP 请求获取 WebSocket 地址
- WebSocket 连接管理
- 协议消息处理

**通信流程：**
```
1. POST /ota → 获取 { websocket: { url, token } }
2. WebSocket Connect → 发送 hello 消息
3. 双向音频流 + JSON 控制消息
```

**消息类型：**
| 类型 | 方向 | 说明 |
|------|------|------|
| `hello` | 双向 | 握手消息，交换设备信息 |
| `listen` | 客户端→服务端 | 控制聆听状态 (detect/start/stop) |
| `tts` | 服务端→客户端 | TTS 音频流控制 |
| `stt` | 服务端→客户端 | 语音识别结果 |
| `audio` | 双向 | Opus 二进制音频帧 |

---

## 4. UI 设计

### 4.1 桌面模式（Desktop Mode）

**触发方式：**
- 设置页开启"桌面助手模式"
- 或快捷开关一键进入

**界面元素：**
```
┌─────────────────────────────────┐
│                                 │
│        [Live2D/波形动画]         │
│                                 │
│    待机/聆听/思考/播放 状态指示    │
│                                 │
│        [实时字幕区域]            │
│                                 │
│    [唤醒词按钮] [设置] [退出]     │
│                                 │
└─────────────────────────────────┘
```

**行为：**
- 屏幕常亮（`FLAG_KEEP_SCREEN_ON`）
- 自动进入唤醒词监听
- 唤醒后显示 AI 响应内容（字幕/动画）
- 支持语音打断

### 4.2 普通模式（Normal Mode）

**界面结构：**
- **首页**：对话状态 + 快捷操作
- **设置页**：服务器配置、设备绑定、唤醒词设置
- **聊天记录**：历史对话列表 + 详情

### 4.3 状态机

```
[IDLE] ──唤醒词──► [WAKING] ──hello ok──► [LISTENING]
  ▲                                         │
  │                                      上传音频
  │                                         │
  │                                    [THINKING]
  │                                         │
  │                                    [PLAYING] ◄──TTS 流
  │                                         │
  └──────────── 用户打断 ────────────────────┘
```

---

## 5. 数据设计

### 5.1 本地数据库（Room）

**表结构：**

**chat_history（聊天记录表）**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| session_id | String | 会话 ID |
| role | String | user/assistant |
| content | String | 内容文本 |
| timestamp | Long | 时间戳 |
| audio_url | String? | 音频文件路径（可选） |

**app_settings（配置表）**
| 字段 | 类型 | 说明 |
|------|------|------|
| key | String | 配置键 |
| value | String | 配置值 |
| updated_at | Long | 更新时间 |

### 5.2 配置项

| Key | 默认值 | 说明 |
|-----|--------|------|
| `server_url` | "" | 服务端 OTA 地址 |
| `device_id` | 自动生成 | 设备 ID |
| `device_mac` | 自动生成 | 设备 MAC |
| `token` | "" | 认证 Token |
| `wake_words` | ["你好小智", "嘿你好呀"] | 唤醒词列表 |
| `desktop_mode` | false | 是否桌面模式 |
| `enable_wake_word` | true | 是否启用唤醒词检测 |

---

## 6. 权限需求

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

---

## 7. 关键技术点

### 7.1 Opus 编解码

**依赖：**
```kotlin
implementation("io.github.jaredsburrows:opus:1.3.1") // 示例版本
```

**编码参数：**
- 采样率：16000 Hz
- 声道数：1（单声道）
- 帧大小：60ms（960 样本）
- 比特率：24000 bps

### 7.2 Sherpa-ONNX 集成

**依赖：**
```kotlin
implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.10.0") // 示例版本
```

**模型文件放置：**
```
app/src/main/assets/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01/
├── encoder.onnx
├── decoder.onnx
├── joiner.onnx
└── tokens.txt
```

### 7.3 前台服务实现

```kotlin
class VoiceAssistantService : Service() {
    override fun onCreate() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音助手运行中")
            .setSmallIcon(R.drawable.ic_assistant)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}
```

---

## 8. 测试策略

### 8.1 单元测试
- WakeWordDetector 关键词检测准确性
- AudioProcessor 编解码正确性
- NetworkManager 消息解析

### 8.2 集成测试
- WebSocket 完整通信流程
- 唤醒→对话→打断完整链路
- 桌面模式切换

### 8.3 性能测试
- 唤醒响应延迟（目标：<500ms）
- 音频流连续性（无卡顿）
- 电池消耗（24 小时待机）

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| Sherpa-ONNX Android 兼容性 | 高 | 提前在目标设备测试，准备备选方案 |
| Opus 编码延迟 | 中 | 使用 60ms 帧大小，优化缓冲区 |
| 后台被杀 | 高 | 前台服务 + 忽略电池优化 + 厂商适配 |
| 音频焦点冲突 | 中 | 正确处理 AudioFocus 请求 |

---

## 10. 里程碑

| 阶段 | 内容 | 预计工时 |
|------|------|----------|
| M1 | 项目搭建 + 基础架构 | 2 天 |
| M2 | AudioProcessor + Opus 编解码 | 2 天 |
| M3 | WakeWordDetector 集成 | 2 天 |
| M4 | NetworkManager + WebSocket | 2 天 |
| M5 | VoiceAssistantService | 2 天 |
| M6 | UI 实现（桌面模式 + 普通模式） | 3 天 |
| M7 | 联调 + 测试 + 优化 | 3 天 |
| **总计** | | **16 天** |

---

## 11. 开放问题

1. **服务端 API 版本兼容性**：需确认服务端是否支持多客户端并发
2. **唤醒词配置同步**：是否需要从服务端拉取，还是本地维护
3. **多设备支持**：是否支持一个 App 绑定多个设备

---

**文档结束**
