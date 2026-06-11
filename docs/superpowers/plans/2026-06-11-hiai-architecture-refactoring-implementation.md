# HIAI 项目架构重构实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 重构 HIAI 项目架构，解决职责不清、耦合度高的问题，提高代码的可维护性、可测试性和可扩展性。

**架构：** 采用分层架构（Presentation → Domain → Data），使用 Hilt 进行依赖注入，将 VoiceAssistantService 拆分为多个职责单一的管理器，优化包结构。

**技术栈：** Kotlin、Android、Hilt（依赖注入）、OkHttp（网络）、Room（数据库）、Sherpa-ONNX（唤醒词检测）、Jetpack Compose（UI）

---

## 文件结构

### 阶段一：引入 Hilt + 添加接口抽象

**创建文件：**
- `app/src/main/java/com/example/hiai/VoiceAssistantApplication.kt` - Application 入口，配置 Hilt
- `app/src/main/java/com/example/hiai/di/AppModule.kt` - 应用级依赖（Context、CoroutineScope）
- `app/src/main/java/com/example/hiai/di/NetworkModule.kt` - 网络依赖（OkHttpClient、NetworkManager）
- `app/src/main/java/com/example/hiai/di/AudioModule.kt` - 音频依赖（OpusCodec、AudioProcessor）
- `app/src/main/java/com/example/hiai/di/DatabaseModule.kt` - 数据库依赖（Database、DAO、Repository）
- `app/src/main/java/com/example/hiai/domain/contract/INetworkConnectionManager.kt` - 网络连接管理器接口
- `app/src/main/java/com/example/hiai/domain/contract/IAudioSessionManager.kt` - 音频会话管理器接口
- `app/src/main/java/com/example/hiai/domain/contract/IWakeWordManager.kt` - 唤醒词管理器接口

**修改文件：**
- `app/build.gradle.kts` - 添加 Hilt 依赖
- `app/src/main/java/com/example/hiai/MainActivity.kt` - 添加 @AndroidEntryPoint
- `app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt` - 添加 @AndroidEntryPoint
- `app/src/main/AndroidManifest.xml` - 设置 Application

### 阶段二：拆分 VoiceAssistantService

**创建文件：**
- `app/src/main/java/com/example/hiai/domain/manager/NetworkConnectionManager.kt` - 网络连接管理器实现
- `app/src/main/java/com/example/hiai/domain/manager/AudioSessionManager.kt` - 音频会话管理器实现
- `app/src/main/java/com/example/hiai/domain/manager/WakeWordManager.kt` - 唤醒词管理器实现
- `app/src/main/java/com/example/hiai/domain/manager/ServiceStateManager.kt` - 状态管理器实现
- `app/src/main/java/com/example/hiai/domain/model/ServiceState.kt` - 服务状态模型
- `app/src/main/java/com/example/hiai/domain/model/ConnectionState.kt` - 连接状态模型
- `app/src/main/java/com/example/hiai/domain/model/AudioState.kt` - 音频状态模型

**修改文件：**
- `app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt` - 重构为协调者
- `app/src/main/java/com/example/hiai/di/ServiceModule.kt` - 添加服务相关依赖

### 阶段三：重构 MainActivity 和 ViewModel

**创建文件：**
- `app/src/main/java/com/example/hiai/domain/contract/IServiceConnectionManager.kt` - 服务连接管理器接口
- `app/src/main/java/com/example/hiai/domain/manager/ServiceConnectionManager.kt` - 服务连接管理器实现

**修改文件：**
- `app/src/main/java/com/example/hiai/viewmodel/VoiceAssistantViewModel.kt` - 重构为业务逻辑层
- `app/src/main/java/com/example/hiai/MainActivity.kt` - 简化为纯 UI 层
- `app/src/main/java/com/example/hiai/di/AppModule.kt` - 添加 ServiceConnectionManager 依赖

### 阶段四：优化包结构

