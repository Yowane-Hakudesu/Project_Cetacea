package com.whalechan.agent.data.log

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [StepLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StepLogDatabase : RoomDatabase() {

    abstract fun stepLogDao(): StepLogDao

    companion object {
        private const val DB_NAME = "whale_logs.db"

        @Volatile
        private var instance: StepLogDatabase? = null

        fun get(context: Context): StepLogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                StepLogDatabase::class.java,
                DB_NAME
            ).build().also { instance = it }
        }
    }
}
