package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.ChapterWithRelations
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.GenerationRunWithSegments
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import com.homoludens.citacknjiga.core.database.BookProjectWithRelations
import com.homoludens.citacknjiga.core.database.ExportJobEntity
import com.homoludens.citacknjiga.core.database.ExportJobChapterEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

public class PlaybackPositionPersistenceTest {
    @Test
    public fun writesCurrentBookChapterSegmentPositionAndSpeed() = runBlocking {
        val dao = RecordingDao()
        val player = FakePlayer("segment-1", positionMs = 1_500L, durationMs = 1_000L, speed = 1.5f)
        val persistence = persistence(dao)
        persistence.attach("book", catalog("chapter-1", "segment-1"), player)

        persistence.flush()

        assertEquals(
            PlaybackPositionEntity("book", "chapter-1", "segment-1", 1_000L, 1.5f, 10L),
            dao.position,
        )
        persistence.close()
    }

    @Test
    public fun throttlesPollingButPersistsPlayerEventsAndLaterChanges() = runBlocking {
        var now = 10L
        val dao = RecordingDao()
        val player = FakePlayer("segment-1", positionMs = 100L, durationMs = 1_000L)
        val persistence = persistence(dao) { now }
        persistence.attach("book", catalog("chapter-1", "segment-1"), player)
        persistence.persistIfDue()
        player.positionMs = 200L
        now = 1_000L
        persistence.persistIfDue()
        assertEquals(1, dao.writeCount)

        player.positionMs = 300L
        now = 2_010L
        player.emitChange()
        delay(30L)
        assertEquals(2, dao.writeCount)
        assertEquals(300L, dao.position?.positionMs)
        persistence.close()
    }

    @Test
    public fun restoresValidStateAndSpeedAfterAdapterRecreation() = runBlocking {
        val dao = RecordingDao(
            PlaybackPositionEntity("book", "chapter-1", "segment-1", 700L, 2.0f, 1L),
        )
        val player = FakePlayer("segment-1", durationMs = 1_000L)
        val persistence = persistence(dao)

        val restored = persistence.restore("book", catalog("chapter-1", "segment-1"), player)

        assertEquals(RestoredPlaybackPosition("chapter-1", "segment-1", 0, 700L, 2.0f), restored)
        assertEquals(700L, player.positionMs)
        assertEquals(2.0f, player.speed)
        persistence.close()
    }

    @Test
    public fun invalidSegmentFallsBackToFirstAvailableSegmentAndSafeSpeed() = runBlocking {
        val dao = RecordingDao(
            PlaybackPositionEntity("book", "chapter-1", "missing", -5L, Float.NaN, 1L),
        )
        val player = FakePlayer("other", durationMs = 1_000L)
        val persistence = persistence(dao)

        val restored = persistence.restore("book", catalog("chapter-1", "segment-1", "segment-2"), player)

        assertEquals(RestoredPlaybackPosition("chapter-1", "segment-1", 0, 0L, 1.0f), restored)
        assertEquals(0L, player.positionMs)
        assertEquals(1.0f, player.speed)
        persistence.close()
    }

    @Test
    public fun missingChapterFallsBackToFirstAvailableChapter() = runBlocking {
        val dao = RecordingDao(
            PlaybackPositionEntity("book", "gone", "gone-segment", 500L, 1.25f, 1L),
        )
        val player = FakePlayer("other", durationMs = 1_000L)
        val persistence = persistence(dao)
        val catalog = PlaybackCatalog(
            listOf(
                PlaybackChapter("pending", "Pending", 0, emptyList(), 0, false),
                PlaybackChapter("ready", "Ready", 1, listOf("segment-1"), 0, true),
            ),
            listOf("segment-1"),
        )

        val restored = persistence.restore("book", catalog, player)

        assertEquals("ready", restored?.chapterId)
        assertEquals("segment-1", restored?.audioSegmentId)
        assertEquals(0L, restored?.positionMs)
        persistence.close()
    }

    private fun persistence(dao: RecordingDao, clock: () -> Long = { 10L }) =
        PlaybackPositionPersistence(
            dao = dao,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMs = clock,
        )

    private fun catalog(chapterId: String, vararg segmentIds: String) = PlaybackCatalog(
        chapters = listOf(PlaybackChapter(chapterId, chapterId, 0, segmentIds.toList(), 0, true)),
        mediaItemIds = segmentIds.toList(),
    )

