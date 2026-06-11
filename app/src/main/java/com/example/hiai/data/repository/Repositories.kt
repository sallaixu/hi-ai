package com.example.hiai.data.repository

import com.example.hiai.data.dao.SettingDao
import com.example.hiai.data.dao.ChatHistoryDao
import com.example.hiai.data.model.Setting
import com.example.hiai.data.model.ChatHistory
import kotlinx.coroutines.flow.Flow

/**
 * 设置 Repository
 */
class SettingRepository(
    private val settingDao: SettingDao
) {
    val allSettings: Flow<List<Setting>> = settingDao.getAll()
    
    suspend fun get(key: String): String? {
        return settingDao.getByKey(key)?.value
    }
    
    suspend fun set(key: String, value: String) {
        settingDao.insert(Setting(key = key, value = value))
    }
    
    suspend fun delete(key: String) {
        settingDao.deleteByKey(key)
    }
}

/**
 * 聊天历史 Repository
 */
class ChatHistoryRepository(
    private val chatHistoryDao: ChatHistoryDao
) {
    fun getBySession(sessionId: String): Flow<List<ChatHistory>> {
        return chatHistoryDao.getBySession(sessionId)
    }
    
    suspend fun getRecent(limit: Int = 50): List<ChatHistory> {
        return chatHistoryDao.getRecent(limit)
    }
    
    suspend fun addMessage(sessionId: String, role: String, content: String): Long {
        val chatHistory = ChatHistory(
            sessionId = sessionId,
            role = role,
            content = content
        )
        return chatHistoryDao.insert(chatHistory)
    }
    
    suspend fun deleteSession(sessionId: String) {
        chatHistoryDao.deleteBySession(sessionId)
    }
    
    suspend fun deleteAll() {
        chatHistoryDao.deleteAll()
    }
}
