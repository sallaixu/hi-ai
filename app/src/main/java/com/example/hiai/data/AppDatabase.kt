package com.example.hiai.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.hiai.data.dao.ChatHistoryDao
import com.example.hiai.data.dao.SettingDao
import com.example.hiai.data.model.ChatHistory
import com.example.hiai.data.model.Setting

/**
 * 应用数据库
 */
@Database(
    entities = [
        Setting::class,
        ChatHistory::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingDao(): SettingDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    
    companion object {
        const val DATABASE_NAME = "hiai_database"
    }
}
