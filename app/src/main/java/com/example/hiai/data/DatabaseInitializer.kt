package com.example.hiai.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库初始化辅助类
 */
object DatabaseInitializer {
    
    @Volatile
    private var INSTANCE: AppDatabase? = null
    
    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                AppDatabase.DATABASE_NAME
            )
            .fallbackToDestructiveMigration() // 开发阶段使用
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // 初始化默认设置
                    INSTANCE?.let { database ->
                        // 可以在这里插入默认数据
                    }
                }
            })
            .build()
            .also { INSTANCE = it }
        }
    }
}
