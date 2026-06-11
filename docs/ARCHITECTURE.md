# 项目架构说明

## 包结构

```
com.example.hiai
├── domain                    # 领域层
│   ├── model                 # 状态模型
│   │   ├── ConnectionState.kt
│   │   ├── AudioSessionState.kt
│   │   ├── ServiceState.kt
│   │   └── WakeWordState.kt
│   └── contract              # 接口契约
│       ├── INetworkConnectionManager.kt
│       ├── IAudioSessionManager.kt
│       ├── IWakeWordManager.kt
│       └── IServiceStateManager.kt
├── service                   # 服务层
│   ├── VoiceAssistantService.kt
│   ├── VoiceAssistantServiceCoordinator.kt
│   └── manager               # 管理器实现
│       ├── NetworkConnectionManager.kt
│       ├── AudioSessionManager.kt
│       ├── WakeWordManager.kt
│       ├── ServiceStateManager.kt
│       └── ServiceConnectionManager.kt
├── viewmodel                 # 视图模型
│   ├── VoiceAssistantViewModel.kt
│   └── VoiceAssistantViewModelNew.kt
├── di                        # 依赖注入
│   └── AppModule.kt
├── audio                     # 音频处理
├── network                   # 网络通信
├── ui                        # UI 层
│   ├── screens               # 屏幕
│   └── theme                 # 主题
└── util                      # 工具类
```

## 架构层次

### 1. 领域层 (Domain Layer)
- **model**: 使用 sealed class 定义状态，提供类型安全的状态管理
- **contract**: 定义接口契约，提高可测试性和可替换性

### 2. 服务层 (Service Layer)
- **VoiceAssistantService**: 原始服务实现
- **VoiceAssistantServiceCoordinator**: 新的服务协调者，使用 Manager 架构
- **manager**: 各种管理器实现，遵循单一职责原则

### 3. 表现层 (Presentation Layer)
- **viewmodel**: 管理 UI 状态和业务逻辑
- **ui**: Compose UI 组件

### 4. 基础设施层 (Infrastructure Layer)
- **audio**: 音频处理相关
- **network**: 网络通信相关
- **di**: 依赖注入配置

## 依赖注入

使用 Koin 进行依赖注入，配置在 `AppModule.kt` 中。

## 状态管理

使用 StateFlow 进行状态管理，所有状态变化都是响应式的。

## 重构进度

### 已完成
- ✅ 领域层模型和接口
- ✅ 所有 Manager 实现
- ✅ VoiceAssistantServiceCoordinator
- ✅ VoiceAssistantViewModelRefactored
- ✅ ServiceConnectionManager

### 待完成
- ⏳ UI 层重构（Screen 接口适配）
- ⏳ 完整集成测试