**移动文件：**（按设计文档的文件移动映射表）

---

## 任务列表

### 任务 1：添加 Hilt 依赖

**文件：**
- 修改：`app/build.gradle.kts`

- [ ] **步骤 1：在 build.gradle.kts 中添加 Hilt 插件和依赖**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt) // 添加 Hilt 插件
}

dependencies {
    // ... 现有依赖 ...

    // Hilt 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
}
```

- [ ] **步骤 2：在 libs.versions.toml 中添加 Hilt 版本定义**

```toml
[versions]
hilt = "2.48"
hiltWork = "1.1.0"

[libraries]
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hiltWork" }
hilt-work-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hiltWork" }

[plugins]
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **步骤 3：Sync Gradle 并验证**

运行：Gradle Sync
预期：成功，无错误

- [ ] **步骤 4：Commit**

```bash
git add app/build.gradle.kts gradle/libs.versions.toml
git commit -m "build: 添加 Hilt 依赖注入框架"
```

---

### 任务 2：创建 Application 入口

**文件：**
- 创建：`app/src/main/java/com/example/hiai/VoiceAssistantApplication.kt`
- 修改：`app/src/main/AndroidManifest.xml`

- [ ] **步骤 1：创建 VoiceAssistantApplication 类**

```kotlin
package com.example.hiai

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口
 *
 * 配置 Hilt 依赖注入
 */
@HiltAndroidApp
class VoiceAssistantApplication : Application()
```

- [ ] **步骤 2：在 AndroidManifest.xml 中设置 Application**

```xml
<application
    android:name=".VoiceAssistantApplication"
    android:allowBackup="true"
    ...>
    ...
</application>
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew assembleDebug`
预期：成功，无错误

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/example/hiai/VoiceAssistantApplication.kt app/src/main/AndroidManifest.xml
git commit -m "feat: 创建 Hilt Application 入口"
```

---

### 任务 3：创建领域模型

**文件：**
- 创建：`app/src/main/java/com/example/hiai/domain/model/ServiceState.kt`
- 创建：`app/src/main/java/com/example/hiai/domain/model/ConnectionState.kt`
- 创建：`app/src/main/java/com/example/hiai/domain/model/AudioState.kt`

- [ ] **步骤 1：创建 ServiceState 模型**

```kotlin
package com.example.hiai.domain.model

/**
 * 服务状态
 */
sealed class ServiceState {
    object Idle : ServiceState()
    object Listening : ServiceState()
    object Speaking : ServiceState()
    object Error : ServiceState()
}
```

- [ ] **步骤 2：创建 ConnectionState 模型**

```kotlin
package com.example.hiai.domain.model

/**
 * 连接状态
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Error : ConnectionState()
}
```

- [ ] **步骤 3：创建 AudioState 模型**

```kotlin
package com.example.hiai.domain.model

/**
 * 音频状态
 */
sealed class AudioState {
    object Idle : AudioState()
    object Recording : AudioState()
    object Playing : AudioState()
    object Error : AudioState()
}
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/example/hiai/domain/model/
git commit -m "feat: 创建领域模型（ServiceState、ConnectionState、AudioState）"
```

---

### 任务 4：创建接口契约

**文件：**
- 创建：`app/src/main/java/com/example/hiai/domain/contract/INetworkConnectionManager.kt`
- 创建：`app/src/main/java/com/example/hiai/domain/contract/IAudioSessionManager.kt`
- 创建：`app/src/main/java/com/example/hiai/domain/contract/IWakeWordManager.kt`

- [ ] **步骤 1：创建 INetworkConnectionManager 接口**

```kotlin
package com.example.hiai.domain.contract

import com.example.hiai.domain.model.ConnectionState
import com.example.hiai.network.model.WebSocketMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 网络连接管理器接口
 *
 * 负责与服务端的网络连接和通信
 */
interface INetworkConnectionManager {
    /**
     * 连接状态
     */
    val connectionState: Flow<ConnectionState>

