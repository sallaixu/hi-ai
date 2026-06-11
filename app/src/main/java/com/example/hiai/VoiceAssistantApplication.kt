package com.example.hiai

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * 应用入口
 *
 * 配置 Koin 依赖注入
 */
class VoiceAssistantApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化 Koin
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@VoiceAssistantApplication)
            // TODO: 添加模块
            // modules(appModule)
        }
    }
}
