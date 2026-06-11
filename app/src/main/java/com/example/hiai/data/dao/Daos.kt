package com.example.hiai.data.dao

import androidx.room.*
import com.example.hiai.data.model.Setting
import com.example.hiai.data.model.ChatHistory
import kotlinx.coroutines.flow.Flow

/**
 * 设置 DAO
 */
@Dao
interface SettingDao {
    @Query("SELECT * FROM settings WHERE key = :key")
    suspend fun getByKey(key: String): Setting?
    
    @Query("SELECT * FROM settings")
    fun getAll(): Flow<List<Setting>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: Setting)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(settings: List<Setting>)
    
    @Delete
    suspend fun delete(setting: Setting)
    
    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun deleteByKey(key: String)
}

/**
 * 聊天历史 DAO
 */
@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_history WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getBySession(sessionId: String): Flow<List<ChatHistory>>
    
    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ChatHistory>
    
    @Query("SELECT * FROM chat_history WHERE id = :id")
    suspend fun getById(id: Long): ChatHistory?
    
    @Insert
    suspend fun insert(chatHistory: ChatHistory): Long
    
    @Insert
    suspend fun insertAll(chatHistories: List<ChatHistory>)
    
    @Delete
    suspend fun delete(chatHistory: ChatHistory)
    
    @Query("DELETE FROM chat_history WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
    
    @Query("DELETE FROM chat_history")
    suspend fun deleteAll()
}