    /**
     * 消息流
     */
    val messageFlow: SharedFlow<Any>

    /**
     * 连接到服务端
     */
    suspend fun connect()

    /**
     * 断开连接
     */
    fun disconnect()

    /**
     * 发送消息
     */
    suspend fun sendMessage(message: WebSocketMessage)

    /**
     * 重新连接
     */
    suspend fun reconnect()
}
```

- [ ] **步骤 2：创建 IAudioSessionManager 接口**

```kotlin
package com.example.hiai.domain.contract

import com.example.hiai.domain.model.AudioState
import kotlinx.coroutines.flow.Flow

/**
 * 音频会话管理器接口
 *
 * 负责音频的录制和播放
 */
interface IAudioSessionManager {
    /**
     * 音频状态
     */
    val audioState: Flow<AudioState>

    /**
     * 开始录音
     *
     * @return 音频数据流
     */
    fun startRecording(): Flow<ByteArray>

    /**
     * 停止录音
     */
    fun stopRecording()

    /**
     * 播放音频
     *
     * @param data 音频数据
     */
    fun playAudio(data: ByteArray)

    /**
     * 停止播放
     */
    fun stopPlaying()

    /**
     * 释放资源
     */
    fun release()
}
```

- [ ] **步骤 3：创建 IWakeWordManager 接口**

```kotlin
package com.example.hiai.domain.contract

import kotlinx.coroutines.flow.Flow

/**
 * 唤醒词管理器接口
 *
 * 负责唤醒词的检测
 */
interface IWakeWordManager {
    /**
     * 是否正在检测
     */
    val isDetecting: Flow<Boolean>

    /**
     * 开始检测
     */
    fun startDetection()

    /**
     * 停止检测
     */
    fun stopDetection()

    /**
     * 释放资源
     */
    fun release()
}
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/example/hiai/domain/contract/
git commit -m "feat: 创建接口契约（INetworkConnectionManager、IAudioSessionManager、IWakeWordManager）"
```

---

### 任务 5：创建 Hilt 模块

**文件：**
- 创建：`app/src/main/java/com/example/hiai/di/AppModule.kt`
- 创建：`app/src/main/java/com/example/hiai/di/NetworkModule.kt`
- 创建：`app/src/main/java/com/example/hiai/di/AudioModule.kt`
- 创建：`app/src/main/java/com/example/hiai/di/DatabaseModule.kt`

- [ ] **步骤 1：创建 AppModule**

```kotlin
package com.example.hiai.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * 应用级依赖模块
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

- [ ] **步骤 2：创建 NetworkModule**

```kotlin
package com.example.hiai.di

import com.example.hiai.data.repository.SettingRepository
import com.example.hiai.network.NetworkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络依赖模块
 */
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
    ): NetworkManager {
        // 注意：这里需要从 SettingRepository 获取配置
        // 暂时使用默认值，后续在 Service 中动态配置
        return NetworkManager(
            baseUrl = "http://localhost:8000",
            deviceId = "default",
            deviceName = "Android Assistant",
            token = ""
        )
    }
}
```

- [ ] **步骤 3：创建 AudioModule**

```kotlin
package com.example.hiai.di

import com.example.hiai.audio.AudioProcessor
import com.example.hiai.audio.OpusCodec
import com.example.hiai.audio.OpusCodecInterface
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 音频依赖模块
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideOpusCodec(): OpusCodecInterface = OpusCodec(sampleRate = 16000)

    @Provides
    @Singleton
    fun provideAudioProcessor(): AudioProcessor = AudioProcessor(sampleRate = 16000)
}
```

- [ ] **步骤 4：创建 DatabaseModule**

