package com.example.hiai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.hiai.service.VoiceAssistantService
import com.example.hiai.ui.screens.DeskModeScreen
import com.example.hiai.ui.screens.NormalModeScreen
import com.example.hiai.ui.screens.PermissionRequestScreen
import com.example.hiai.ui.theme.VoiceAssistantTheme
import com.example.hiai.util.PermissionHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    
    private var service: VoiceAssistantService? = null
    private var serviceState by mutableStateOf<VoiceAssistantService.ServiceState>(
        VoiceAssistantService.ServiceState.Idle
    )
    private var isDeskMode by mutableStateOf(false)
    private var hasPermission by mutableStateOf(false)
    private var showServerConfigDialog by mutableStateOf(false)
    private var showWakeWordConfigDialog by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private var ttsText by mutableStateOf("")
    private var ttsMessages by mutableStateOf<List<String>>(emptyList())
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VoiceAssistantService.LocalBinder
            service = localBinder.getService()
            
            // 观察服务状态
            lifecycleScope.launchWhenStarted {
                service?.serviceState?.collect { state ->
                    serviceState = state
                }
            }
            
            lifecycleScope.launchWhenStarted {
                service?.isDeskMode?.collect { deskMode ->
                    isDeskMode = deskMode
                }
            }
            
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
            
            lifecycleScope.launchWhenStarted {
                service?.ttsMessages?.collect { messages ->
                    ttsMessages = messages
                }
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 检查权限
        if (PermissionHelper.hasAllPermissions(this)) {
            hasPermission = true
            initializeApp()
        } else {
            PermissionHelper.requestPermissions(this)
        }
        
        setContent {
            VoiceAssistantTheme {
                if (!hasPermission) {
                    // 权限请求界面
                    PermissionRequestScreen(
                        onRequestPermission = {
                            PermissionHelper.requestPermissions(this)
                        }
                    )
                } else if (isDeskMode) {
                    DeskModeScreen(
                        isConnected = isConnected,
                        ttsText = ttsText,
                        ttsMessages = ttsMessages,
                        serviceState = serviceState,
                        onToggleDeskMode = { toggleDeskMode() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    NormalModeScreen(
                        onToggleDeskMode = { toggleDeskMode() },
                        onShowServerConfig = { showServerConfigDialog = true },
                        onShowWakeWordConfig = { showWakeWordConfigDialog = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                // 服务器配置对话框
                if (showServerConfigDialog) {
                    ServerConfigDialog(
                        onDismiss = { showServerConfigDialog = false },
                        onSave = { serverUrl, deviceId, token ->
                            saveServerConfig(serverUrl, deviceId, token)
                        }
                    )
                }
                
                // 唤醒词配置对话框
                if (showWakeWordConfigDialog) {
                    WakeWordConfigDialog(
                        currentKeywords = service?.getWakeWords() ?: VoiceAssistantService.DEFAULT_WAKE_WORDS,
                        onDismiss = { showWakeWordConfigDialog = false },
                        onSave = { keywords ->
                            val success = service?.updateWakeWords(keywords) ?: false
                            showWakeWordConfigDialog = false
                        }
                    )
                }
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PermissionHelper.PERMISSION_REQUEST_CODE) {
            if (PermissionHelper.verifyPermissions(grantResults)) {
                hasPermission = true
                initializeApp()
            } else {
                // 权限被拒绝，显示提示
                // TODO: 显示需要权限的提示
            }
        }
    }
    
    private fun initializeApp() {
        // 启动服务
        startVoiceAssistantService()
        
        // 绑定服务
        bindService(
            Intent(this, VoiceAssistantService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    private fun startVoiceAssistantService() {
        val intent = Intent(this, VoiceAssistantService::class.java).apply {
            action = VoiceAssistantService.ACTION_START
        }
        startForegroundService(intent)
    }
    
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
    
    /**
     * 显示服务器配置对话框
     */
    @Composable
    private fun ServerConfigDialog(
        onDismiss: () -> Unit,
        onSave: (String, String, String) -> Unit
    ) {
        var serverUrl by remember { mutableStateOf("") }
        var deviceId by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        
        // 从数据库加载配置
        LaunchedEffect(Unit) {
            service?.let { svc ->
                val settingRepository = svc.settingRepository
                serverUrl = settingRepository.get("server_url") ?: ""
                deviceId = settingRepository.get("device_id") ?: ""
                token = settingRepository.get("token") ?: ""
            }
        }
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("服务器配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("OTA 地址") },
                        placeholder = { Text("http://localhost:8000/ota") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "完整的 OTA 接口 URL（包含 /ota 路径）",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        label = { Text("设备 ID") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Token") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(serverUrl, deviceId, token)
                        onDismiss()
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }
    
    /**
     * 唤醒词配置对话框
     */
    @Composable
    private fun WakeWordConfigDialog(
        currentKeywords: List<String>,
        onDismiss: () -> Unit,
        onSave: (List<String>) -> Unit
    ) {
        var keywords by remember { mutableStateOf(currentKeywords) }
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("唤醒词配置") },
            text = {
                Column {
                    Text(
                        "格式：拼音音素 @显示名称\n例如：n ǐ h ǎo x iǎo zh ì @你好小智",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    keywords.forEachIndexed { index, keyword ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = keyword,
                                onValueChange = { newText ->
                                    keywords = keywords.toMutableList().also {
                                        it[index] = newText
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    keywords = keywords.toMutableList().also {
                                        it.removeAt(index)
                                    }
                                }
                            ) {
                                Text("×", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedButton(
                        onClick = {
                            keywords = keywords + ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ 添加唤醒词")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(keywords.filter { it.isNotBlank() })
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }
    
    /**
     * 保存服务器配置
     */
    private fun saveServerConfig(serverUrl: String, deviceId: String, token: String) {
        service?.let { svc ->
            lifecycleScope.launch {
                val settingRepository = svc.settingRepository
                if (serverUrl.isNotBlank()) {
                    settingRepository.set("server_url", serverUrl)
                }
                if (deviceId.isNotBlank()) {
                    settingRepository.set("device_id", deviceId)
                }
                if (token.isNotBlank()) {
                    settingRepository.set("token", token)
                }
                // 重启服务以应用新配置
                restartService()
            }
        }
    }
    
    /**
     * 重启服务
     */
    private suspend fun restartService() {
        service?.let {
            unbindService(serviceConnection)
        }
        
        val intent = Intent(this@MainActivity, VoiceAssistantService::class.java)
        stopService(intent)
        
        delay(500)
        startVoiceAssistantService()
        bindService(
            Intent(this@MainActivity, VoiceAssistantService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        service?.let { unbindService(serviceConnection) }
    }
}
