package com.whalechan.agent.data.log

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.whalechan.agent.data.social.SocialActionDao
import com.whalechan.agent.data.social.SocialActionEntity

@Database(
    entities = [StepLogEntity::class, SocialActionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class StepLogDatabase : RoomDatabase() {

    abstract fun stepLogDao(): StepLogDao

    abstract fun socialActionDao(): SocialActionDao

    companion object {
        private const val DB_NAME = "whale_logs.db"

        @Volatile
        private var instance: StepLogDatabase? = null

        /** v1 → v2：新增社交动作账本，不动旧日志。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `social_actions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `targetKey` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `textHash` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_social_actions_actionType` ON `social_actions` (`actionType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_social_actions_targetKey` ON `social_actions` (`targetKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_social_actions_timestamp` ON `social_actions` (`timestamp`)")
            }
        }

        fun get(context: Context): StepLogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                StepLogDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
