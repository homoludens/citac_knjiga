package com.homoludens.citacknjiga.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
public interface AudiobookDao {
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
