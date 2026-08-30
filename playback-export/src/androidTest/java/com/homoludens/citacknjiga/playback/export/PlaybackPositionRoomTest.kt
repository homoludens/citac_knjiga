package com.homoludens.citacknjiga.playback.export

import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

public class PlaybackPositionRoomTest {
    @Test
    public fun positionSurvivesRoomCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "playback-position-${UUID.randomUUID()}.db"
        var database = AudiobookDatabase.create(context, databaseName)
        try {
            val dao = database.audiobookDao()
            dao.insertProject(
                BookProjectEntity(
                    id = "book",
                    title = "Book",
                    sourceUri = "content://book",
                    sourceFingerprint = "f".repeat(64),
                    status = BookProjectStatus.READY,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            dao.insertChapter(ChapterEntity("chapter", "book", 0, "Chapter", status = ChapterStatus.READY, createdAt = 1L, updatedAt = 1L))
            dao.insertNarrationBlock(
                NarrationBlockEntity(
                    id = "block",
                    chapterId = "chapter",
                    ordinal = 0,
                    blockType = NarrationBlockType.PARAGRAPH,
                    sourceText = "Text",
                    status = NarrationBlockStatus.PROCESSED,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            dao.insertAudioSegment(
                AudioSegmentEntity(
                    id = "segment",
                    chapterId = "chapter",
                    narrationBlockId = "block",
                    sequence = 0,
                    chunkOrdinal = 0,
                    status = AudioSegmentStatus.READY,
                    durationMs = 2_000L,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            val first = PlaybackPositionPersistence(
                dao,
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                nowMs = { 10L },
            )
            first.attach("book", catalog(), FakePlayer())
            first.flush()
            first.close()
            database.close()

            database = AudiobookDatabase.create(context, databaseName)
            val reopenedDao = database.audiobookDao()
            assertEquals(10L, reopenedDao.findPlaybackPosition("book")?.updatedAt)
            val player = FakePlayer()
            val restored = PlaybackPositionPersistence(
                reopenedDao,
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ).restore("book", catalog(), player)

            assertEquals(1.5f, restored?.speed)
            assertEquals(1_200L, restored?.positionMs)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun catalog() = PlaybackCatalog(
        listOf(PlaybackChapter("chapter", "Chapter", 0, listOf("segment"), 0, true)),
        listOf("segment"),
    )

    private class FakePlayer : PlaybackPlayerPort {
        override val isPlaying: Boolean = false
        override val positionMs: Long = 1_200L
        override val durationMs: Long = 2_000L
        override val currentMediaItemId: String = "segment"
        override val currentMediaItemIndex: Int = 0
        override val mediaItemCount: Int = 1
        private var speedValue: Float = 1.5f
        override val speed: Float get() = speedValue

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = Unit
        override fun setSpeed(speed: Float) { speedValue = speed }
        override fun addListener(listener: () -> Unit) = Unit
        override fun removeListener(listener: () -> Unit) = Unit
    }
}
