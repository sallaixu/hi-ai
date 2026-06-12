package com.example.hiai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hiai.service.VoiceAssistantService
import com.example.hiai.ui.theme.*

/**
 * 权限请求界面
 */
@Composable
fun PermissionRequestScreen(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "需要权限",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(
                text = "本应用需要录音权限才能进行语音对话，需要网络权限才能连接服务端。",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("授予权限")
            }
        }
    }
}

/**
 * 万年历时钟组件（带防烧屏策略）
 * 显示大时钟、年月日、星期
 * 
 * 防烧屏策略：
 * - 每 5 分钟随机偏移 1-2 像素
 * - 每秒刷新，动态变化减少烧屏风险
 */
@Composable
fun ClockCalendar(
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(java.util.Calendar.getInstance()) }
    var offset by remember { mutableStateOf(0f) }
    
    // 每秒更新时间
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime = java.util.Calendar.getInstance()
        }
    }
    
    // 每 5 分钟随机偏移，防止烧屏
    LaunchedEffect(Unit) {
        while (true) {
            // 随机偏移 -2 到 +2 dp
            offset = (Math.random() * 4 - 2).toFloat()
            kotlinx.coroutines.delay(300_000) // 5 分钟
        }
    }
    
    val hour = currentTime.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = currentTime.get(java.util.Calendar.MINUTE)
    val second = currentTime.get(java.util.Calendar.SECOND)
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
            .offset { IntOffset(offset.dp.roundToPx(), 0) }
    ) {
        // 大时钟
        Text(
            text = String.format("%02d:%02d:%02d", hour, minute, second),
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

/**
 * 助手内容组件
 * 显示助手状态和 TTS 消息滚动列表
 */
@Composable
fun AssistantContent(
    ttsText: String,
    ttsMessages: List<String>,
    serviceState: VoiceAssistantService.ServiceState,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
    ) {
        if (ttsMessages.isNotEmpty()) {
            // 显示 TTS 消息滚动列表
            val listState = rememberLazyListState(ttsMessages)
            
            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                reverseLayout = true
            ) {
                items(ttsMessages.reversed()) { message ->
                    Text(
                        text = message,
                        color = if (message == ttsMessages.last()) DeskModeTtsText else DeskModeStatusText,
                        fontSize = if (message == ttsMessages.last()) 28.sp else 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        } else {
            // 状态指示器
            val color = when (serviceState) {
                is VoiceAssistantService.ServiceState.Idle -> IdleColor
                is VoiceAssistantService.ServiceState.Listening -> ListeningColor
                is VoiceAssistantService.ServiceState.Speaking -> SpeakingColor
                is VoiceAssistantService.ServiceState.Error -> ErrorColor
            }
            
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
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

@Composable
private fun rememberLazyListState(items: List<*>): androidx.compose.foundation.lazy.LazyListState {
    val listState = remember { androidx.compose.foundation.lazy.LazyListState(0, 0) }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    return listState
}

/**
 * 底部状态栏组件
 */
@Composable
fun BottomStatusBar(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val statusText = if (isConnected) "已连接" else "待唤醒 · 低功耗模式"
    
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

@Composable
private fun getStateText(serviceState: VoiceAssistantService.ServiceState): String {
    return when (serviceState) {
        is VoiceAssistantService.ServiceState.Idle -> "待命中..."
        is VoiceAssistantService.ServiceState.Listening -> "正在聆听..."
        is VoiceAssistantService.ServiceState.Speaking -> "正在说话..."
        is VoiceAssistantService.ServiceState.Error -> "出错了"
    }
}

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
    ttsMessages: List<String>,
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
                        ttsMessages = ttsMessages,
                        serviceState = serviceState
                    )
                }
            }
            
            // 底部状态栏
            BottomStatusBar(isConnected = isConnected)
            
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

/**
 * 普通模式界面（设置和配置）
 */
@Composable
fun NormalModeScreen(
    onToggleDeskMode: () -> Unit,
    onShowServerConfig: () -> Unit,
    onShowWakeWordConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "AI 语音助手",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            // 状态卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "服务状态",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "运行中",
                        color = ListeningColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            // 桌面模式按钮
            Button(
                onClick = onToggleDeskMode,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("进入桌面模式")
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 设置选项
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            OutlinedButton(
                onClick = onShowServerConfig,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("服务器配置")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedButton(
                onClick = onShowWakeWordConfig,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("唤醒词配置")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedButton(
                onClick = { /* TODO: 打开关于页面 */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关于")
            }
        }
    }
}
