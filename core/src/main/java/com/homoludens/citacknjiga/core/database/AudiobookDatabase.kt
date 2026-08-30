package com.homoludens.citacknjiga.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BookProjectEntity::class,
        ChapterEntity::class,
        NarrationBlockEntity::class,
        AudioSegmentEntity::class,
        GenerationRunEntity::class,
        ModelPackageEntity::class,
        PlaybackPositionEntity::class,
        ExportJobEntity::class,
        ExportJobChapterEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(RoomEnumConverters::class)
public abstract class AudiobookDatabase : RoomDatabase() {
    public abstract fun audiobookDao(): AudiobookDao

    public companion object {
        public val MIGRATION_1_2: androidx.room.migration.Migration = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE export_job ADD COLUMN manifest_name TEXT")
                database.execSQL("ALTER TABLE export_job ADD COLUMN cover_name TEXT")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `export_job_chapter` (
                        `export_job_id` TEXT NOT NULL,
                        `chapter_id` TEXT NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `source_segment_ids_json` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `file_uri` TEXT,
                        `temporary_uri` TEXT,
                        `sha256` TEXT,
                        `size_bytes` INTEGER,
                        `duration_ms` INTEGER,
                        `status` TEXT NOT NULL,
                        `attempt_count` INTEGER NOT NULL,
                        `last_error` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`export_job_id`, `chapter_id`),
                        FOREIGN KEY(`export_job_id`) REFERENCES `export_job`(`id`) ON UPDATE CASCADE ON DELETE CASCADE,
                        FOREIGN KEY(`chapter_id`) REFERENCES `chapter`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_export_job_chapter_export_job_id_ordinal ON export_job_chapter(export_job_id, ordinal)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_export_job_chapter_export_job_id_status ON export_job_chapter(export_job_id, status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_export_job_chapter_chapter_id ON export_job_chapter(chapter_id)")
            }
        }

        public fun create(context: Context, name: String = "audiobook.db"): AudiobookDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AudiobookDatabase::class.java,
                name,
            ).addMigrations(MIGRATION_1_2).build()
    }
}
