package com.homoludens.citacknjiga.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
public interface AudiobookDao {
    @Insert
    public fun insertProject(project: BookProjectEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public fun insertChapter(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public fun insertNarrationBlock(block: NarrationBlockEntity)

    @Transaction
    public fun insertDocument(
        project: BookProjectEntity,
        chapters: List<ChapterEntity>,
        blocks: List<NarrationBlockEntity>,
    ) {
        insertProject(project)
        chapters.forEach(::insertChapter)
        blocks.forEach(::insertNarrationBlock)
    }

    @Insert
    public fun insertModelPackage(modelPackage: ModelPackageEntity)

    @Insert
    public fun insertGenerationRun(run: GenerationRunEntity)

    @Insert
    public fun insertAudioSegment(segment: AudioSegmentEntity)

    @Insert
    public fun insertExportJob(job: ExportJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public fun insertExportJobChapter(chapter: ExportJobChapterEntity)

    @Transaction
    public fun insertExportJobWithChapters(job: ExportJobEntity, chapters: List<ExportJobChapterEntity>) {
        insertExportJob(job)
        chapters.forEach(::insertExportJobChapter)
    }

    @Query("SELECT * FROM book_project")
    public fun findAllProjects(): List<BookProjectEntity>

    @Query("SELECT * FROM book_project ORDER BY updated_at DESC, id DESC")
    public fun observeAllProjects(): Flow<List<BookProjectEntity>>

    @Query("SELECT * FROM book_project WHERE source_fingerprint = :fingerprint LIMIT 1")
    public fun findProjectBySourceFingerprint(fingerprint: String): BookProjectEntity?

    @Query("SELECT * FROM book_project WHERE id = :projectId LIMIT 1")
    public fun findProjectById(projectId: String): BookProjectEntity?

    @Query("SELECT * FROM chapter")
    public fun findAllChapters(): List<ChapterEntity>

    @Query("SELECT * FROM chapter ORDER BY book_project_id, ordinal")
    public fun observeAllChapters(): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapter WHERE id = :chapterId LIMIT 1")
    public fun findChapterById(chapterId: String): ChapterEntity?

    @Query("SELECT * FROM narration_block WHERE id = :blockId LIMIT 1")
    public fun findNarrationBlockById(blockId: String): NarrationBlockEntity?

    @Query("SELECT * FROM narration_block")
    public fun findAllNarrationBlocks(): List<NarrationBlockEntity>

    @Query("SELECT * FROM generation_run")
    public fun findAllGenerationRuns(): List<GenerationRunEntity>

    @Query("SELECT * FROM generation_run WHERE id = :runId LIMIT 1")
    public fun findGenerationRunById(runId: String): GenerationRunEntity?

    @Query("SELECT * FROM audio_segment")
    public fun findAllAudioSegments(): List<AudioSegmentEntity>

    @Query("SELECT * FROM audio_segment ORDER BY chapter_id, sequence, id")
    public fun observeAllAudioSegments(): Flow<List<AudioSegmentEntity>>

    @Query("SELECT * FROM generation_run ORDER BY requested_at DESC, id DESC")
    public fun observeAllGenerationRuns(): Flow<List<GenerationRunEntity>>

    @Query("SELECT * FROM playback_position ORDER BY updated_at DESC, book_project_id")
    public fun observeAllPlaybackPositions(): Flow<List<PlaybackPositionEntity>>

    @Query("SELECT * FROM playback_position WHERE book_project_id = :projectId LIMIT 1")
    public fun findPlaybackPosition(projectId: String): PlaybackPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public fun savePlaybackPosition(position: PlaybackPositionEntity)

    /** Room is authoritative for which audio is eligible for playback. */
    @Query(
        """
        SELECT audio_segment.* FROM audio_segment
        INNER JOIN chapter ON chapter.id = audio_segment.chapter_id
        WHERE chapter.book_project_id = :projectId AND audio_segment.status = 'READY'
        ORDER BY chapter.ordinal, audio_segment.sequence, audio_segment.id
        """,
    )
    public fun observeReadyAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>>

    @Query("SELECT * FROM audio_segment WHERE id = :segmentId LIMIT 1")
    public fun findAudioSegmentById(segmentId: String): AudioSegmentEntity?

    @Query("SELECT * FROM model_package WHERE status = 'ACTIVE' ORDER BY imported_at DESC, id DESC LIMIT 1")
    public fun findActiveModelPackage(): ModelPackageEntity?

    @Query("SELECT * FROM model_package")
    public fun findAllModelPackages(): List<ModelPackageEntity>

    @Query("SELECT * FROM model_package WHERE id = :modelPackageId LIMIT 1")
    public fun findModelPackageById(modelPackageId: String): ModelPackageEntity?

    @Update
    public fun updateProject(project: BookProjectEntity)

    /** Marks a project before its owned files and rows are removed. */
    @Query(
        """
        UPDATE book_project
        SET is_deleting = 1, updated_at = :updatedAt
        WHERE id = :projectId AND is_deleting = 0
        """,
    )
    public fun markProjectDeleting(projectId: String, updatedAt: Long): Int

    @Update
    public fun updateChapter(chapter: ChapterEntity)

    @Update
    public fun updateNarrationBlock(block: NarrationBlockEntity)

    @Update
    public fun updateGenerationRun(run: GenerationRunEntity)

    @Update
    public fun updateAudioSegment(segment: AudioSegmentEntity)

    @Query("DELETE FROM audio_segment WHERE id IN (:segmentIds)")
    public fun deleteAudioSegments(segmentIds: List<String>)

    /** Conditional updates keep a validated transition from overwriting a concurrent state change. */
    @Query(
        """
        UPDATE book_project
        SET status = :toStatus, last_error = :lastError, updated_at = :updatedAt
        WHERE id = :projectId AND status = :fromStatus
        """,
    )
    public fun transitionProject(
        projectId: String,
        fromStatus: BookProjectStatus,
        toStatus: BookProjectStatus,
        lastError: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE chapter
        SET status = :toStatus, last_error = :lastError, updated_at = :updatedAt
        WHERE id = :chapterId AND status = :fromStatus
        """,
    )
    public fun transitionChapter(
        chapterId: String,
        fromStatus: ChapterStatus,
        toStatus: ChapterStatus,
        lastError: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE generation_run
        SET status = :toStatus,
            attempt_count = attempt_count + :attemptIncrement,
            last_error = :lastError,
            started_at = :startedAt,
            finished_at = :finishedAt
        WHERE id = :runId AND status = :fromStatus
        """,
    )
    public fun transitionGenerationRun(
        runId: String,
        fromStatus: GenerationRunStatus,
        toStatus: GenerationRunStatus,
        attemptIncrement: Int,
        lastError: String?,
        startedAt: Long?,
        finishedAt: Long?,
    ): Int

    @Query(
        """
        UPDATE audio_segment
        SET status = :toStatus,
            attempt_count = attempt_count + :attemptIncrement,
            last_error = :lastError,
            updated_at = :updatedAt
        WHERE id = :segmentId AND status = :fromStatus
        """,
    )
    public fun transitionAudioSegment(
        segmentId: String,
        fromStatus: AudioSegmentStatus,
        toStatus: AudioSegmentStatus,
        attemptIncrement: Int,
        lastError: String?,
        updatedAt: Long,
    ): Int

    @Transaction
    @Query("SELECT * FROM book_project WHERE id = :projectId")
    public fun findProjectWithRelations(projectId: String): BookProjectWithRelations?

    @Transaction
    @Query("SELECT * FROM chapter WHERE id = :chapterId")
    public fun findChapterWithRelations(chapterId: String): ChapterWithRelations?

    @Transaction
    @Query("SELECT * FROM generation_run WHERE id = :runId")
    public fun findGenerationRunWithSegments(runId: String): GenerationRunWithSegments?

    @Query("SELECT * FROM export_job WHERE id = :jobId LIMIT 1")
    public fun findExportJobById(jobId: String): ExportJobEntity?

    @Query("SELECT * FROM export_job ORDER BY updated_at DESC, id DESC")
    public fun findAllExportJobs(): List<ExportJobEntity>

    @Query("SELECT * FROM export_job_chapter WHERE export_job_id = :jobId ORDER BY ordinal")
    public fun findExportJobChapters(jobId: String): List<ExportJobChapterEntity>

    @Query("SELECT * FROM export_job_chapter WHERE export_job_id = :jobId AND chapter_id = :chapterId LIMIT 1")
    public fun findExportJobChapter(jobId: String, chapterId: String): ExportJobChapterEntity?

    @Update
    public fun updateExportJob(job: ExportJobEntity)

    @Update
    public fun updateExportJobChapter(chapter: ExportJobChapterEntity)
}
