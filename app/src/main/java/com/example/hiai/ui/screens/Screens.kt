package com.example.hiai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
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
 * 桌面模式界面
 * 全屏沉浸式，显示语音助手状态和波形动画
 */
@Composable
fun DeskModeScreen(
    serviceState: VoiceAssistantService.ServiceState,
    onToggleDeskMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeskModeBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            // 状态指示器
            StatusIndicator(serviceState)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 状态文本
            Text(
                text = getStateText(serviceState),
                color = DeskModeText,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 退出桌面模式按钮
            OutlinedButton(
                onClick = onToggleDeskMode,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = DeskModeAccent.copy(alpha = 0.1f),
                    contentColor = DeskModeAccent
                ),
                modifier = Modifier.border(
                    width = 1.dp,
                    color = DeskModeAccent,
                    shape = MaterialTheme.shapes.small
                )
            ) {
                Text("退出桌面模式")
            }
        }
    }
}

@Composable
private fun StatusIndicator(serviceState: VoiceAssistantService.ServiceState) {
    val color = when (serviceState) {
        is VoiceAssistantService.ServiceState.Idle -> IdleColor
        is VoiceAssistantService.ServiceState.Listening -> ListeningColor
        is VoiceAssistantService.ServiceState.Speaking -> SpeakingColor
        is VoiceAssistantService.ServiceState.Error -> ErrorColor
    }
    
    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(color)
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
 * 普通模式界面（设置和配置）
 */
@Composable
fun NormalModeScreen(
    onToggleDeskMode: () -> Unit,
    onShowServerConfig: () -> Unit,
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
                onClick = { /* TODO: 打开关于页面 */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关于")
            }
        }
    }
}
