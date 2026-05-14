package com.catcatch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room 数据库
 */
@Database(
    entities = [TaskEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}
