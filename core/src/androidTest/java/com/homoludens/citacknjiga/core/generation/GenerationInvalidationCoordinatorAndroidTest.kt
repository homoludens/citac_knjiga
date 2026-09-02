package com.homoludens.citacknjiga.core.generation

import android.content.Context
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
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class GenerationInvalidationCoordinatorAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var databaseName: String
    private lateinit var storage: AppPrivateStorage

    @Before
    public fun setUp() {
        databaseName = "generation-invalidation-${UUID.randomUUID()}.db"
        database = AudiobookDatabase.create(context, databaseName)
        storage = AppPrivateStorage(File(context.cacheDir, "generation-invalidation-${UUID.randomUUID()}"))
        seedProject("book", listOf("one", "two"))
        seedProject("other-book", listOf("only"))
    }

    @After
    public fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun chapterScopeRemovesOldFileResetsAndQueuesOnlyThatChapter() {
        val oldTarget = readySegment("book", "one", "old-one")
        val other = readySegment("book", "two", "other")
        val otherProject = readySegment("other-book", "only", "other-project")
        val dao = database.audiobookDao()
        dao.insertAudioSegment(oldTarget)
        dao.insertAudioSegment(other)
        dao.insertAudioSegment(otherProject)
        dao.savePlaybackPosition(PlaybackPositionEntity("book", "book-one", "old-one", updatedAt = 1L))
        val oldTargetFile = File(oldTarget.audioPath!!)
        val otherFile = File(other.audioPath!!)
        val otherProjectFile = File(otherProject.audioPath!!)

        val queued = invalidator().invalidateAndQueue(request("book", GenerationScope.Chapter("book-one")))

        assertEquals(listOf("replacement-run-book-one-book-one-block"), queued.segmentIds)
        assertFalse(oldTargetFile.exists())
        assertTrue(otherFile.exists())
        assertTrue(otherProjectFile.exists())
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("other")!!.status)
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("other-project")!!.status)
        assertEquals(ChapterStatus.PENDING, dao.findChapterById("book-one")!!.status)
        assertEquals(AudioSegmentStatus.PENDING, dao.findAudioSegmentById(queued.segmentIds.single())!!.status)
        assertEquals(ChapterStatus.READY, dao.findChapterById("book-two")!!.status)
        assertEquals(BookProjectStatus.COMPLETED, dao.findProjectById("other-book")!!.status)
        assertNull(dao.findPlaybackPosition("book")!!.audioSegmentId)
    }

    @Test
    public fun completeBookScopeRemovesAllBookAudioAndLeavesOtherProjectUntouched() {
        val first = readySegment("book", "one", "book-one-old")
        val second = readySegment("book", "two", "book-two-old")
        val outside = readySegment("other-book", "only", "other-project")
        val dao = database.audiobookDao()
        dao.insertAudioSegment(first)
        dao.insertAudioSegment(second)
        dao.insertAudioSegment(outside)
        val firstFile = File(first.audioPath!!)
        val secondFile = File(second.audioPath!!)
        val outsideFile = File(outside.audioPath!!)

        val queued = invalidator().invalidateAndQueue(request("book", GenerationScope.CompleteBook))

        assertEquals(2, queued.segmentIds.size)
        assertFalse(firstFile.exists())
        assertFalse(secondFile.exists())
        assertTrue(outsideFile.exists())
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("other-project")!!.status)
        assertEquals(setOf(ChapterStatus.PENDING), dao.findAllChapters()
            .filter { it.bookProjectId == "book" }
            .map { it.status }
            .toSet())
        assertEquals(1, dao.findAllAudioSegments().count { it.chapterId == "other-book-only" })
    }

    @Test
    public fun plannerFailureLeavesTargetUnavailableAndDoesNotChangeOtherScopes() {
        val target = readySegment("book", "one", "target")
        val other = readySegment("book", "two", "other")
        val outside = File(storage.rootDirectory.parentFile, "outside-${UUID.randomUUID()}").apply { writeText("keep") }
        val unsafeTarget = target.copy(audioPath = outside.path, audioSha256 = "old", sizeBytes = outside.length())
        val dao = database.audiobookDao()
        dao.insertAudioSegment(unsafeTarget)
        dao.insertAudioSegment(other)
        val beforeOther = dao.findAudioSegmentById(other.id)!!

        val failure = runCatching {
            GenerationInvalidationCoordinator(
                database = database,
                storage = storage,
                generationCoordinator = DurableGenerationCoordinator(
                    database = database,
                    planners = listOf(FailingPlanner),
                    enqueue = {},
                ),
            ).invalidateAndQueue(request("book", GenerationScope.Chapter("book-one")))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(outside.exists())
        assertEquals(beforeOther, dao.findAudioSegmentById(other.id))
        assertTrue(dao.findAllAudioSegments().none { it.chapterId == "book-one" })
        assertEquals(ChapterStatus.PENDING, dao.findChapterById("book-one")!!.status)
    }

    private fun invalidator(): GenerationInvalidationCoordinator = GenerationInvalidationCoordinator(
        database = database,
        storage = storage,
        generationCoordinator = DurableGenerationCoordinator(
            database = database,
            planners = listOf(FakePlanner),
            enqueue = {},
            clock = { 10L },
            runIdFactory = { "replacement-run-${it.scope.chapterId()}" },
        ),
        clock = { 10L },
    )

    private fun request(projectId: String, scope: GenerationScope): GenerationRequest {
        val dao = database.audiobookDao()
        val project = dao.findProjectById(projectId)!!
        return GenerationRequestFactory.fromExistingNarrationBlocks(
            project = project,
            chapters = dao.findAllChapters(),
            narrationBlocks = dao.findAllNarrationBlocks(),
            scope = scope,
            engine = GenerationEngine.VITS,
        )
    }

    private fun seedProject(projectId: String, chapterIds: List<String>) {
        val dao = database.audiobookDao()
        dao.insertProject(
            BookProjectEntity(
                id = projectId,
                title = projectId,
                sourceUri = "content://$projectId",
                sourceFingerprint = "$projectId-fingerprint",
                status = BookProjectStatus.COMPLETED,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        chapterIds.forEachIndexed { ordinal, chapterId ->
            dao.insertChapter(
                ChapterEntity(
                    id = "$projectId-$chapterId",
                    bookProjectId = projectId,
                    ordinal = ordinal,
                    title = chapterId,
                    status = ChapterStatus.READY,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            dao.insertNarrationBlock(
                NarrationBlockEntity(
                    id = "$projectId-$chapterId-block",
                    chapterId = "$projectId-$chapterId",
                    ordinal = 0,
                    blockType = NarrationBlockType.PARAGRAPH,
                    sourceText = "Text $chapterId",
                    status = NarrationBlockStatus.PROCESSED,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }
    }

    private fun readySegment(projectId: String, chapterId: String, id: String): AudioSegmentEntity {
        val chapterKey = "$projectId-$chapterId"
        val blockKey = "$chapterKey-block"
        val file = storage.readySegmentAudio(projectId, chapterKey, id).apply {
            parentFile!!.mkdirs()
            writeText("old audio")
        }
        return AudioSegmentEntity(
            id = id,
            chapterId = chapterKey,
            narrationBlockId = blockKey,
            sequence = 0,
            chunkOrdinal = 0,
            status = AudioSegmentStatus.READY,
            audioPath = file.path,
            audioSha256 = "old",
            sizeBytes = file.length(),
            durationMs = 1L,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private fun GenerationScope.chapterId(): String = when (this) {
        GenerationScope.CompleteBook -> "book"
        is GenerationScope.Chapter -> chapterId
    }

    private object FakePlanner : GenerationEnginePlanner {
        override val engine: GenerationEngine = GenerationEngine.VITS

        override fun plan(request: GenerationRequest): GenerationEnginePlan {
            val provenance = GenerationProvenance(
                generationKey = "new-key",
                modelPackageId = "new-model",
                modelPackageSha256 = "package",
                voiceSha256 = "voice",
                preprocessingVersion = "prep",
                pronunciationVersion = "pron",
                inferenceSettingsHash = "settings",
                audioProcessingVersion = "audio",
                engine = engine.id,
            )
            return GenerationEnginePlan(
                modelPackage = com.homoludens.citacknjiga.core.database.ModelPackageEntity(
                    id = "new-model",
                    packageIdentity = "new-model@1",
                    packageVersion = "1",
                    packageSha256 = "package",
                    modelSha256 = "model",
                    voiceSha256 = "voice",
                    preprocessingVersion = "prep",
                    pronunciationVersion = "pron",
                    packagePath = "model.zip",
                    importedAt = 1L,
                ),
                segments = request.narrationBlocks.map { PlannedGenerationSegment(it.id, it.chapterId, provenance) },
            )
        }
    }

    private object FailingPlanner : GenerationEnginePlanner {
        override val engine: GenerationEngine = GenerationEngine.VITS

        override fun plan(request: GenerationRequest): GenerationEnginePlan =
            error("model unavailable")
    }
}