```kotlin
package com.example.hiai.di

import android.content.Context
import com.example.hiai.data.AppDatabase
import com.example.hiai.data.DatabaseInitializer
import com.example.hiai.data.dao.SettingDao
import com.example.hiai.data.dao.ChatHistoryDao
import com.example.hiai.data.repository.SettingRepository
import com.example.hiai.data.repository.ChatHistoryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库依赖模块
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        DatabaseInitializer.getDatabase(context)

    @Provides
    fun provideSettingDao(database: AppDatabase): SettingDao = database.settingDao()

    @Provides
    fun provideChatHistoryDao(database: AppDatabase): ChatHistoryDao = database.chatHistoryDao()

    @Provides
    @Singleton
    fun provideSettingRepository(dao: SettingDao): SettingRepository =
        SettingRepository(dao)

    @Provides
    @Singleton
    fun provideChatHistoryRepository(dao: ChatHistoryDao): ChatHistoryRepository =
        ChatHistoryRepository(dao)
}
```

- [ ] **步骤 5：编译验证**

运行：`./gradlew assembleDebug`
预期：成功，无错误

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/example/hiai/di/
git commit -m "feat: 创建 Hilt 依赖注入模块"
```

---

### 任务 6：为 MainActivity 和 Service 添加 Hilt 注解

**文件：**
- 修改：`app/src/main/java/com/example/hiai/MainActivity.kt`
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`

- [ ] **步骤 1：为 MainActivity 添加 @AndroidEntryPoint**

在 MainActivity 类声明前添加：

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // ... 现有代码 ...
}
```

- [ ] **步骤 2：为 VoiceAssistantService 添加 @AndroidEntryPoint**

在 VoiceAssistantService 类声明前添加：

```kotlin
@AndroidEntryPoint
class VoiceAssistantService : Service() {
    // ... 现有代码 ...
}
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew assembleDebug`
预期：成功，无错误

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/example/hiai/MainActivity.kt app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt
git commit -m "feat: 为 MainActivity 和 Service 添加 Hilt 注解"
```

---

### 任务 7：创建 NetworkConnectionManager

**文件：**
- 创建：`app/src/main/java/com/example/hiai/domain/manager/NetworkConnectionManager.kt`

- [ ] **步骤 1：创建 NetworkConnectionManager 类**

从 VoiceAssistantService 中提取网络连接逻辑：

```kotlin
package com.example.hiai.domain.manager

import android.util.Log
import com.example.hiai.data.repository.SettingRepository
import com.example.hiai.domain.contract.INetworkConnectionManager
import com.example.hiai.domain.model.ConnectionState
import com.example.hiai.network.NetworkManager
import com.example.hiai.network.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络连接管理器
 *
 * 负责与服务端的网络连接和通信
 */
@Singleton
class NetworkConnectionManager @Inject constructor(
    private val settingRepository: SettingRepository
) : INetworkConnectionManager {

    companion object {
        private const val TAG = "NetworkConnectionManager"
    }

    private var networkManager: NetworkManager? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    override val messageFlow: SharedFlow<Any>
        get() = networkManager?.messageFlow ?: MutableSharedFlow()

    /**
     * 初始化网络管理器
     */
    suspend fun initialize() {
        val baseUrl = settingRepository.get("server_url") ?: "http://localhost:8000"
        val deviceId = settingRepository.get("device_id") ?: generateDeviceId()
        val token = settingRepository.get("token") ?: ""

        networkManager = NetworkManager(
            baseUrl = baseUrl,
            deviceId = deviceId,
            deviceName = "Android Assistant",
            token = token
        )
    }

    override suspend fun connect() {
        _connectionState.value = ConnectionState.Connecting

        try {
            // 获取 OTA 信息
            val otaResponse = networkManager?.fetchOtaInfo()

            if (otaResponse != null && networkManager != null) {
                // 连接 WebSocket
                networkManager?.connect(otaResponse.websocket.url, otaResponse.websocket.token)
                _connectionState.value = ConnectionState.Connected
            } else {
                _connectionState.value = ConnectionState.Error
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            _connectionState.value = ConnectionState.Error
        }
    }

    override fun disconnect() {
        networkManager?.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun sendMessage(message: WebSocketMessage) {
        networkManager?.sendMessage(message)
    }

    override suspend fun reconnect() {
        disconnect()
        connect()
    }

    private fun generateDeviceId(): String {
        return android.provider.Settings.Secure.ANDROID_ID
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/example/hiai/domain/manager/NetworkConnectionManager.kt
git commit -m "feat: 创建 NetworkConnectionManager"
```

