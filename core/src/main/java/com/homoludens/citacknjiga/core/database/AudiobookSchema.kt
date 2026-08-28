package com.homoludens.citacknjiga.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

public enum class BookProjectStatus {
    IMPORTING,
    READY,
    GENERATING,
    PAUSED,
    COMPLETED,
    FAILED,
}

public enum class ChapterStatus {
    PENDING,
    GENERATING,
    PARTIAL,
    READY,
    FAILED,
}

public enum class NarrationBlockType {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    QUOTE,
    POETRY,
    CAPTION,
    NOTE,
    SCENE_BREAK,
    SKIPPED,
}

public enum class NarrationBlockStatus {
    PENDING,
    PROCESSED,
    FAILED,
}

public enum class AudioSegmentStatus {
    PENDING,
    GENERATING,
    READY,
    STALE,
    FAILED,
}

public enum class GenerationRunStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

public enum class ModelPackageStatus {
    INSTALLED,
    ACTIVE,
    RETIRED,
}

public enum class ExportJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Entity(
    tableName = "book_project",
    indices = [
        Index(value = ["source_fingerprint"], unique = true),
        Index(value = ["status"]),
    ],
)
public data class BookProjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String? = null,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String,
    @ColumnInfo(name = "source_fingerprint")
    val sourceFingerprint: String,
    @ColumnInfo(name = "source_path")
    val sourcePath: String? = null,
    @ColumnInfo(name = "cover_path")
    val coverPath: String? = null,
    @ColumnInfo(name = "language", defaultValue = "'sr'")
    val language: String = "sr",
    @ColumnInfo(name = "status")
    val status: BookProjectStatus = BookProjectStatus.IMPORTING,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter",
    foreignKeys = [
        ForeignKey(
            entity = BookProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_project_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_project_id", "ordinal"], unique = true),
        Index(value = ["book_project_id"]),
        Index(value = ["status"]),
    ],
)
public data class ChapterEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "book_project_id")
    val bookProjectId: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String? = null,
    @ColumnInfo(name = "canonical_markdown_path")
    val canonicalMarkdownPath: String? = null,
    @ColumnInfo(name = "status")
    val status: ChapterStatus = ChapterStatus.PENDING,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "narration_block",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapter_id", "ordinal"], unique = true),
        Index(value = ["chapter_id"]),
        Index(value = ["status"]),
    ],
)
public data class NarrationBlockEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
    @ColumnInfo(name = "block_type")
    val blockType: NarrationBlockType,
    @ColumnInfo(name = "source_text")
    val sourceText: String,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String? = null,
    @ColumnInfo(name = "normalized_text")
    val normalizedText: String? = null,
    @ColumnInfo(name = "normalized_text_hash")
    val normalizedTextHash: String? = null,
    @ColumnInfo(name = "phoneme_hash")
    val phonemeHash: String? = null,
    @ColumnInfo(name = "token_hash")
    val tokenHash: String? = null,
    @ColumnInfo(name = "preprocessing_version")
    val preprocessingVersion: String? = null,
    @ColumnInfo(name = "pronunciation_version")
    val pronunciationVersion: String? = null,
    @ColumnInfo(name = "status")
    val status: NarrationBlockStatus = NarrationBlockStatus.PENDING,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "model_package",
    indices = [
        Index(value = ["package_identity"], unique = true),
        Index(value = ["package_sha256"], unique = true),
        Index(value = ["status"]),
    ],
)
public data class ModelPackageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "package_identity")
    val packageIdentity: String,
    @ColumnInfo(name = "package_version")
    val packageVersion: String,
    @ColumnInfo(name = "package_sha256")
    val packageSha256: String,
    @ColumnInfo(name = "model_sha256")
    val modelSha256: String,
    @ColumnInfo(name = "voice_sha256")
    val voiceSha256: String,
    @ColumnInfo(name = "preprocessing_version")
    val preprocessingVersion: String,
    @ColumnInfo(name = "pronunciation_version")
    val pronunciationVersion: String,
    @ColumnInfo(name = "package_path")
    val packagePath: String,
    @ColumnInfo(name = "status")
    val status: ModelPackageStatus = ModelPackageStatus.INSTALLED,
    @ColumnInfo(name = "imported_at")
    val importedAt: Long,
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,
)

