# 桌面模式黑底万年历与 TTS 展示实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将桌面模式改造为横屏黑底界面：WebSocket 断开时显示大时钟万年历，唤醒后切换为助手内容，并在 TTS 播放时展示当前文本。

**架构：** UI 层新增状态（待唤醒/助手/TTS）与组合式组件；服务层暴露连接状态与 TTS 文本 Flow；Activity 汇聚这些状态并驱动桌面模式界面切换。保持纯黑背景与极简视觉层级。

**技术栈：** Kotlin、Jetpack Compose、StateFlow、OkHttp WebSocket、现有 VoiceAssistantService 架构。

---

## 文件修改清单

| 文件 | 职责 |
|------|------|
| `ui/screens/Screens.kt` | 拆分并重构 DeskModeScreen：新增万年历时钟组件、助手内容组件、底部状态栏、状态切换逻辑；横屏布局与黑底。 |
| `service/VoiceAssistantService.kt` | 新增 `_connectionState` 与 `_ttsText` StateFlow，暴露连接状态与当前 TTS 文本给 UI。 |
| `MainActivity.kt` | 订阅连接状态与 TTS 文本，传递给 DeskModeScreen；移除 DeskModeScreen 对旧 serviceState 的直接依赖。 |
| `network/NetworkManager.kt` | 在 WebSocketListener 的 onClosing/onFailure 中暴露断开事件；现有 messageFlow 已承载 TtsMessage。 |
| `ui/theme/Color.kt` | 新增桌面模式专用颜色常量（纯黑背景、时钟文字、日期文字、状态栏文字）。 |
| `MainActivity.kt` | 进入桌面模式时设置屏幕常亮（FLAG_KEEP_SCREEN_ON），退出时清除。 |

---

## 任务 1：在 VoiceAssistantService 中暴露连接状态与 TTS 文本

**文件：**
- 修改：`d:\project\android\hiai\app\src\main\java\com\example\hiai\service\VoiceAssistantService.kt`

- [ ] **步骤 1：新增连接状态 StateFlow**

在 `VoiceAssistantService` 中添加：

```kotlin
// 连接状态：true 表示已连接，false 表示已断开
private val _isConnected = MutableStateFlow(false)
val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

// 当前 TTS 文本（空表示无 TTS 正在播放）
private val _ttsText = MutableStateFlow("")
val ttsText: StateFlow<String> = _ttsText.asStateFlow()
```

- [ ] **步骤 2：在 WebSocket 打开时设置连接状态**

在 `connectToServer()` 的消息监听中，当收到 `HelloResponse` 时设置：

```kotlin
is HelloResponse -> {
    _isConnected.value = true
    // ... 现有逻辑
}
```

- [ ] **步骤 3：在 WebSocket 断开时重置连接状态**

在 `NetworkManager` 的 WebSocketListener 回调中，新增断开通知机制，然后在 `VoiceAssistantService` 中监听：

在 `NetworkManager.kt` 的 WebSocketListener 中：

```kotlin
override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
    Log.d(TAG, "  - WebSocket closing: code=$code, reason=$reason")
    isConnected = false
    _messageFlow.tryEmit(ConnectionClosed(code, reason))
    webSocket.close(1000, null)
}

override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
    Log.e(TAG, "  - WebSocket failure", t)
    isConnected = false
    _messageFlow.tryEmit(ConnectionFailed(t.message ?: "Unknown error"))
}
```

在 `ProtocolModels.kt` 中新增：

```kotlin
@Serializable
data class ConnectionClosed(
    @SerialName("type")
    override val type: String = "connection_closed",
    @SerialName("code")
    val code: Int,
    @SerialName("reason")
    val reason: String
) : WebSocketMessage()

@Serializable
data class ConnectionFailed(
    @SerialName("type")
    override val type: String = "connection_failed",
    @SerialName("error")
    val error: String
) : WebSocketMessage()
```

在 `VoiceAssistantService` 的消息处理中：

```kotlin
is ConnectionClosed, is ConnectionFailed -> {
    _isConnected.value = false
    _ttsText.value = ""
}
```

- [ ] **步骤 4：在 TTS 消息处理中更新 TTS 文本**

在 `VoiceAssistantService` 的消息处理中：

```kotlin
is TtsMessage -> {
    when (message.state) {
        "start" -> {
            _ttsText.value = message.text ?: ""
        }
        "sentence_start" -> {
            _ttsText.value = message.text ?: ""
        }
        "stop" -> {
            // TTS 结束后保留最后一句，或清空
            // 这里选择保留最后一句，让 UI 决定何时清空
        }
    }
    // ... 现有播放逻辑
}
```

