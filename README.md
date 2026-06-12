# HiAI - AI 语音助手

一款基于 Android 的 AI 语音助手应用，支持离线唤醒词检测、WebSocket 实时通信、Opus 音频编解码和沉浸式桌面模式。兼容 [小智](https://xiaozhi.me/) 服务端协议，可作为小智 AI 的 Android 客户端使用。

## 功能概览

| 功能模块 | 说明 |
|---------|------|
| 🎙️ 离线唤醒词 | 基于 Sherpa-ONNX 的本地唤醒词检测，支持自定义唤醒词 |
| 🌐 WebSocket 通信 | OTA 注册 + WebSocket 双向通信，支持 Hello 握手和动态音频参数协商 |
| 🔊 Opus 编解码 | Concentus 纯 Java Opus 实现，16kHz/单声道 PCM↔Opus 转换 |
| 🎧 TTS 音频播放 | AudioTrack 流式播放，支持音频焦点管理和 OPPO/ColorOS 兼容 |
| 🗣️ VAD 打断 | WebRTC VAD 语音活动检测，支持实时模式下的 TTS 打断 |
| 🖥️ 桌面模式 | 全屏沉浸式黑底界面，万年历时钟 + TTS 消息滚动 + 防烧屏策略 |
| 💾 数据持久化 | Room 数据库存储配置和聊天记录 |
| 🔄 连续对话 | TTS 结束后自动继续监听，支持实时/手动两种监听模式 |

## 项目架构

```
com.example.hiai
├── domain/                         # 领域层（接口契约 + 状态模型）
│   ├── contract/
│   │   ├── INetworkConnectionManager.kt    # 网络连接管理接口
│   │   ├── IAudioSessionManager.kt         # 音频会话管理接口
│   │   ├── IWakeWordManager.kt             # 唤醒词检测接口
│   │   └── IServiceStateManager.kt         # 服务状态管理接口
│   └── model/
│       ├── ConnectionState.kt              # 连接状态（sealed class）
│       ├── AudioSessionState.kt            # 音频会话状态
│       ├── ServiceState.kt                 # 服务状态
│       └── WakeWordState.kt                # 唤醒词状态
│
├── service/                        # 服务层
│   ├── VoiceAssistantService.kt            # 主服务（当前使用）
│   ├── VoiceAssistantServiceCoordinator.kt # 新架构协调者（重构中）
│   └── manager/
│       ├── NetworkConnectionManager.kt     # 网络连接管理器
│       ├── AudioSessionManager.kt          # 音频会话管理器
│       ├── WakeWordManager.kt              # 唤醒词管理器
│       ├── ServiceStateManager.kt          # 服务状态管理器
│       └── ServiceConnectionManager.kt     # 服务绑定管理器
│
├── audio/                          # 音频处理模块
│   ├── WakeWordDetector.kt                # Sherpa-ONNX 唤醒词检测器
│   ├── AudioProcessor.kt                  # 录音处理器（AudioRecord）
│   ├── AudioPlayer.kt                     # TTS 音频播放器（AudioTrack）
│   ├── OpusCodec.kt                       # Opus 编解码器（Concentus）
│   └── PcmPreRollBuffer.kt                # PCM 预滚缓冲区
│
├── network/                        # 网络通信模块
│   ├── NetworkManager.kt                  # OTA + WebSocket 管理器
│   └── model/
│       └── ProtocolModels.kt              # 协议模型（OTA/Hello/STT/TTS/MCP）
│
├── data/                           # 数据持久化模块
│   ├── AppDatabase.kt                     # Room 数据库定义
│   ├── DatabaseInitializer.kt             # 数据库初始化
│   ├── dao/Daos.kt                        # DAO（SettingDao, ChatHistoryDao）
│   ├── model/Entities.kt                  # 实体（Setting, ChatHistory）
│   └── repository/Repositories.kt         # 仓库（SettingRepository, ChatHistoryRepository）
│
├── ui/                             # UI 层（Jetpack Compose）
│   ├── screens/Screens.kt                 # 屏幕（桌面模式/普通模式/权限请求）
│   └── theme/
│       ├── Color.kt                       # 颜色定义（桌面模式配色 + 状态颜色）
│       ├── Theme.kt                       # 主题
│       └── Type.kt                        # 字体
│
├── viewmodel/                      # 视图模型
│   ├── VoiceAssistantViewModel.kt         # 当前 ViewModel
│   └── VoiceAssistantViewModelNew.kt      # 新架构 ViewModel（重构中）
│
├── di/                             # 依赖注入（Koin）
│   └── AppModule.kt                       # DI 模块配置
│
├── util/                           # 工具类
│   └── PermissionHelper.kt                # 权限检查与请求
│
├── MainActivity.kt                 # 主 Activity（UI 入口 + 服务绑定）
└── VoiceAssistantApplication.kt    # 应用入口（Koin 初始化）
```

## 核心工作流程

### 1. 服务启动与连接

```
App 启动 → 权限检查 → 启动前台服务 → 初始化数据库 → 初始化音频组件 → 初始化唤醒词检测器
    → 初始化 NetworkManager → OTA 请求获取 WebSocket URL → WebSocket 连接 → Hello 握手
    → 协商音频参数 → 进入 Idle 状态
```

### 2. 桌面模式唤醒流程

```
桌面模式 → 启动唤醒词检测 → 麦克风持续监听 → 检测到唤醒词
    → 等待 WebSocket 连接就绪 → 发送 Listen 消息 → 开始录音上传
    → 服务端 STT 识别 → 服务端 AI 处理 → 服务端 TTS 播放
    → TTS 结束 → 继续监听（实时模式）/ 回到 Idle（手动模式）
```

### 3. 音频数据流

```
录音：麦克风 PCM → Opus 编码 → WebSocket 发送
播放：WebSocket 接收 Opus → Opus 解码 → PCM → AudioTrack 播放
```

## 通信协议

应用与小智服务端的通信基于以下协议流程：

| 消息类型 | 方向 | 说明 |
|---------|------|------|
| OTA Request | 客户端→服务端 | HTTP POST，上报设备信息，获取 WebSocket URL |
| Hello | 双向 | WebSocket 握手，交换设备信息和音频参数 |
| Listen | 客户端→服务端 | 控制监听状态（detect/start/stop） |
| Audio (binary) | 双向 | Opus 编码的音频数据 |
| STT | 服务端→客户端 | 语音识别文本结果 |
| TTS (control) | 服务端→客户端 | TTS 状态控制（start/stop/sentence_start） |
| TTS (audio) | 服务端→客户端 | Opus 格式 TTS 音频数据 |
| Abort | 客户端→服务端 | 打断当前 TTS 播放 |
| MCP Tool | 双向 | MCP 工具调用和响应 |

## UI 模式

### 普通模式

设置和配置界面，提供：
- 服务状态卡片
- 服务器配置（OTA 地址、设备 ID、Token）
- 唤醒词配置（支持拼音音素格式自定义）
- 进入桌面模式入口

### 桌面模式

全屏沉浸式黑底界面（适合 OLED 屏幕省电），提供：
- **未连接时**：万年历时钟（大时钟 + 年月日 + 星期），带防烧屏策略
- **已连接时**：助手状态指示器 + TTS 消息滚动显示
- 底部连接状态栏

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 服务 | Android 前台服务（foregroundServiceType: microphone） |
| 音频编解码 | Concentus（纯 Java Opus 实现） |
| 唤醒词检测 | Sherpa-ONNX（zipformer2 模型，本地离线） |
| VAD | android-vad（WebRTC VAD） |
| 网络 | OkHttp（HTTP + WebSocket） |
| 数据库 | Room |
| 依赖注入 | Koin |
| 序列化 | kotlinx.serialization |
| 协程 | kotlinx.coroutines |

## 构建与运行

### 环境要求

- Android SDK 36（compileSdk / targetSdk）
- minSdk 23（Android 6.0+）
- JDK 17
- Kotlin

### 构建步骤

1. 克隆项目：
   ```bash
   git clone <repository-url>
   cd hiai
   ```

2. Sherpa-ONNX 模型文件需放置在 `app/src/main/assets/sherpa-onnx-models/` 目录下：
   - `encoder-epoch-13-avg-2-chunk-16-left-64.onnx`
   - `decoder-epoch-13-avg-2-chunk-16-left-64.onnx`
   - `joiner-epoch-13-avg-2-chunk-16-left-64.onnx`
   - `tokens.txt`

3. Sherpa-ONNX AAR 库需放置在 `app/libs/sherpa-onnx-1.12.14.aar`

4. 唤醒词关键词文件需放置在 `app/src/main/assets/keywords.txt`，格式为：
   ```
   n ǐ h ǎo x iǎo zh ì @你好小智
   h ēi n ǐ h ǎo y a @嘿你好呀
   ```

5. 使用 Android Studio 或 Gradle 构建：
   ```bash
   ./gradlew assembleDebug
   ```

### 服务端配置

首次运行需在"服务器配置"中填写：
- **OTA 地址**：小智服务端的 OTA 接口完整 URL（如 `http://your-server:8000/ota`）
- **设备 ID**：设备标识（留空则使用硬件序列号）
- **Token**：认证令牌（如服务端不需要可留空）

## 重构进度

项目正在进行架构重构，从单体 `VoiceAssistantService` 向 Manager 架构演进：

- ✅ 领域层模型和接口契约
- ✅ 所有 Manager 实现
- ✅ VoiceAssistantServiceCoordinator
- ✅ VoiceAssistantViewModelNew
- ✅ ServiceConnectionManager
- ⏳ UI 层重构（Screen 接口适配）
- ⏳ Koin DI 模块注册
- ⏳ 完整集成测试

## 权限说明

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 麦克风录音（唤醒词检测 + 语音输入） |
| `INTERNET` | 网络通信（OTA + WebSocket） |
| `ACCESS_NETWORK_STATE` | 检查网络连接状态 |
| `MODIFY_AUDIO_SETTINGS` | 音频路由和扬声器控制（OPPO/OnePlus 兼容） |
| `FOREGROUND_SERVICE` | 后台常驻服务 |
| `FOREGROUND_SERVICE_MICROPHONE` | 前台麦克风服务类型（Android 14+） |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动（可选） |
| `WAKE_LOCK` | 屏幕常亮（桌面模式） |

## License

Private / Internal Use