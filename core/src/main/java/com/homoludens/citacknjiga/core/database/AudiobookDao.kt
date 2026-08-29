package com.homoludens.citacknjiga.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
public interface AudiobookDao {
    @Insert
    public fun insertProject(project: BookProjectEntity)

    @Insert
    public fun insertChapter(chapter: ChapterEntity)

    @Insert
    public fun insertNarrationBlock(block: NarrationBlockEntity)

    @Insert
    public fun insertModelPackage(modelPackage: ModelPackageEntity)

    @Insert
    public fun insertGenerationRun(run: GenerationRunEntity)

    @Insert
    public fun insertAudioSegment(segment: AudioSegmentEntity)

    @Query("SELECT * FROM book_project")
    public fun findAllProjects(): List<BookProjectEntity>

    @Query("SELECT * FROM chapter")
    public fun findAllChapters(): List<ChapterEntity>

    @Query("SELECT * FROM generation_run")
    public fun findAllGenerationRuns(): List<GenerationRunEntity>

    @Query("SELECT * FROM audio_segment")
    public fun findAllAudioSegments(): List<AudioSegmentEntity>

    @Query("SELECT * FROM model_package WHERE status = 'ACTIVE' ORDER BY imported_at DESC, id DESC LIMIT 1")
    public fun findActiveModelPackage(): ModelPackageEntity?

    @Update
    public fun updateProject(project: BookProjectEntity)

    @Update
    public fun updateChapter(chapter: ChapterEntity)

    @Update
    public fun updateNarrationBlock(block: NarrationBlockEntity)

    @Update
    public fun updateGenerationRun(run: GenerationRunEntity)

    @Update
    public fun updateAudioSegment(segment: AudioSegmentEntity)

    @Transaction
    @Query("SELECT * FROM book_project WHERE id = :projectId")
    public fun findProjectWithRelations(projectId: String): BookProjectWithRelations?

    @Transaction
    @Query("SELECT * FROM chapter WHERE id = :chapterId")
    public fun findChapterWithRelations(chapterId: String): ChapterWithRelations?

    @Transaction
    @Query("SELECT * FROM generation_run WHERE id = :runId")
    public fun findGenerationRunWithSegments(runId: String): GenerationRunWithSegments?
}