- [ ] **步骤 5：验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

---

## 任务 2：在 MainActivity 中订阅连接状态与 TTS 文本

**文件：**
- 修改：`d:\project\android\hiai\app\src\main\java\com\example\hiai\MainActivity.kt`

- [ ] **步骤 1：新增状态变量**

在 `MainActivity` 中添加：

```kotlin
private var isConnected by mutableStateOf(false)
private var ttsText by mutableStateOf("")
```

- [ ] **步骤 2：在服务连接中订阅新状态**

在 `serviceConnection.onServiceConnected` 中：

```kotlin
lifecycleScope.launchWhenStarted {
    service?.isConnected?.collect { connected ->
        isConnected = connected
    }
}

lifecycleScope.launchWhenStarted {
    service?.ttsText?.collect { text ->
        ttsText = text
    }
}
```

- [ ] **步骤 3：传递给 DeskModeScreen**

修改 `DeskModeScreen` 调用：

```kotlin
DeskModeScreen(
    isConnected = isConnected,
    ttsText = ttsText,
    serviceState = serviceState,
    onToggleDeskMode = { toggleDeskMode() },
    modifier = Modifier.fillMaxSize()
)
```

- [ ] **步骤 4：实现屏幕常亮**

在 `toggleDeskMode()` 方法中添加屏幕常亮逻辑：

```kotlin
private fun toggleDeskMode() {
    val intent = Intent(this, VoiceAssistantService::class.java).apply {
        action = VoiceAssistantService.ACTION_TOGGLE_DESK_MODE
    }
    startService(intent)
    
    // 切换屏幕常亮状态
    if (isDeskMode) {
        // 退出桌面模式，清除屏幕常亮
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        // 进入桌面模式，设置屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
```

- [ ] **步骤 5：验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

---

## 任务 3：新增桌面模式专用颜色常量

**文件：**
- 修改：`d:\project\android\hiai\app\src\main\java\com\example\hiai\ui\theme\Color.kt`

- [ ] **步骤 1：新增颜色常量**

在 `Color.kt` 中添加：

```kotlin
// 桌面模式颜色
val DeskModeBlack = Color(0xFF000000)           // 纯黑背景
val DeskModeClockText = Color(0xFFE0E0E0)       // 时钟文字（浅灰白）
val DeskModeDateText = Color(0xFFB0B0B0)        // 日期文字（稍暗）
val DeskModeStatusText = Color(0xFF808080)      // 状态栏文字（灰色）
val DeskModeTtsText = Color(0xFFE0E0E0)         // TTS 文本（浅灰白）
```

- [ ] **步骤 2：验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

---

## 任务 4：重构 DeskModeScreen 为状态驱动的组合式界面

**文件：**
- 修改：`d:\project\android\hiai\app\src\main\java\com\example\hiai\ui\screens\Screens.kt`

- [ ] **步骤 1：新增万年历时钟组件**

```kotlin
/**
 * 万年历时钟组件
 * 显示大时钟、年月日、星期
 */
@Composable
fun ClockCalendar(
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(java.util.Calendar.getInstance()) }
    
    // 每秒更新时间
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime = java.util.Calendar.getInstance()
        }
    }
    
    val hour = currentTime.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = currentTime.get(java.util.Calendar.MINUTE)
    val year = currentTime.get(java.util.Calendar.YEAR)
    val month = currentTime.get(java.util.Calendar.MONTH) + 1
    val day = currentTime.get(java.util.Calendar.DAY_OF_MONTH)
    val dayOfWeek = when (currentTime.get(java.util.Calendar.DAY_OF_WEEK)) {
        java.util.Calendar.SUNDAY -> "星期日"
        java.util.Calendar.MONDAY -> "星期一"
        java.util.Calendar.TUESDAY -> "星期二"
        java.util.Calendar.WEDNESDAY -> "星期三"
        java.util.Calendar.THURSDAY -> "星期四"
        java.util.Calendar.FRIDAY -> "星期五"
        java.util.Calendar.SATURDAY -> "星期六"
        else -> ""
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        // 大时钟
        Text(
            text = String.format("%02d:%02d", hour, minute),
            color = DeskModeClockText,
            fontSize = 96.sp,
            fontWeight = FontWeight.Light
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 年月日
        Text(
            text = String.format("%d / %02d / %02d", year, month, day),
            color = DeskModeDateText,
            fontSize = 32.sp,
            fontWeight = FontWeight.Normal
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 星期
        Text(
            text = dayOfWeek,
            color = DeskModeDateText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
```

- [ ] **步骤 2：新增助手内容组件**