    private class FakePlayer(
        override var currentMediaItemId: String?,
        override var positionMs: Long = 0L,
        override val durationMs: Long? = null,
        speed: Float = 1.0f,
    ) : PlaybackPlayerPort {
        private val listeners = mutableListOf<() -> Unit>()
        private var speedValue = speed
        override val isPlaying: Boolean = false
        override val currentMediaItemIndex: Int = 0
        override val mediaItemCount: Int = 1
        override val speed: Float get() = speedValue

        fun emitChange() = listeners.forEach { it() }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) { this.positionMs = positionMs }
        override fun setSpeed(speed: Float) { speedValue = speed }
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener }
    }

    private class RecordingDao(
        var position: PlaybackPositionEntity? = null,
    ) : AudiobookDao {
        var writeCount: Int = 0
        override fun insertProject(project: BookProjectEntity) = Unit
        override fun insertChapter(chapter: ChapterEntity) = Unit
        override fun insertNarrationBlock(block: NarrationBlockEntity) = Unit
        override fun insertModelPackage(modelPackage: ModelPackageEntity) = Unit
        override fun insertGenerationRun(run: GenerationRunEntity) = Unit
        override fun insertAudioSegment(segment: AudioSegmentEntity) = Unit
        override fun insertExportJob(job: ExportJobEntity) = Unit
        override fun insertExportJobChapter(chapter: ExportJobChapterEntity) = Unit
        override fun findAllProjects(): List<BookProjectEntity> = emptyList()
        override fun observeAllProjects(): Flow<List<BookProjectEntity>> = emptyFlow()
        override fun findProjectBySourceFingerprint(fingerprint: String): BookProjectEntity? = null
        override fun findProjectById(projectId: String): BookProjectEntity? = null
        override fun findAllChapters(): List<ChapterEntity> = emptyList()
        override fun observeAllChapters(): Flow<List<ChapterEntity>> = emptyFlow()
        override fun findChapterById(chapterId: String): ChapterEntity? = null
        override fun findNarrationBlockById(blockId: String): NarrationBlockEntity? = null
        override fun findAllGenerationRuns(): List<GenerationRunEntity> = emptyList()
        override fun findGenerationRunById(runId: String): GenerationRunEntity? = null
        override fun findAllAudioSegments(): List<AudioSegmentEntity> = emptyList()
        override fun observeAllAudioSegments(): Flow<List<AudioSegmentEntity>> = emptyFlow()
        override fun observeAllGenerationRuns(): Flow<List<GenerationRunEntity>> = emptyFlow()
        override fun observeAllPlaybackPositions(): Flow<List<PlaybackPositionEntity>> = emptyFlow()
        override fun findPlaybackPosition(projectId: String): PlaybackPositionEntity? = position
        override fun savePlaybackPosition(position: PlaybackPositionEntity) { this.position = position; writeCount++ }
        override fun observeReadyAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>> = emptyFlow()
        override fun findAudioSegmentById(segmentId: String): AudioSegmentEntity? = null
        override fun findActiveModelPackage(): ModelPackageEntity? = null
        override fun updateProject(project: BookProjectEntity) = Unit
        override fun updateChapter(chapter: ChapterEntity) = Unit
        override fun updateNarrationBlock(block: NarrationBlockEntity) = Unit
        override fun updateGenerationRun(run: GenerationRunEntity) = Unit
        override fun updateAudioSegment(segment: AudioSegmentEntity) = Unit
        override fun transitionProject(projectId: String, fromStatus: BookProjectStatus, toStatus: BookProjectStatus, lastError: String?, updatedAt: Long): Int = 0
        override fun transitionChapter(chapterId: String, fromStatus: ChapterStatus, toStatus: ChapterStatus, lastError: String?, updatedAt: Long): Int = 0
        override fun transitionGenerationRun(runId: String, fromStatus: GenerationRunStatus, toStatus: GenerationRunStatus, attemptIncrement: Int, lastError: String?, startedAt: Long?, finishedAt: Long?): Int = 0
        override fun transitionAudioSegment(segmentId: String, fromStatus: AudioSegmentStatus, toStatus: AudioSegmentStatus, attemptIncrement: Int, lastError: String?, updatedAt: Long): Int = 0
        override fun findProjectWithRelations(projectId: String): BookProjectWithRelations? = null
        override fun findChapterWithRelations(chapterId: String): ChapterWithRelations? = null
        override fun findGenerationRunWithSegments(runId: String): GenerationRunWithSegments? = null
        override fun findExportJobById(jobId: String): ExportJobEntity? = null
        override fun findAllExportJobs(): List<ExportJobEntity> = emptyList()
        override fun findExportJobChapters(jobId: String): List<ExportJobChapterEntity> = emptyList()
        override fun findExportJobChapter(jobId: String, chapterId: String): ExportJobChapterEntity? = null
        override fun updateExportJob(job: ExportJobEntity) = Unit
        override fun updateExportJobChapter(chapter: ExportJobChapterEntity) = Unit
    }
}
