package com.homoludens.citacknjiga.playback.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

public class RoomReadyAudioSourceTest {
    private lateinit var database: AudiobookDatabase

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    public fun tearDown() {
        database.close()
    }

    @Test
    public fun roomObservationSelectsReadyAudioForOneProjectInPlaybackOrder() = runBlocking {
        val dao = database.audiobookDao()
        dao.insertProject(project("book"))
        dao.insertProject(project("other"))
        dao.insertChapter(chapter("book", "chapter-later", 1))
        dao.insertChapter(chapter("book", "chapter-first", 0))
        dao.insertChapter(chapter("other", "other-chapter", 0))
        listOf("chapter-later", "chapter-first", "other-chapter").forEach { chapterId ->
            dao.insertNarrationBlock(block(chapterId))
        }
        dao.insertAudioSegment(segment("later-ready", "chapter-later", 1, AudioSegmentStatus.READY))
        dao.insertAudioSegment(segment("first-ready", "chapter-first", 0, AudioSegmentStatus.READY))
        dao.insertAudioSegment(segment("first-pending", "chapter-first", 1, AudioSegmentStatus.PENDING, 1))
        dao.insertAudioSegment(segment("other-ready", "other-chapter", 0, AudioSegmentStatus.READY))

        val result = RoomReadyAudioSource(dao)
            .observeReadyAudioSegments("book")
            .first()

        assertEquals(listOf("first-ready", "later-ready"), result.map { it.id })
    }

    private fun project(id: String) = BookProjectEntity(
        id = id,
        title = id,
        sourceUri = "content://$id",
        sourceFingerprint = "fingerprint-$id",
        status = BookProjectStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(projectId: String, id: String, ordinal: Int) = ChapterEntity(
        id = id,
        bookProjectId = projectId,
        ordinal = ordinal,
        title = id,
        status = ChapterStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun block(chapterId: String) = NarrationBlockEntity(
        id = "block-$chapterId",
        chapterId = chapterId,
        ordinal = 0,
        blockType = NarrationBlockType.PARAGRAPH,
        sourceText = "Текст.",
        status = NarrationBlockStatus.PROCESSED,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun segment(
        id: String,
        chapterId: String,
        sequence: Int,
        status: AudioSegmentStatus,
        chunkOrdinal: Int = 0,
    ) =
        AudioSegmentEntity(
            id = id,
            chapterId = chapterId,
            narrationBlockId = "block-$chapterId",
            sequence = sequence,
            chunkOrdinal = chunkOrdinal,
            status = status,
            createdAt = 1,
            updatedAt = 1,
        )
}