@Entity(
    tableName = "generation_run",
    foreignKeys = [
        ForeignKey(
            entity = BookProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_project_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ModelPackageEntity::class,
            parentColumns = ["id"],
            childColumns = ["model_package_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_project_id", "status"]),
        Index(value = ["model_package_id"]),
    ],
)
public data class GenerationRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "book_project_id")
    val bookProjectId: String,
    @ColumnInfo(name = "model_package_id")
    val modelPackageId: String? = null,
    @ColumnInfo(name = "preprocessing_version")
    val preprocessingVersion: String,
    @ColumnInfo(name = "pronunciation_version")
    val pronunciationVersion: String,
    @ColumnInfo(name = "inference_settings_hash")
    val inferenceSettingsHash: String,
    @ColumnInfo(name = "audio_processing_version")
    val audioProcessingVersion: String,
    @ColumnInfo(name = "status")
    val status: GenerationRunStatus = GenerationRunStatus.QUEUED,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "requested_at")
    val requestedAt: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
)

@Entity(
    tableName = "audio_segment",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NarrationBlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["narration_block_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GenerationRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["generation_run_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ModelPackageEntity::class,
            parentColumns = ["id"],
            childColumns = ["model_package_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapter_id", "sequence"], unique = true),
        Index(value = ["narration_block_id", "chunk_ordinal"], unique = true),
        Index(value = ["chapter_id", "status"]),
        Index(value = ["generation_run_id"]),
        Index(value = ["model_package_id"]),
    ],
)
public data class AudioSegmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    @ColumnInfo(name = "narration_block_id")
    val narrationBlockId: String,
    @ColumnInfo(name = "sequence")
    val sequence: Int,
    @ColumnInfo(name = "chunk_ordinal")
    val chunkOrdinal: Int,
    @ColumnInfo(name = "generation_key")
    val generationKey: String? = null,
    @ColumnInfo(name = "generation_run_id")
    val generationRunId: String? = null,
    @ColumnInfo(name = "model_package_id")
    val modelPackageId: String? = null,
    @ColumnInfo(name = "model_package_sha256")
    val modelPackageSha256: String? = null,
    @ColumnInfo(name = "voice_sha256")
    val voiceSha256: String? = null,
    @ColumnInfo(name = "preprocessing_version")
    val preprocessingVersion: String? = null,
    @ColumnInfo(name = "pronunciation_version")
    val pronunciationVersion: String? = null,
    @ColumnInfo(name = "inference_settings_hash")
    val inferenceSettingsHash: String? = null,
    @ColumnInfo(name = "audio_processing_version")
    val audioProcessingVersion: String? = null,
    @ColumnInfo(name = "status")
    val status: AudioSegmentStatus = AudioSegmentStatus.PENDING,
    @ColumnInfo(name = "audio_path")
    val audioPath: String? = null,
    @ColumnInfo(name = "audio_sha256")
    val audioSha256: String? = null,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo(name = "sample_rate", defaultValue = "24000")
    val sampleRate: Int = 24_000,
    @ColumnInfo(name = "channels", defaultValue = "1")
    val channels: Int = 1,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "playback_position",
    foreignKeys = [
        ForeignKey(
            entity = BookProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_project_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AudioSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["audio_segment_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapter_id"]),
        Index(value = ["audio_segment_id"]),
    ],
)
public data class PlaybackPositionEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_project_id")
    val bookProjectId: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String? = null,
    @ColumnInfo(name = "audio_segment_id")
    val audioSegmentId: String? = null,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long = 0,
    @ColumnInfo(name = "speed")
    val speed: Float = 1.0f,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "export_job",
    foreignKeys = [
        ForeignKey(
            entity = BookProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_project_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_project_id", "status"]),
    ],
)
public data class ExportJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "book_project_id")
    val bookProjectId: String,
    @ColumnInfo(name = "destination_uri")
    val destinationUri: String,
    @ColumnInfo(name = "selected_chapter_ids_json")
    val selectedChapterIdsJson: String,
    @ColumnInfo(name = "total_chapters")
    val totalChapters: Int,
    @ColumnInfo(name = "completed_chapters")
    val completedChapters: Int = 0,
    @ColumnInfo(name = "current_chapter_ordinal")
    val currentChapterOrdinal: Int? = null,
    @ColumnInfo(name = "manifest_path")
    val manifestPath: String? = null,
    @ColumnInfo(name = "status")
    val status: ExportJobStatus = ExportJobStatus.QUEUED,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

