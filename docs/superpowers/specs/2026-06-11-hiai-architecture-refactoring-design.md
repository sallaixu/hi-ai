# HIAI 项目架构重构设计文档

**日期**: 2026-06-11
**作者**: AI Assistant
**状态**: 待审查

## 1. 概述

本文档描述了 HIAI（Android 语音助手）项目的架构重构方案，旨在解决职责不清、耦合度高的问题，提高代码的可维护性、可测试性和可扩展性。

## 2. 当前问题分析

### 2.1 VoiceAssistantService 职责过重

- **文件大小**: 约 700+ 行代码，包含 19 个函数
- **职责混乱**: 网络连接、音频处理、唤醒词检测、状态管理、通知管理、消息处理
- **违反原则**: 单一职责原则（SRP）

### 2.2 MainActivity 职责混乱

- **UI 管理**: 正确（应该负责）
- **服务绑定**: 错误（应该在 ViewModel）
- **状态观察**: 错误（应该在 ViewModel）
- **业务逻辑**: 错误（应该在 ViewModel）

### 2.3 缺乏接口抽象

- `NetworkManager`、`AudioProcessor` 等都是具体类
- 难以进行单元测试
- 难以替换实现（如 Mock 测试）

### 2.4 包结构不够清晰

- 虽然按功能分包，但模块边界不清晰
- 缺少领域层抽象
- 依赖方向不够明确

## 3. 重构目标

1. **职责清晰**: 每个类只有一个变更理由
2. **低耦合**: 高层模块依赖抽象，不依赖具体实现
3. **高内聚**: 相关功能聚合在一起
4. **可测试**: 通过接口抽象支持单元测试
5. **可扩展**: 新功能可以通过扩展点添加，不修改现有代码

## 4. 整体架构设计

### 4.1 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation Layer                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ MainActivity │  │  ViewModels  │  │    Screens   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                     Domain Layer                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │        VoiceAssistantService (协调者)             │  │
│  └──────────────────────────────────────────────────┘  │
│            │        │        │        │                │
│            ▼        ▼        ▼        ▼                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │ Network  │ │  Audio   │ │ WakeWord │ │  State   │ │
│  │Connection│ │ Session  │ │ Manager  │ │ Manager  │ │
│  │ Manager  │ │ Manager  │ │          │ │          │ │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                      Data Layer                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │   Network    │  │    Audio     │  │   Database   │  │
│  │   (API)      │  │  (Hardware)  │  │  (Storage)   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 4.2 核心设计原则

1. **单一职责原则（SRP）**: 每个类只有一个变更理由
2. **依赖倒置原则（DIP）**: 高层模块依赖抽象接口，不依赖具体实现
3. **接口隔离原则（ISP）**: 接口应该小而专注
4. **开闭原则（OCP）**: 对扩展开放，对修改关闭

## 5. VoiceAssistantService 拆分设计

### 5.1 拆分策略

将 VoiceAssistantService 拆分为以下组件：

```
VoiceAssistantService (协调者，约 150 行)
    ├── NetworkConnectionManager (网络连接管理，约 200 行)
    ├── AudioSessionManager (音频会话管理，约 200 行)
    ├── WakeWordManager (唤醒词管理，约 100 行)
    └── ServiceStateManager (状态管理，约 50 行)
```

### 5.2 各组件职责

#### VoiceAssistantService（协调者）

- **职责**: 协调各组件，管理服务生命周期
- **不包含**: 具体业务逻辑
- **负责**:
  - 创建和销毁组件
  - 分发 Intent 命令
  - 管理通知

#### NetworkConnectionManager

- **职责**: 管理与服务端的网络连接
- **负责**:
  - OTA 请求
  - WebSocket 连接
  - 消息分发
  - 连接状态管理

#### AudioSessionManager

- **职责**: 管理音频会话（录音 + 播放）
- **负责**:
  - 音频编解码
  - 录音控制
  - 播放控制
  - 音频参数管理

#### WakeWordManager

- **职责**: 管理唤醒词检测
- **负责**:
  - 唤醒词检测器初始化
  - 开始/停止检测
  - 唤醒回调处理

#### ServiceStateManager

- **职责**: 管理服务状态
- **负责**:
  - 状态流转
  - 状态持久化
  - 状态通知

### 5.3 接口设计

