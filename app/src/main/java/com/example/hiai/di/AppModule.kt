package com.example.hiai.di

import com.example.hiai.domain.contract.IAudioSessionManager
import com.example.hiai.domain.contract.INetworkConnectionManager
import com.example.hiai.domain.contract.IServiceStateManager
import com.example.hiai.domain.contract.IWakeWordManager
import org.koin.dsl.module

/**
 * 应用依赖注入模块
 *
 * 注册所有核心组件的实现
 */
val appModule = module {
    // TODO: 注册 Manager 实现
    // single<INetworkConnectionManager> { NetworkConnectionManager(get()) }
    // single<IAudioSessionManager> { AudioSessionManager(get()) }
    // single<IWakeWordManager> { WakeWordManager(get()) }
    // single<IServiceStateManager> { ServiceStateManager() }
}

/**
 * 网络模块
 */
val networkModule = module {
    // TODO: 注册网络相关依赖
    // single { NetworkManager() }
}

/**
 * 音频模块
 */
val audioModule = module {
    // TODO: 注册音频相关依赖
    // single { AudioProcessor(get()) }
}

/**
 * 数据库模块
 */
val databaseModule = module {
    // TODO: 注册数据库相关依赖
    // single { AppDatabase.getInstance(get()) }
}

/**
 * 所有 Koin 模块列表
 */
val allModules = listOf(
    appModule,
    networkModule,
    audioModule,
    databaseModule
)
