package com.homoludens.citacknjiga.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import com.homoludens.citacknjiga.core.reconciliation.RoomReconciliationDatabase
import com.homoludens.citacknjiga.core.reconciliation.StartupReconciliation
import com.homoludens.citacknjiga.core.generation.GenerationRetryPolicy
import com.homoludens.citacknjiga.core.generation.GenerationStateService
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class RoomProjectCoreIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var storageRoot: File

    @Before
    public fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageRoot = File(context.cacheDir, "core-task-6-7-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    public fun tearDown() {
        database.close()
        storageRoot.deleteRecursively()
    }

    @Test
    public fun roomPersistsValidProjectGenerationTransitionsAndRelations() {
        val dao = database.audiobookDao()
        val project = project(BookProjectStatus.IMPORTING)
        val chapter = chapter(ChapterStatus.PENDING)
        val block = block(NarrationBlockStatus.PENDING)
        val model = activeModel()
        val run = run(GenerationRunStatus.QUEUED)
        val segment = segment(AudioSegmentStatus.PENDING, run.id, id = "segment")
        insert(dao, project, chapter, block, model, run, segment)

        dao.updateProject(project.copy(status = BookProjectStatus.READY))
        dao.updateChapter(chapter.copy(status = ChapterStatus.GENERATING))
        dao.updateProject(project.copy(status = BookProjectStatus.GENERATING))
        dao.updateGenerationRun(run.copy(status = GenerationRunStatus.RUNNING, startedAt = 2))
        dao.updateAudioSegment(segment.copy(status = AudioSegmentStatus.GENERATING))
        dao.updateNarrationBlock(block.copy(status = NarrationBlockStatus.PROCESSED))
        dao.updateAudioSegment(segment.copy(status = AudioSegmentStatus.READY))
        dao.updateGenerationRun(run.copy(status = GenerationRunStatus.COMPLETED, finishedAt = 3))
        dao.updateChapter(chapter.copy(status = ChapterStatus.READY))
        dao.updateProject(project.copy(status = BookProjectStatus.COMPLETED))

        val projectWithRelations = dao.findProjectWithRelations(project.id)
        val chapterWithRelations = dao.findChapterWithRelations(chapter.id)
        val runWithSegments = dao.findGenerationRunWithSegments(run.id)
        assertEquals(BookProjectStatus.COMPLETED, projectWithRelations?.project?.status)
        assertEquals(ChapterStatus.READY, projectWithRelations?.chapters?.single()?.status)
        assertEquals(NarrationBlockStatus.PROCESSED, chapterWithRelations?.narrationBlocks?.single()?.status)
        assertEquals(AudioSegmentStatus.READY, chapterWithRelations?.audioSegments?.single()?.status)
        assertEquals(GenerationRunStatus.COMPLETED, runWithSegments?.run?.status)
        assertEquals(AudioSegmentStatus.READY, runWithSegments?.audioSegments?.single()?.status)
    }

    @Test
    public fun roomReconciliationRepairsStateAndIsIdempotent() {
        val storage = AppPrivateStorage(storageRoot)
        val artifactStore = AtomicArtifactStore(storage)
        val artifact = artifactStore.publish(
            ownerId = "run",
            destination = storage.readySegmentAudio("book", "chapter", "ready"),
            writer = { it.write("verified audio".toByteArray()) },
        )
        val dao = database.audiobookDao()
        val project = project(BookProjectStatus.GENERATING)
        val chapter = chapter(ChapterStatus.GENERATING)
        val readyBlock = block(NarrationBlockStatus.PROCESSED, "block-ready")
        val generatingBlock = block(NarrationBlockStatus.PENDING, "block-generating", ordinal = 1)
        val model = activeModel()
        val run = run(GenerationRunStatus.RUNNING)
        val ready = segment(
            status = AudioSegmentStatus.READY,
            runId = run.id,
            id = "ready",
            blockId = readyBlock.id,
            audioPath = artifact.file.absolutePath,
            audioSha256 = artifact.sha256,
            sizeBytes = artifact.sizeBytes,
        )
        val generating = segment(
            status = AudioSegmentStatus.GENERATING,
            runId = run.id,
            id = "generating",
            blockId = generatingBlock.id,
            sequence = 1,
        )
        insert(dao, project, chapter, readyBlock, generatingBlock, model, run, ready, generating)
        val reconciliation = StartupReconciliation(RoomReconciliationDatabase(database), storage, artifactStore)

        val first = reconciliation.reconcile()
        val second = reconciliation.reconcile()

        assertEquals(listOf(run.id), first.interruptedRunIds)
        assertEquals(listOf(generating.id), first.interruptedSegmentIds)
        assertTrue(first.invalidReadySegmentIds.isEmpty())
        assertTrue(first.staleProvenanceSegmentIds.isEmpty())
        assertTrue(second.interruptedRunIds.isEmpty())
        assertTrue(second.interruptedSegmentIds.isEmpty())
        assertEquals(BookProjectStatus.READY, dao.findAllProjects().single().status)
        assertEquals(ChapterStatus.PARTIAL, dao.findAllChapters().single().status)
        assertEquals(AudioSegmentStatus.READY, dao.findAllAudioSegments().single { it.id == ready.id }.status)
        assertEquals(AudioSegmentStatus.PENDING, dao.findAllAudioSegments().single { it.id == generating.id }.status)
        assertTrue(artifact.file.exists())
    }

    @Test
    public fun roomReconciliationKeepsAnUnaffectedReadySegmentReusable() {
        val storage = AppPrivateStorage(storageRoot)
        val artifactStore = AtomicArtifactStore(storage)
        val staleArtifact = artifactStore.publish(
            ownerId = "run",
            destination = storage.readySegmentAudio("book", "chapter", "stale"),
            writer = { it.write("stale candidate".toByteArray()) },
        )
        val reusableArtifact = artifactStore.publish(
            ownerId = "run",
            destination = storage.readySegmentAudio("book", "chapter", "reusable"),
            writer = { it.write("reusable audio".toByteArray()) },
        )
        val dao = database.audiobookDao()
        val project = project(BookProjectStatus.COMPLETED)
        val chapter = chapter(ChapterStatus.READY)
        val staleBlock = block(NarrationBlockStatus.PROCESSED, "block-stale")
        val reusableBlock = block(NarrationBlockStatus.PROCESSED, "block-reusable", ordinal = 1)
        val model = activeModel()
        val run = run(GenerationRunStatus.COMPLETED)
        val stale = segment(
            AudioSegmentStatus.READY,
            run.id,
            "stale",
            blockId = staleBlock.id,
            audioPath = staleArtifact.file.absolutePath,
            audioSha256 = staleArtifact.sha256,
            sizeBytes = staleArtifact.sizeBytes,
        )
        val reusable = segment(
            AudioSegmentStatus.READY,
            run.id,
            "reusable",
            blockId = reusableBlock.id,
            sequence = 1,
            audioPath = reusableArtifact.file.absolutePath,
            audioSha256 = reusableArtifact.sha256,
            sizeBytes = reusableArtifact.sizeBytes,
        )
        insert(dao, project, chapter, staleBlock, reusableBlock, model, run, stale, reusable)

        val report = StartupReconciliation(RoomReconciliationDatabase(database), storage, artifactStore).reconcile(
            expectedGenerationKeys = mapOf(stale.id to "new-key", reusable.id to reusable.generationKey!!),
        )

        assertEquals(listOf(stale.id), report.staleGenerationKeySegmentIds)
        assertEquals(AudioSegmentStatus.STALE, dao.findAudioSegmentById(stale.id)?.status)
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById(reusable.id)?.status)
        assertTrue(reusableArtifact.file.exists())
    }

    @Test
    public fun roomRetryLimitLeavesThePersistedFailedSegmentUntouched() {
        val dao = database.audiobookDao()
        val project = project(BookProjectStatus.GENERATING)
        val chapter = chapter(ChapterStatus.GENERATING)
        val block = block(NarrationBlockStatus.PROCESSED)
        val model = activeModel()
        val run = run(GenerationRunStatus.RUNNING)
        val segment = segment(AudioSegmentStatus.FAILED, run.id, "failed").copy(
            attemptCount = 3,
            lastError = "INFERENCE_FAILURE: model unavailable",
        )
        insert(dao, project, chapter, block, model, run, segment)

        assertThrows(IllegalStateException::class.java) {
            GenerationStateService(database, retryPolicy = GenerationRetryPolicy(maxAttempts = 3))
                .retryAudioSegment(segment.id)
        }

        val saved = dao.findAudioSegmentById(segment.id)!!
        assertEquals(AudioSegmentStatus.FAILED, saved.status)
        assertEquals(3, saved.attemptCount)
        assertEquals(segment.lastError, saved.lastError)
    }

    private fun insert(
        dao: AudiobookDao,
        project: BookProjectEntity,
        chapter: ChapterEntity,
        vararg remaining: Any,
    ) {
        dao.insertProject(project)
        dao.insertChapter(chapter)
        remaining.forEach { value ->
            when (value) {
                is NarrationBlockEntity -> dao.insertNarrationBlock(value)
                is ModelPackageEntity -> dao.insertModelPackage(value)
                is GenerationRunEntity -> dao.insertGenerationRun(value)
                is AudioSegmentEntity -> dao.insertAudioSegment(value)
                else -> error("Unsupported fixture entity: ${value::class}")
            }
        }
    }

    private fun project(status: BookProjectStatus) = BookProjectEntity(
        id = "book",
        title = "Book",
        sourceUri = "content://book",
        sourceFingerprint = "fingerprint",
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(status: ChapterStatus) = ChapterEntity(
        id = "chapter",
        bookProjectId = "book",
        ordinal = 0,
        title = "Chapter",
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun block(
        status: NarrationBlockStatus,
        id: String = "block",
        ordinal: Int = 0,
    ) = NarrationBlockEntity(
        id = id,
        chapterId = "chapter",
        ordinal = ordinal,
        blockType = NarrationBlockType.PARAGRAPH,
        sourceText = "Tekst.",
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun activeModel() = ModelPackageEntity(
        id = "model",
        packageIdentity = "model@1",
        packageVersion = "1",
        packageSha256 = "package",
        modelSha256 = "model-sha",
        voiceSha256 = "voice",
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        packagePath = "model.zip",
        status = ModelPackageStatus.ACTIVE,
        importedAt = 1,
    )

    private fun run(status: GenerationRunStatus) = GenerationRunEntity(
        id = "run",
        bookProjectId = "book",
        modelPackageId = "model",
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettingsHash = "settings",
        audioProcessingVersion = "audio-v1",
        status = status,
        requestedAt = 1,
    )

    private fun segment(
        status: AudioSegmentStatus,
        runId: String,
        id: String,
        blockId: String = "block",
        sequence: Int = 0,
        audioPath: String? = null,
        audioSha256: String? = null,
        sizeBytes: Long? = null,
        attemptCount: Int = 0,
    ) = AudioSegmentEntity(
        id = id,
        chapterId = "chapter",
        narrationBlockId = blockId,
        sequence = sequence,
        chunkOrdinal = 0,
        generationKey = "generation-$id",
        generationRunId = runId,
        modelPackageId = "model",
        modelPackageSha256 = "package",
        voiceSha256 = "voice",
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettingsHash = "settings",
        audioProcessingVersion = "audio-v1",
        status = status,
        audioPath = audioPath,
        audioSha256 = audioSha256,
        sizeBytes = sizeBytes,
        attemptCount = attemptCount,
        createdAt = 1,
        updatedAt = 1,
    )
}