```kotlin
// 网络连接管理器接口
interface INetworkConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    val messageFlow: SharedFlow<Message>

    suspend fun connect()
    fun disconnect()
    suspend fun sendMessage(message: Message)
}

// 音频会话管理器接口
interface IAudioSessionManager {
    val audioState: StateFlow<AudioState>

    fun startRecording(): Flow<ByteArray>
    fun stopRecording()
    fun playAudio(data: ByteArray)
    fun stopPlaying()
    fun release()
}

// 唤醒词管理器接口
interface IWakeWordManager {
    val isDetecting: StateFlow<Boolean>

    fun startDetection()
    fun stopDetection()
    fun release()
}
```

### 5.4 依赖关系

```
VoiceAssistantService
    ├── INetworkConnectionManager (注入)
    ├── IAudioSessionManager (注入)
    ├── IWakeWordManager (注入)
    └── ServiceStateManager (内部创建)
```

## 6. Hilt 依赖注入设计

### 6.1 Hilt 模块设计

```
di/
├── AppModule.kt          // 应用级依赖
├── NetworkModule.kt      // 网络相关依赖
├── AudioModule.kt        // 音频相关依赖
├── DatabaseModule.kt     // 数据库相关依赖
└── ServiceModule.kt      // 服务相关依赖
```

### 6.2 各模块内容

#### AppModule.kt

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideContext(application: Application): Context = application

    @Provides
    @Singleton
    fun provideCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

#### NetworkModule.kt

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideNetworkManager(
        okHttpClient: OkHttpClient,
        settingRepository: SettingRepository
    ): INetworkConnectionManager = NetworkConnectionManager(okHttpClient, settingRepository)
}
```

#### AudioModule.kt

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    @Provides
    @Singleton
    fun provideOpusCodec(): OpusCodecInterface = OpusCodec(sampleRate = 16000)

    @Provides
    @Singleton
    fun provideAudioProcessor(): AudioProcessor = AudioProcessor(sampleRate = 16000)

    @Provides
    fun provideAudioSessionManager(
        opusCodec: OpusCodecInterface,
        audioProcessor: AudioProcessor,
        context: Context
    ): IAudioSessionManager = AudioSessionManager(opusCodec, audioProcessor, context)
}
```

#### DatabaseModule.kt

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(context: Context): AppDatabase =
        DatabaseInitializer.getDatabase(context)

    @Provides
    fun provideSettingDao(database: AppDatabase): SettingDao = database.settingDao()

    @Provides
    fun provideChatHistoryDao(database: AppDatabase): ChatHistoryDao = database.chatHistoryDao()

    @Provides
    fun provideSettingRepository(dao: SettingDao): SettingRepository =
        SettingRepository(dao)
}
```

#### ServiceModule.kt

```kotlin
@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {
    @Provides
    fun provideWakeWordManager(
        context: Context,
        @Named("wakeWordCallback") callback: (String) -> Unit
    ): IWakeWordManager = WakeWordManager(context, callback)
}
```

### 6.3 作用域设计

- `@Singleton`: 应用级单例（如 OkHttpClient、Database）
- `@ServiceScoped`: 服务级作用域（如 WakeWordManager）
- 无注解: 每次注入创建新实例（如 AudioSessionManager）

### 6.4 入口点

```kotlin
@HiltAndroidApp
class VoiceAssistantApplication : Application()

@AndroidEntryPoint
class MainActivity : ComponentActivity()

@AndroidEntryPoint
class VoiceAssistantService : Service()
```

## 7. MainActivity 和 ViewModel 重构设计

### 7.1 职责划分

#### MainActivity（纯 UI 层）

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: VoiceAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoiceAssistantTheme {
                when {
                    !viewModel.hasPermission -> PermissionRequestScreen()
                    viewModel.isDeskMode -> DeskModeScreen(viewModel)
                    else -> NormalModeScreen(viewModel)
                }
            }
        }
    }
}
```

#### VoiceAssistantViewModel（业务逻辑层）

```kotlin
@HiltViewModel
class VoiceAssistantViewModel @Inject constructor(
    private val serviceConnectionManager: IServiceConnectionManager,
    private val settingRepository: SettingRepository
) : ViewModel() {

    // UI 状态
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 服务连接状态
    val serviceState: StateFlow<ServiceState> = serviceConnectionManager.serviceState
    val isDeskMode: StateFlow<Boolean> = serviceConnectionManager.isDeskMode

    // 权限状态
    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    // 服务器配置对话框状态
    private val _showServerConfigDialog = MutableStateFlow(false)
    val showServerConfigDialog: StateFlow<Boolean> = _showServerConfigDialog.asStateFlow()

    init {
        // 连接服务
        viewModelScope.launch {
            serviceConnectionManager.connect()
        }
    }

    // 业务方法
    fun toggleDeskMode() {
        viewModelScope.launch {
            serviceConnectionManager.toggleDeskMode()
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            settingRepository.set("server_url", url)
            serviceConnectionManager.reconnect()
        }
    }

    fun showServerConfigDialog() {
        _showServerConfigDialog.value = true
    }

    fun hideServerConfigDialog() {
        _showServerConfigDialog.value = false
    }

    data class UiState(
        val serviceState: ServiceState = ServiceState.Idle,
        val isDeskMode: Boolean = false,
        val hasPermission: Boolean = false,
        val showServerConfigDialog: Boolean = false,
        val errorMessage: String? = null
    )
}
```