---

### 任务 8：创建 AudioSessionManager

**文件：**
- 创建：`app/src/main/java/com/example/hiai/domain/manager/AudioSessionManager.kt`

- [ ] **步骤 1：创建 AudioSessionManager 类**

从 VoiceAssistantService 中提取音频会话逻辑：

```kotlin
package com.example.hiai.domain.manager

import android.content.Context
import android.util.Log
import com.example.hiai.audio.AudioPlayer
import com.example.hiai.audio.AudioProcessor
import com.example.hiai.audio.OpusCodecInterface
import com.example.hiai.domain.contract.IAudioSessionManager
import com.example.hiai.domain.model.AudioState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频会话管理器
 *
 * 负责音频的录制和播放
 */
@Singleton
class AudioSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opusCodec: OpusCodecInterface,
    private val audioProcessor: AudioProcessor,
    private val coroutineScope: CoroutineScope
) : IAudioSessionManager {

    companion object {
        private const val TAG = "AudioSessionManager"
    }

    private lateinit var audioPlayer: AudioPlayer

    private val _audioState = MutableStateFlow<AudioState>(AudioState.Idle)
    override val audioState: Flow<AudioState> = _audioState.asStateFlow()

    init {
        // 初始化音频播放器
        audioPlayer = AudioPlayer(context, opusCodec, 16000, coroutineScope)
    }

    override fun startRecording(): Flow<ByteArray> {
        _audioState.value = AudioState.Recording
        return audioProcessor.startRecording()
    }

    override fun stopRecording() {
        audioProcessor.stopRecording()
        _audioState.value = AudioState.Idle
    }

    override fun playAudio(data: ByteArray) {
        _audioState.value = AudioState.Playing
        audioPlayer.play(data)
    }

    override fun stopPlaying() {
        audioPlayer.stop()
        _audioState.value = AudioState.Idle
    }

    override fun release() {
        audioProcessor.release()
        audioPlayer.release()
        opusCodec.release()
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/example/hiai/domain/manager/AudioSessionManager.kt
git commit -m "feat: 创建 AudioSessionManager"
```

---

### 任务 9：创建 WakeWordManager

**文件：**
- 创建：`app/src/main/java/com/example/hiai/domain/manager/WakeWordManager.kt`

- [ ] **步骤 1：创建 WakeWordManager 类**

从 VoiceAssistantService 中提取唤醒词检测逻辑：

```kotlin
package com.example.hiai.domain.manager

import android.content.Context
import android.util.Log
import com.example.hiai.audio.WakeWordDetector
import com.example.hiai.domain.contract.IWakeWordManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * 唤醒词管理器
 *
 * 负责唤醒词的检测
 */
class WakeWordManager @Inject constructor(
    @ApplicationContext private val context: Context
) : IWakeWordManager {

    companion object {
        private const val TAG = "WakeWordManager"
    }

    private var wakeWordDetector: WakeWordDetector? = null
    private var onWakeWordDetected: ((String) -> Unit)? = null

    private val _isDetecting = MutableStateFlow(false)
    override val isDetecting: Flow<Boolean> = _isDetecting.asStateFlow()

    /**
     * 设置唤醒词检测回调
     */
    fun setWakeWordCallback(callback: (String) -> Unit) {
        onWakeWordDetected = callback
    }

    /**
     * 初始化唤醒词检测器
     */
    fun initialize(): Boolean {
        wakeWordDetector = WakeWordDetector(context) { keyword ->
            onWakeWordDetected?.invoke(keyword)
        }
        return wakeWordDetector?.init() ?: false
    }

    override fun startDetection() {
        wakeWordDetector?.start()
        _isDetecting.value = true
    }

    override fun stopDetection() {
        wakeWordDetector?.stop()
        _isDetecting.value = false
    }

    override fun release() {
        wakeWordDetector?.release()
        wakeWordDetector = null
        _isDetecting.value = false
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/example/hiai/domain/manager/WakeWordManager.kt
git commit -m "feat: 创建 WakeWordManager"
```

