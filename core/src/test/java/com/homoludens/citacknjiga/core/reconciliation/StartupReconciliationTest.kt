package com.homoludens.citacknjiga.core.reconciliation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.generation.GenerationKeyInput
import com.homoludens.citacknjiga.core.generation.SelectiveRegenerationPolicy
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class StartupReconciliationTest {
    @Test
    public fun simulatedProcessDeathAtInferenceWriteAndPublicationLeavesRecoveryCheckpoint() {
        listOf("inference", "write", "publication").forEach { phase ->
            val root = createTempDirectory().toFile()
            val storage = AppPrivateStorage(root)
            val store = AtomicArtifactStore(storage)
            val project = project(BookProjectStatus.GENERATING)
            val chapter = chapter(ChapterStatus.GENERATING)
            val run = run(GenerationRunStatus.RUNNING)
            val ready = readySegment(storage, store, "ready", run.id)
            val interrupted = segment("interrupted", run.id, AudioSegmentStatus.GENERATING)
            val source = storage.sourceDocument(project.id).apply {
                checkNotNull(parentFile).mkdirs()
                writeText("private source")
            }
            if (phase != "inference") {
                storage.temporaryFile("generation-$phase", "partial.tmp").apply {
                    checkNotNull(parentFile).mkdirs()
                    writeText("partial artifact")
                    setLastModified(1)
                }
            }
            val database = FakeDatabase(snapshot(project, chapter, run, listOf(ready, interrupted)))

            val report = StartupReconciliation(
                database = database,
                storage = storage,
                artifactStore = store,
                temporaryMaxAgeMillis = 0,
            ).reconcile()

            assertEquals(listOf(run.id), report.interruptedRunIds)
            assertEquals(listOf(interrupted.id), report.interruptedSegmentIds)
            assertEquals(if (phase == "inference") 0 else 1, report.removedTemporaryFileCount)
            assertEquals(GenerationRunStatus.QUEUED, database.state.generationRuns.single().status)
            assertEquals(
                AudioSegmentStatus.PENDING,
                database.state.audioSegments.single { it.id == interrupted.id }.status,
            )
            assertEquals(
                AudioSegmentStatus.READY,
                database.state.audioSegments.single { it.id == ready.id }.status,
            )
            assertEquals(ChapterStatus.PARTIAL, database.state.chapters.single().status)
            assertEquals(BookProjectStatus.READY, database.state.projects.single().status)
            assertTrue(storage.readySegmentAudio(project.id, chapter.id, interrupted.id).let { !it.exists() })
            assertTrue(source.exists())
            assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
        }
    }

    @Test
    public fun interruptedStateIsRecoverableAndReconciliationIsIdempotent() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage) { NOW }
        val staleTemporary = storage.temporaryFile("run", "stale.tmp").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("stale")
            setLastModified(1_000)
        }
        val recentTemporary = storage.temporaryFile("run", "recent.tmp").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("recent")
            setLastModified(9_500)
        }
        val project = project(BookProjectStatus.GENERATING)
        val chapter = chapter(ChapterStatus.GENERATING)
        val run = run(GenerationRunStatus.RUNNING)
        val ready = readySegment(storage, store, "ready", run.id)
        val generating = segment("generating", run.id, AudioSegmentStatus.GENERATING)
        val database = FakeDatabase(snapshot(project, chapter, run, listOf(ready, generating)))

        val first = StartupReconciliation(database, storage, store, temporaryMaxAgeMillis = 5_000).reconcile()
        val second = StartupReconciliation(database, storage, store, temporaryMaxAgeMillis = 5_000).reconcile()

        assertEquals(listOf(run.id), first.interruptedRunIds)
        assertEquals(listOf(generating.id), first.interruptedSegmentIds)
        assertEquals(1, first.removedTemporaryFileCount)
        assertTrue(second.interruptedRunIds.isEmpty())
        assertTrue(second.interruptedSegmentIds.isEmpty())
        assertEquals(0, second.removedTemporaryFileCount)
        assertEquals(GenerationRunStatus.QUEUED, database.state.generationRuns.single().status)
        assertEquals(AudioSegmentStatus.PENDING, database.state.audioSegments.single { it.id == generating.id }.status)
        assertEquals(BookProjectStatus.READY, database.state.projects.single().status)
        assertEquals(ChapterStatus.PARTIAL, database.state.chapters.single().status)
        assertFalse(staleTemporary.exists())
        assertTrue(recentTemporary.exists())
    }

    @Test
    public fun missingAndCorruptReadyAudioBecomeStaleWithoutDeletingArtifacts() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val project = project(BookProjectStatus.COMPLETED)
        val chapter = chapter(ChapterStatus.READY)
        val run = run(GenerationRunStatus.COMPLETED)
        val missing = segment(
            id = "missing",
            runId = run.id,
            status = AudioSegmentStatus.READY,
            audioPath = storage.readySegmentAudio(project.id, chapter.id, "missing").absolutePath,
            audioSha256 = "00",
            sizeBytes = 4,
        )
        val corruptFile = storage.readySegmentAudio(project.id, chapter.id, "corrupt").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("bad!")
        }
        val corrupt = segment(
            id = "corrupt",
            runId = run.id,
            status = AudioSegmentStatus.READY,
            audioPath = corruptFile.absolutePath,
            audioSha256 = store.sha256(writeExpectedFile(storage, "checksum")),
            sizeBytes = 4,
        )
        val source = storage.sourceDocument(project.id).apply {
            checkNotNull(parentFile).mkdirs()
            writeText("keep source")
        }
        val model = File(storage.modelPackagesDirectory, "installed.zip").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("keep model")
        }
        val database = FakeDatabase(snapshot(project, chapter, run, listOf(missing, corrupt)))

        val report = StartupReconciliation(database, storage, store, temporaryMaxAgeMillis = 0).reconcile()

        assertEquals(listOf("corrupt", "missing"), report.invalidReadySegmentIds)
        assertTrue(report.staleProvenanceSegmentIds.isEmpty())
        assertTrue(database.state.audioSegments.all { it.status == AudioSegmentStatus.STALE })
        assertEquals(ChapterStatus.PARTIAL, database.state.chapters.single().status)
        assertEquals(BookProjectStatus.READY, database.state.projects.single().status)
        assertTrue(corruptFile.exists())
        assertTrue(source.exists())
        assertTrue(model.exists())
    }

    @Test
    public fun staleProvenanceIsMarkedWithoutRemovingValidAudio() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val project = project(BookProjectStatus.COMPLETED)
        val chapter = chapter(ChapterStatus.READY)
        val run = run(GenerationRunStatus.COMPLETED)
        val file = storage.readySegmentAudio(project.id, chapter.id, "stale").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("valid audio")
        }
        val segment = segment(
            id = "stale",
            runId = run.id,
            status = AudioSegmentStatus.READY,
            modelPackageId = "old-model",
            modelPackageSha256 = "old-package",
            voiceSha256 = "old-voice",
            audioPath = file.absolutePath,
            audioSha256 = store.sha256(file),
            sizeBytes = file.length(),
        )
        val database = FakeDatabase(snapshot(project, chapter, run, listOf(segment)))

        val report = StartupReconciliation(database, storage, store).reconcile()

        assertEquals(listOf(segment.id), report.staleProvenanceSegmentIds)
        assertTrue(report.invalidReadySegmentIds.isEmpty())
        assertEquals(AudioSegmentStatus.STALE, database.state.audioSegments.single().status)
        assertTrue(file.exists())
    }

    @Test
    public fun changingActiveEnginePackageDoesNotInvalidateOlderReadyAudio() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val project = project(BookProjectStatus.COMPLETED)
        val chapter = chapter(ChapterStatus.READY)
        val run = run(GenerationRunStatus.COMPLETED)
        val file = storage.readySegmentAudio(project.id, chapter.id, "kokoro").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("kokoro audio")
        }
        val ready = segment(
            id = "kokoro",
            runId = run.id,
            status = AudioSegmentStatus.READY,
            audioPath = file.absolutePath,
            audioSha256 = store.sha256(file),
            sizeBytes = file.length(),
        )
        val newerPackage = activeModel().copy(
            id = "vits-package",
            packageIdentity = "vits@1",
            packageSha256 = "vits-package",
        )
        val database = FakeDatabase(
            snapshot(project, chapter, run, listOf(ready)).copy(
                activeModelPackage = newerPackage,
                modelPackages = listOf(activeModel(), newerPackage),
            ),
        )

        val report = StartupReconciliation(database, storage, store).reconcile()

        assertTrue(report.staleProvenanceSegmentIds.isEmpty())
        assertEquals(AudioSegmentStatus.READY, database.state.audioSegments.single().status)
        assertEquals(ready, database.state.audioSegments.single())
        assertTrue(file.exists())
    }

    @Test
    public fun onlySegmentsWithStaleGenerationKeysAreInvalidated() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val project = project(BookProjectStatus.COMPLETED)
        val chapter = chapter(ChapterStatus.READY)
        val run = run(GenerationRunStatus.COMPLETED)
        val stale = readySegment(storage, store, "stale-key", run.id)
        val reusable = readySegment(storage, store, "reusable", run.id)
        val database = FakeDatabase(snapshot(project, chapter, run, listOf(stale, reusable)))
        val expectedStaleKey = SelectiveRegenerationPolicy.expectedGenerationKeys(
            mapOf(stale.id to generationKeyInput()),
        ).getValue(stale.id)

        val report = StartupReconciliation(database, storage, store).reconcile(
            expectedGenerationKeys = mapOf(
                stale.id to expectedStaleKey,
                reusable.id to reusable.generationKey!!,
            ),
        )

        assertEquals(listOf(stale.id), report.staleGenerationKeySegmentIds)
        assertEquals(AudioSegmentStatus.STALE, database.state.audioSegments.single { it.id == stale.id }.status)
        assertEquals(AudioSegmentStatus.READY, database.state.audioSegments.single { it.id == reusable.id }.status)
        assertTrue(storage.readySegmentAudio(project.id, chapter.id, reusable.id).exists())
    }

    private fun snapshot(
        project: BookProjectEntity,
        chapter: ChapterEntity,
        run: GenerationRunEntity,
        segments: List<AudioSegmentEntity>,
    ): ReconciliationSnapshot = ReconciliationSnapshot(
        projects = listOf(project),
        chapters = listOf(chapter),
        generationRuns = listOf(run),
        audioSegments = segments,
        activeModelPackage = activeModel(),
    )

    private fun project(status: BookProjectStatus): BookProjectEntity = BookProjectEntity(
        id = "book",
        title = "Book",
        sourceUri = "content://book",
        sourceFingerprint = "fingerprint",
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(status: ChapterStatus): ChapterEntity = ChapterEntity(
        id = "chapter",
        bookProjectId = "book",
        ordinal = 0,
        title = "Chapter",
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun run(status: GenerationRunStatus): GenerationRunEntity = GenerationRunEntity(
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
        id: String,
        runId: String,
        status: AudioSegmentStatus,
        modelPackageId: String = "model",
        modelPackageSha256: String = "package",
        voiceSha256: String = "voice",
        audioPath: String? = null,
        audioSha256: String? = null,
        sizeBytes: Long? = null,
    ): AudioSegmentEntity = AudioSegmentEntity(
        id = id,
        chapterId = "chapter",
        narrationBlockId = "block-$id",
        sequence = if (id == "ready") 0 else 1,
        chunkOrdinal = 0,
        generationKey = "generation-$id",
        generationRunId = runId,
        modelPackageId = modelPackageId,
        modelPackageSha256 = modelPackageSha256,
        voiceSha256 = voiceSha256,
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettingsHash = "settings",
        audioProcessingVersion = "audio-v1",
        status = status,
        audioPath = audioPath,
        audioSha256 = audioSha256,
        sizeBytes = sizeBytes,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun readySegment(
        storage: AppPrivateStorage,
        store: AtomicArtifactStore,
        id: String,
        runId: String,
    ): AudioSegmentEntity {
        val file = storage.readySegmentAudio("book", "chapter", id).apply {
            checkNotNull(parentFile).mkdirs()
            writeText("verified audio")
        }
        return segment(
            id = id,
            runId = runId,
            status = AudioSegmentStatus.READY,
            audioPath = file.absolutePath,
            audioSha256 = store.sha256(file),
            sizeBytes = file.length(),
        )
    }

    private fun writeExpectedFile(storage: AppPrivateStorage, name: String): File =
        storage.readySegmentAudio("book", "chapter", name).apply {
            checkNotNull(parentFile).mkdirs()
            writeText("good")
        }

    private fun activeModel(): ModelPackageEntity = ModelPackageEntity(
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

    private fun generationKeyInput() = GenerationKeyInput(
        tokens = listOf(0, 1, 0),
        modelSha256 = "model-sha",
        voiceSha256 = "voice-sha",
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettings = mapOf("speed" to "1.0"),
        audioProcessingVersion = "audio-v1",
    )

    private class FakeDatabase(initialState: ReconciliationSnapshot) : ReconciliationDatabase {
        var state: ReconciliationSnapshot = initialState

        override fun snapshot(): ReconciliationSnapshot = state

        override fun inTransaction(block: () -> Unit) {
            block()
        }

        override fun updateProject(project: BookProjectEntity) {
            state = state.copy(projects = state.projects.replace(project) { it.id == project.id })
        }

        override fun updateChapter(chapter: ChapterEntity) {
            state = state.copy(chapters = state.chapters.replace(chapter) { it.id == chapter.id })
        }

        override fun updateGenerationRun(run: GenerationRunEntity) {
            state = state.copy(generationRuns = state.generationRuns.replace(run) { it.id == run.id })
        }

        override fun updateAudioSegment(segment: AudioSegmentEntity) {
            state = state.copy(audioSegments = state.audioSegments.replace(segment) { it.id == segment.id })
        }

        private fun <T> List<T>.replace(value: T, matches: (T) -> Boolean): List<T> =
            map { if (matches(it)) value else it }
    }

    private companion object {
        const val NOW = 10_000L
    }
}