### 7.2 ServiceConnectionManager

```kotlin
interface IServiceConnectionManager {
    val serviceState: StateFlow<ServiceState>
    val isDeskMode: StateFlow<Boolean>
    val isConnected: StateFlow<Boolean>

    suspend fun connect()
    fun disconnect()
    fun toggleDeskMode()
    suspend fun reconnect()
}

class ServiceConnectionManager @Inject constructor(
    private val context: Context
) : IServiceConnectionManager {
    // 管理与 VoiceAssistantService 的绑定
    // 使用 ServiceConnection
}
```

### 7.3 数据流

```
用户操作 → ViewModel → ServiceConnectionManager → VoiceAssistantService
                ↓
            UI 状态更新
```

## 8. 包结构优化设计

### 8.1 当前包结构

```
com.example.hiai/
├── audio/          # 音频相关
├── data/           # 数据相关
│   ├── dao/
│   ├── model/
│   └── repository/
├── network/        # 网络相关
│   └── model/
├── service/        # 服务相关
├── ui/             # UI 相关
│   ├── screens/
│   └── theme/
├── util/           # 工具类
└── viewmodel/      # ViewModel
```

### 8.2 优化后的包结构

```
com.example.hiai/
├── di/                          # 依赖注入模块
│   ├── AppModule.kt
│   ├── NetworkModule.kt
│   ├── AudioModule.kt
│   ├── DatabaseModule.kt
│   └── ServiceModule.kt
│
├── domain/                      # 领域层（核心业务逻辑）
│   ├── manager/                 # 管理器
│   │   ├── NetworkConnectionManager.kt
│   │   ├── AudioSessionManager.kt
│   │   ├── WakeWordManager.kt
│   │   └── ServiceStateManager.kt
│   ├── contract/                # 接口契约
│   │   ├── INetworkConnectionManager.kt
│   │   ├── IAudioSessionManager.kt
│   │   └── IWakeWordManager.kt
│   └── model/                   # 领域模型
│       ├── ServiceState.kt
│       ├── ConnectionState.kt
│       └── AudioState.kt
│
├── data/                        # 数据层
│   ├── network/                 # 网络数据源
│   │   ├── NetworkManager.kt
│   │   └── model/
│   │       ├── ProtocolModels.kt
│   │       └── WebSocketModels.kt
│   ├── audio/                   # 音频数据源
│   │   ├── AudioProcessor.kt
│   │   ├── AudioPlayer.kt
│   │   ├── OpusCodec.kt
│   │   └── WakeWordDetector.kt
│   ├── database/                # 数据库数据源
│   │   ├── AppDatabase.kt
│   │   ├── dao/
│   │   │   └── Daos.kt
│   │   └── entity/
│   │       └── Entities.kt
│   └── repository/              # 数据仓库
│       ├── SettingRepository.kt
│       └── ChatHistoryRepository.kt
│
├── service/                     # 服务层
│   └── VoiceAssistantService.kt
│
├── presentation/                # 表现层
│   ├── main/                    # 主界面
│   │   ├── MainActivity.kt
│   │   └── VoiceAssistantViewModel.kt
│   ├── screen/                  # 各屏幕
│   │   ├── NormalModeScreen.kt
│   │   ├── DeskModeScreen.kt
│   │   └── PermissionRequestScreen.kt
│   └── theme/                   # 主题
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
│
├── util/                        # 工具类
│   └── PermissionHelper.kt
│
└── VoiceAssistantApplication.kt # Application 入口
```

### 8.3 包结构设计原则

1. **按层次分包**: domain、data、presentation、service、di
2. **按功能模块分包**: 每个层次下按功能模块细分
3. **依赖方向**: presentation → domain → data
4. **接口与实现分离**: contract 包放接口，manager 包放实现

### 8.4 文件移动映射