```kotlin
/**
 * 助手内容组件
 * 显示助手状态和 TTS 文本
 */
@Composable
fun AssistantContent(
    ttsText: String,
    serviceState: VoiceAssistantService.ServiceState,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        if (ttsText.isNotEmpty()) {
            // 显示 TTS 文本
            Text(
                text = ttsText,
                color = DeskModeTtsText,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp)
            )
        } else {
            // 显示状态提示
            Text(
                text = getStateText(serviceState),
                color = DeskModeStatusText,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
```

- [ ] **步骤 3：新增底部状态栏组件**

```kotlin
/**
 * 底部状态栏组件
 */
@Composable
fun BottomStatusBar(
    isConnected: Boolean,
    isWakingUp: Boolean,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        !isConnected -> "待唤醒 · 低功耗模式"
        isWakingUp -> "已唤醒"
        else -> "已连接"
    }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = statusText,
            color = DeskModeStatusText,
            fontSize = 14.sp
        )
    }
}
```

- [ ] **步骤 4：重构 DeskModeScreen 为状态驱动**

```kotlin
/**
 * 桌面模式界面
 * 全屏沉浸式，横屏黑底
 * - 断开连接：显示万年历时钟
 * - 已连接：显示助手内容
 */
@Composable
fun DeskModeScreen(
    isConnected: Boolean,
    ttsText: String,
    serviceState: VoiceAssistantService.ServiceState,
    onToggleDeskMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 判断当前显示模式
    val showClock = !isConnected
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeskModeBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            // 主区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (showClock) {
                    // 待唤醒：万年历时钟
                    ClockCalendar()
                } else {
                    // 已唤醒：助手内容
                    AssistantContent(
                        ttsText = ttsText,
                        serviceState = serviceState
                    )
                }
            }
            
            // 底部状态栏
            BottomStatusBar(
                isConnected = isConnected,
                isWakingUp = isConnected
            )
            
            // 退出按钮
            OutlinedButton(
                onClick = onToggleDeskMode,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = DeskModeStatusText
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("退出桌面模式")
            }
        }
    }
}
```

- [ ] **步骤 5：移除旧的 StatusIndicator 和相关代码**

删除原有的 `StatusIndicator` 和 `getStateText` 函数，已在新组件中实现。

- [ ] **步骤 6：验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

---

## 任务 5：验证功能完整性

**文件：**
- 无需修改，仅验证

- [ ] **步骤 1：安装到设备**

运行：`adb install -r app\build\outputs\apk\debug\app-debug.apk`
预期：Success

- [ ] **步骤 2：启动应用并进入桌面模式**

手动测试：
1. 启动应用
2. 点击"进入桌面模式"
3. 验证界面为横屏黑底

- [ ] **步骤 3：验证待唤醒界面**

手动测试：
1. 确保服务器未连接
2. 进入桌面模式
3. 验证显示大时钟、年月日、星期
4. 验证底部显示"待唤醒 · 低功耗模式"

- [ ] **步骤 4：验证助手界面**

手动测试：
1. 连接服务器
2. 进入桌面模式
3. 验证显示助手状态（"待命中..."）
4. 验证底部显示"已连接"

- [ ] **步骤 5：验证 TTS 文本展示**

手动测试：
1. 连接服务器并唤醒
2. 与助手对话
3. 验证 TTS 播放时界面显示当前文本

- [ ] **步骤 6：验证断开重连**

手动测试：
1. 在桌面模式下断开服务器
2. 验证界面切回万年历时钟
3. 重新连接服务器
4. 验证界面切回助手内容

- [ ] **步骤 7：验证屏幕常亮**

手动测试：
1. 进入桌面模式
2. 等待 30 秒，验证屏幕未自动熄灭
3. 退出桌面模式
4. 等待系统自动锁屏时间，验证屏幕正常熄灭

---

## 验收标准

完成后应满足：

1. 桌面模式背景为纯黑 `#000000`
2. 断开连接时显示大时钟 + 年月日 + 星期
3. 连接成功后显示助手状态
4. TTS 播放时显示当前文本
5. 底部状态栏显示当前模式
6. 所有界面元素在横屏下排版合理
7. 不影响现有语音交互功能
8. 进入桌面模式后屏幕保持常亮，退出后恢复正常

## 注意事项

- 保持 YAGNI：只实现规格中要求的功能，不添加额外特性
- 保持 DRY：时间格式化、状态文本等复用逻辑集中在组件内
- 频繁 commit：每个任务完成后立即 commit
- 遵循现有代码风格：使用 Compose、StateFlow、Material3
