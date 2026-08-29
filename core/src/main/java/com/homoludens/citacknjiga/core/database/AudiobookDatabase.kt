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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoomEnumConverters::class)
public abstract class AudiobookDatabase : RoomDatabase() {
    public abstract fun audiobookDao(): AudiobookDao

    public companion object {
        public fun create(context: Context, name: String = "audiobook.db"): AudiobookDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AudiobookDatabase::class.java,
                name,
            ).build()
    }
}