---

### 任务 10：创建 ServiceStateManager

**文件：**
- 创建：`app/src/main/java/com/example/hiai/domain/manager/ServiceStateManager.kt`

- [ ] **步骤 1：创建 ServiceStateManager 类**

从 VoiceAssistantService 中提取状态管理逻辑：

```kotlin
package com.example.hiai.domain.manager

import com.example.hiai.domain.model.ServiceState
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务状态管理器
 *
 * 负责服务状态的管理和通知
 */
@Singleton
class ServiceStateManager @Inject constructor() {

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _isDeskMode = MutableStateFlow(false)
    val isDeskMode: StateFlow<Boolean> = _isDeskMode.asStateFlow()

    /**
     * 更新服务状态
     */
    fun updateServiceState(state: ServiceState) {
        _serviceState.value = state
    }

    /**
     * 切换桌面模式
     */
    fun toggleDeskMode() {
        _isDeskMode.value = !_isDeskMode.value
    }

    /**
     * 设置桌面模式
     */
    fun setDeskMode(isDeskMode: Boolean) {
        _isDeskMode.value = isDeskMode
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/example/hiai/domain/manager/ServiceStateManager.kt
git commit -m "feat: 创建 ServiceStateManager"
```

---

### 任务 11：重构 VoiceAssistantService 为协调者

**文件：**
- 修改：`app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt`
- 创建：`app/src/main/java/com/example/hiai/di/ServiceModule.kt`

- [ ] **步骤 1：创建 ServiceModule**

```kotlin
package com.example.hiai.di

import android.content.Context
import com.example.hiai.domain.manager.WakeWordManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 服务依赖模块
 */
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideWakeWordManager(@ApplicationContext context: Context): WakeWordManager =
        WakeWordManager(context)
}
```

- [ ] **步骤 2：重构 VoiceAssistantService**

将 VoiceAssistantService 重构为协调者，注入各个管理器：

```kotlin
@AndroidEntryPoint
class VoiceAssistantService : Service() {

    @Inject lateinit var networkConnectionManager: NetworkConnectionManager
    @Inject lateinit var audioSessionManager: AudioSessionManager
    @Inject lateinit var wakeWordManager: WakeWordManager
    @Inject lateinit var serviceStateManager: ServiceStateManager

    // ... 只保留协调逻辑，具体实现委托给各个管理器 ...
}
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew assembleDebug`
预期：成功，无错误

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/example/hiai/service/VoiceAssistantService.kt app/src/main/java/com/example/hiai/di/ServiceModule.kt
git commit -m "refactor: 重构 VoiceAssistantService 为协调者"
```

---

### 任务 12：创建 ServiceConnectionManager

**文件：**
- 创建：`app/src/main/java/com/example/hiai/domain/contract/IServiceConnectionManager.kt`
- 创建：`app/src/main/java/com/example/hiai/domain/manager/ServiceConnectionManager.kt`

- [ ] **步骤 1：创建 IServiceConnectionManager 接口**

```kotlin
package com.example.hiai.domain.contract

import com.example.hiai.domain.model.ServiceState
import kotlinx.coroutines.flow.StateFlow

/**
 * 服务连接管理器接口
 *
 * 负责与 VoiceAssistantService 的连接
 */
interface IServiceConnectionManager {
    /**
     * 服务状态
     */
    val serviceState: StateFlow<ServiceState>