| 原路径 | 新路径 |
|--------|--------|
| `audio/AudioProcessor.kt` | `data/audio/AudioProcessor.kt` |
| `audio/OpusCodec.kt` | `data/audio/OpusCodec.kt` |
| `audio/WakeWordDetector.kt` | `data/audio/WakeWordDetector.kt` |
| `network/NetworkManager.kt` | `data/network/NetworkManager.kt` |
| `data/model/Entities.kt` | `data/database/entity/Entities.kt` |
| `data/dao/Daos.kt` | `data/database/dao/Daos.kt` |
| `viewmodel/VoiceAssistantViewModel.kt` | `presentation/main/VoiceAssistantViewModel.kt` |
| `ui/screens/Screens.kt` | `presentation/screen/` (拆分为多个文件) |

## 9. 实施计划

### 9.1 阶段一：引入 Hilt + 添加接口抽象（预计 2-3 小时）

1. 添加 Hilt 依赖到 `build.gradle.kts`
2. 创建 `VoiceAssistantApplication`
3. 创建接口契约（`INetworkConnectionManager`、`IAudioSessionManager` 等）
4. 创建 Hilt 模块（`AppModule`、`NetworkModule` 等）
5. 为现有类添加 `@Inject` 注解
6. 测试：确保应用能正常启动和运行

### 9.2 阶段二：拆分 VoiceAssistantService（预计 4-5 小时）

1. 创建 `NetworkConnectionManager`（从 Service 中提取网络逻辑）
2. 创建 `AudioSessionManager`（从 Service 中提取音频逻辑）
3. 创建 `WakeWordManager`（从 Service 中提取唤醒词逻辑）
4. 创建 `ServiceStateManager`（从 Service 中提取状态逻辑）
5. 重构 `VoiceAssistantService` 为协调者
6. 测试：确保服务能正常启动、连接、录音、播放

### 9.3 阶段三：重构 MainActivity 和 ViewModel（预计 2-3 小时）

1. 创建 `ServiceConnectionManager`
2. 重构 `VoiceAssistantViewModel`（添加业务逻辑）
3. 简化 `MainActivity`（只保留 UI 逻辑）
4. 测试：确保 UI 能正常显示和交互

### 9.4 阶段四：优化包结构（预计 1-2 小时）

1. 创建新的包结构
2. 移动文件到新位置
3. 更新 import 语句
4. 测试：确保编译通过且功能正常

## 10. 风险控制

### 10.1 风险识别与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 重构过程中破坏现有功能 | 高 | 每个阶段完成后进行完整测试；使用 Git 分支进行重构，随时可以回滚 |
| Hilt 依赖注入配置错误 | 中 | 先在一个简单组件上测试 Hilt；参考官方文档和示例 |
| 接口抽象不完整 | 中 | 先抽象最常用的方法；后续根据需要扩展接口 |
| 包结构移动导致编译错误 | 低 | 使用 IDE 的重构功能；逐个文件移动，每次移动后编译测试 |

### 10.2 验证标准

每个阶段完成后，需要验证：

1. ✅ 应用能正常启动
2. ✅ 服务能正常启动和常驻
3. ✅ 能连接到服务端
4. ✅ 能进行语音唤醒
5. ✅ 能进行录音和播放
6. ✅ UI 能正常显示和交互
7. ✅ 配置能正常保存和读取

### 10.3 回滚策略

如果某个阶段出现问题：

1. 立即停止重构
2. 使用 `git checkout` 回滚到上一个稳定版本
3. 分析问题原因
4. 调整方案后重新开始该阶段

## 11. 预期收益

### 11.1 可维护性提升

- 每个类职责清晰，易于理解和修改
- 代码组织有序，易于定位问题
- 接口抽象明确，易于扩展功能

### 11.2 可测试性提升

- 通过接口抽象支持 Mock 测试
- 业务逻辑集中在 ViewModel，易于单元测试
- 依赖注入使得测试时可以替换依赖

### 11.3 可扩展性提升

- 新功能可以通过添加新的 Manager 实现
- 不需要修改现有代码（开闭原则）
- 模块边界清晰，易于并行开发

## 12. 附录

### 12.1 参考资料

- [Hilt 官方文档](https://dagger.dev/hilt/)
- [Android 架构组件指南](https://developer.android.com/topic/architecture)
- [SOLID 原则](https://en.wikipedia.org/wiki/SOLID)

### 12.2 相关文档

- [Sherpa-ONNX 唤醒词集成设计](./2026-06-10-sherpa-onnx-wake-word-design.md)
- [小智 Android 应用实现计划](./2026-06-10-xiaozhi-android-app-implementation.md)