public data class BookProjectWithRelations(
    @Embedded
    val project: BookProjectEntity,
    @Relation(parentColumn = "id", entityColumn = "book_project_id")
    val chapters: List<ChapterEntity>,
    @Relation(parentColumn = "id", entityColumn = "book_project_id")
    val generationRuns: List<GenerationRunEntity>,
    @Relation(parentColumn = "id", entityColumn = "book_project_id")
    val exportJobs: List<ExportJobEntity>,
    @Relation(parentColumn = "id", entityColumn = "book_project_id")
    val playbackPosition: PlaybackPositionEntity?,
)

public data class ChapterWithRelations(
    @Embedded
    val chapter: ChapterEntity,
    @Relation(parentColumn = "id", entityColumn = "chapter_id")
    val narrationBlocks: List<NarrationBlockEntity>,
    @Relation(parentColumn = "id", entityColumn = "chapter_id")
    val audioSegments: List<AudioSegmentEntity>,
)

public data class GenerationRunWithSegments(
    @Embedded
    val run: GenerationRunEntity,
    @Relation(parentColumn = "id", entityColumn = "generation_run_id")
    val audioSegments: List<AudioSegmentEntity>,
)

public class RoomEnumConverters {
    @TypeConverter
    public fun bookProjectStatusToStorage(value: BookProjectStatus): String = value.name

    @TypeConverter
    public fun storageToBookProjectStatus(value: String): BookProjectStatus = BookProjectStatus.valueOf(value)

    @TypeConverter
    public fun chapterStatusToStorage(value: ChapterStatus): String = value.name

    @TypeConverter
    public fun storageToChapterStatus(value: String): ChapterStatus = ChapterStatus.valueOf(value)

    @TypeConverter
    public fun narrationBlockTypeToStorage(value: NarrationBlockType): String = value.name

    @TypeConverter
    public fun storageToNarrationBlockType(value: String): NarrationBlockType = NarrationBlockType.valueOf(value)

    @TypeConverter
    public fun narrationBlockStatusToStorage(value: NarrationBlockStatus): String = value.name

    @TypeConverter
    public fun storageToNarrationBlockStatus(value: String): NarrationBlockStatus =
        NarrationBlockStatus.valueOf(value)

    @TypeConverter
    public fun audioSegmentStatusToStorage(value: AudioSegmentStatus): String = value.name

    @TypeConverter
    public fun storageToAudioSegmentStatus(value: String): AudioSegmentStatus = AudioSegmentStatus.valueOf(value)

    @TypeConverter
    public fun generationRunStatusToStorage(value: GenerationRunStatus): String = value.name

    @TypeConverter
    public fun storageToGenerationRunStatus(value: String): GenerationRunStatus = GenerationRunStatus.valueOf(value)

    @TypeConverter
    public fun modelPackageStatusToStorage(value: ModelPackageStatus): String = value.name

    @TypeConverter
    public fun storageToModelPackageStatus(value: String): ModelPackageStatus = ModelPackageStatus.valueOf(value)

    @TypeConverter
    public fun exportJobStatusToStorage(value: ExportJobStatus): String = value.name

    @TypeConverter
    public fun storageToExportJobStatus(value: String): ExportJobStatus = ExportJobStatus.valueOf(value)
}
