package com.mina.legadostudio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, SourceRevisionEntity::class, HttpLogEntity::class, VerificationSessionEntity::class, OperationLogEntity::class, DiagnosticSnapshotEntity::class],
    version = 13,
    exportSchema = true,
)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun dao(): StudioDao

    companion object {
        @Volatile private var instance: StudioDatabase? = null

        /**
         * v11 → v12：为 http_logs 追加归属三列 + 两个索引。
         * 非破坏性：旧数据三列保持 NULL，由归属器按 URL/Referer 证据离线判定。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `http_logs` ADD COLUMN `sourceAnchor` TEXT")
                db.execSQL("ALTER TABLE `http_logs` ADD COLUMN `contextId` TEXT")
                db.execSQL("ALTER TABLE `http_logs` ADD COLUMN `originKind` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_http_logs_sourceAnchor` ON `http_logs` (`sourceAnchor`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_http_logs_contextId` ON `http_logs` (`contextId`)")
            }
        }

        /** v12 → v13：verification_sessions 追加 kind/answer/imageData 三列支撑图片验证码会话。 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `verification_sessions` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'webview'")
                db.execSQL("ALTER TABLE `verification_sessions` ADD COLUMN `answer` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `verification_sessions` ADD COLUMN `imageData` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): StudioDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StudioDatabase::class.java, "studio-v1.db")
                .addMigrations(MIGRATION_11_12, MIGRATION_12_13)
                // A version mismatch must fail closed rather than erase saved sources.
                .build().also { instance = it }
        }
    }
}
