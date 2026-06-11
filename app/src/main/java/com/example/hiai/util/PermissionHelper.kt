package com.example.hiai.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理工具类
 */
object PermissionHelper {
    
    // 需要的权限列表
    val REQUIRED_PERMISSIONS = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要通知权限
            // Manifest.permission.POST_NOTIFICATIONS
        }
    }.toTypedArray()
    
    const val PERMISSION_REQUEST_CODE = 1001
    
    /**
     * 检查是否所有必要权限都已授予
     */
    fun hasAllPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 请求必要权限
     */
    fun requestPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            REQUIRED_PERMISSIONS,
            PERMISSION_REQUEST_CODE
        )
    }
    
    /**
     * 检查权限结果
     */
    fun verifyPermissions(grantResults: IntArray): Boolean {
        return grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
    
    /**
     * 获取被拒绝的权限列表
     */
    fun getDeniedPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 检查是否应该显示权限解释（用户拒绝了权限但没有选择"不再询问"）
     */
    fun shouldShowPermissionRationale(activity: Activity): Boolean {
        return REQUIRED_PERMISSIONS.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }
}