    /**
     * 是否为桌面模式
     */
    val isDeskMode: StateFlow<Boolean>

    /**
     * 是否已连接
     */
    val isConnected: StateFlow<Boolean>

    /**
     * 连接服务
     */
    suspend fun connect()

    /**
     * 断开连接
     */
    fun disconnect()

    /**
     * 切换桌面模式
     */
    fun toggleDeskMode()

    /**
     * 重新连接
     */
    suspend fun reconnect()
}
```

- [ ] **步骤 2：创建 ServiceConnectionManager 实现**

```kotlin
package com.example.hiai.domain.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.hiai.domain.contract.IServiceConnectionManager
import com.example.hiai.domain.model.ServiceState
import com.example.hiai.service.VoiceAssistantService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务连接管理器
 *
 * 负责与 VoiceAssistantService 的连接
 */
@Singleton
class ServiceConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) : IServiceConnectionManager {

    private var service: VoiceAssistantService? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    override val serviceState: StateFlow<ServiceState>
        get() = service?.serviceStateManager?.serviceState ?: MutableStateFlow(ServiceState.Idle)

    override val isDeskMode: StateFlow<Boolean>
        get() = service?.serviceStateManager?.isDeskMode ?: MutableStateFlow(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VoiceAssistantService.LocalBinder
            service = localBinder.getService()
            _isConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _isConnected.value = false
        }
    }

    override suspend fun connect() {
        val intent = Intent(context, VoiceAssistantService::class.java).apply {
            action = VoiceAssistantService.ACTION_START
        }
        context.startService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun disconnect() {
        service?.let {
            context.unbindService(serviceConnection)
            _isConnected.value = false
        }
    }

    override fun toggleDeskMode() {
        val intent = Intent(context, VoiceAssistantService::class.java).apply {
            action = VoiceAssistantService.ACTION_TOGGLE_DESK_MODE
        }
        context.startService(intent)
    }

    override suspend fun reconnect() {
        disconnect()
        connect()
    }
}
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/domain/contract/IServiceConnectionManager.kt app/src/main/java/com/example/hiai/domain/manager/ServiceConnectionManager.kt
git commit -m "feat: 创建 ServiceConnectionManager"
```

---

### 任务 13：重构 VoiceAssistantViewModel

**文件：**
- 修改：`app/src/main/java/com/example/hiai/viewmodel/VoiceAssistantViewModel.kt`

- [ ] **步骤 1：重构 VoiceAssistantViewModel**

```kotlin
package com.example.hiai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hiai.data.repository.SettingRepository
import com.example.hiai.domain.contract.IServiceConnectionManager
import com.example.hiai.domain.model.ServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 语音助手 ViewModel
 *
 * 管理 UI 状态和业务逻辑
 */
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

    /**
     * 切换桌面模式
     */
    fun toggleDeskMode() {
        viewModelScope.launch {
            serviceConnectionManager.toggleDeskMode()
        }
    }

    /**
     * 更新服务器地址
     */
    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            settingRepository.set("server_url", url)
            serviceConnectionManager.reconnect()
        }
    }

    /**
     * 显示服务器配置对话框
     */
    fun showServerConfigDialog() {
        _showServerConfigDialog.value = true
    }

    /**
     * 隐藏服务器配置对话框
     */
    fun hideServerConfigDialog() {
        _showServerConfigDialog.value = false
    }

    /**
     * 设置权限状态
     */
    fun setPermissionGranted(granted: Boolean) {
        _hasPermission.value = granted
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

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/example/hiai/viewmodel/VoiceAssistantViewModel.kt
git commit -m "refactor: 重构 VoiceAssistantViewModel 为业务逻辑层"
```

---

### 任务 14：简化 MainActivity

**文件：**
- 修改：`app/src/main/java/com/example/hiai/MainActivity.kt`

- [ ] **步骤 1：简化 MainActivity**

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: VoiceAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 检查权限
        if (PermissionHelper.hasAllPermissions(this)) {
            viewModel.setPermissionGranted(true)
        } else {
            PermissionHelper.requestPermissions(this)
        }

        setContent {
            VoiceAssistantTheme {
                when {
                    !viewModel.hasPermission.collectAsState().value -> {
                        PermissionRequestScreen(
                            onRequestPermission = {
                                PermissionHelper.requestPermissions(this)
                            }
                        )
                    }
                    viewModel.isDeskMode.collectAsState().value -> {
                        DeskModeScreen(viewModel)
                    }
                    else -> {
                        NormalModeScreen(viewModel)
                    }
                }
            }
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`./gradlew assembleDebug`
预期：成功，无错误

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/example/hiai/MainActivity.kt
git commit -m "refactor: 简化 MainActivity 为纯 UI 层"
```

---

### 任务 15：优化包结构

**文件：**
- 移动文件到新的包结构

- [ ] **步骤 1：创建新的包结构**

创建以下目录：
- `app/src/main/java/com/example/hiai/data/network/`
- `app/src/main/java/com/example/hiai/data/audio/`
- `app/src/main/java/com/example/hiai/data/database/`
- `app/src/main/java/com/example/hiai/presentation/main/`
- `app/src/main/java/com/example/hiai/presentation/screen/`

- [ ] **步骤 2：移动文件**

按照设计文档的文件移动映射表移动文件：
- `audio/AudioProcessor.kt` → `data/audio/AudioProcessor.kt`
- `audio/OpusCodec.kt` → `data/audio/OpusCodec.kt`
- `audio/WakeWordDetector.kt` → `data/audio/WakeWordDetector.kt`
- `network/NetworkManager.kt` → `data/network/NetworkManager.kt`
- `data/model/Entities.kt` → `data/database/entity/Entities.kt`
- `data/dao/Daos.kt` → `data/database/dao/Daos.kt`
- `viewmodel/VoiceAssistantViewModel.kt` → `presentation/main/VoiceAssistantViewModel.kt`

- [ ] **步骤 3：更新 import 语句**

更新所有受影响文件的 import 语句

- [ ] **步骤 4：编译验证**

运行：`./gradlew assembleDebug`
预期：成功，无错误

- [ ] **步骤 5：Commit**

```bash
git add -A
git commit -m "refactor: 优化包结构"
```

---

### 任务 16：最终验证

- [ ] **步骤 1：编译验证**

运行：`./gradlew assembleDebug`
预期：成功，无错误

- [ ] **步骤 2：安装并运行应用**

运行：`./gradlew installDebug`
预期：应用能正常安装和启动

- [ ] **步骤 3：功能验证**

验证以下功能：
1. ✅ 应用能正常启动
2. ✅ 服务能正常启动和常驻
3. ✅ 能连接到服务端
4. ✅ 能进行语音唤醒
5. ✅ 能进行录音和播放
6. ✅ UI 能正常显示和交互
7. ✅ 配置能正常保存和读取

- [ ] **步骤 4：最终 Commit**

```bash
git add -A
git commit -m "refactor: 完成 HIAI 项目架构重构"
```

---

## 验收标准

1. ✅ 应用能正常启动和运行
2. ✅ VoiceAssistantService 职责清晰，只负责协调
3. ✅ 各管理器职责单一，易于测试
4. ✅ MainActivity 只负责 UI 展示
5. ✅ ViewModel 包含所有业务逻辑
6. ✅ 使用 Hilt 进行依赖注入
7. ✅ 包结构清晰，按层次分包
8. ✅ 代码可编译，无错误
9. ✅ 功能完整，无回归

---

## 风险控制

如果某个任务出现问题：

1. 立即停止
2. 使用 `git checkout` 回滚到上一个稳定版本
3. 分析问题原因
4. 调整方案后重新开始该任务
