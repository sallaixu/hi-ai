package com.example.hiai.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 设置实体
 * 
 * 存储应用配置项
 */
@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 聊天历史实体
 * 
 * 存储与助手的对话记录
 */
@Entity(tableName = "chat_history")
data class ChatHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
