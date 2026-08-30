package com.homoludens.citacknjiga.generation

import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.generation.BoundedGenerationRunner
import com.homoludens.citacknjiga.core.generation.BoundedGenerationStatus
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationStateService
import com.homoludens.citacknjiga.core.generation.SegmentGenerator
import com.homoludens.citacknjiga.core.reconciliation.RoomReconciliationDatabase
import com.homoludens.citacknjiga.core.reconciliation.StartupReconciliation
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.document.epub.EpubAcceptanceResult
import com.homoludens.citacknjiga.document.epub.EpubCanonicalTextService
import com.homoludens.citacknjiga.document.epub.EpubDocumentParser
import com.homoludens.citacknjiga.document.epub.EpubImportPreviewService
import com.homoludens.citacknjiga.document.epub.EpubPreviewResult
import com.homoludens.citacknjiga.document.epub.EpubSourceReader
import com.homoludens.citacknjiga.document.epub.RoomEpubProjectIndex
import com.homoludens.citacknjiga.document.epub.SafEpubSourceRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic whole-book recovery proof. The interruption closes Room while the next segment is
 * claimed, which is the safe test substitute for killing this test process mid-inference.
 */
public class MultiChapterResumeAndroidTest {
    @Test
    public fun importedTwoChapterBookResumesAndReusesVerifiedSegment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val projectId = "task-8-10-${UUID.randomUUID()}"
        val databaseName = "$projectId.db"
        val storage = AppPrivateStorage(context.filesDir)
        var database = AudiobookDatabase.create(context, databaseName)
        val artifactStore = AtomicArtifactStore(storage)
        try {
            val repository = SafEpubSourceRepository(
                sourceReader = EpubSourceReader { testContext.assets.open("serbian-epub3.epub") },
                storage = storage,
                artifactStore = artifactStore,
                projectIndex = RoomEpubProjectIndex(database.audiobookDao()),
                projectIdFactory = { projectId },
            )
            val previewService = EpubImportPreviewService(
                sourceRepository = repository,
                parser = EpubDocumentParser(storage),
                canonicalText = EpubCanonicalTextService(storage, artifactStore),
            )
            val preview = when (val result = previewService.previewSource("asset://serbian-epub3.epub")) {
                is EpubPreviewResult.Ready -> result.preview
                is EpubPreviewResult.Duplicate -> error("fixture unexpectedly duplicated")
                is EpubPreviewResult.Failed -> error("fixture preview failed: ${result.message}")
            }
            val accepted = previewService.accept(preview)
            check(accepted is EpubAcceptanceResult.Published) { "fixture acceptance failed: $accepted" }
            assertEquals(2, accepted.preview.document.chapters.size)

            val now = 1L
            val projection = accepted.preview.document.toRoomProjection(accepted.source, now)
            val dao = database.audiobookDao()
            dao.updateProject(projection.project.copy(status = BookProjectStatus.READY))
            projection.chapters.forEach(dao::insertChapter)
            projection.narrationBlocks.forEach(dao::insertNarrationBlock)
            dao.insertModelPackage(model())
            val run = run(projectId)
            dao.insertGenerationRun(run)
            val segments = projection.chapters.flatMap { chapter ->
                projection.narrationBlocks
                    .filter { it.chapterId == chapter.id && it.blockType != NarrationBlockType.SKIPPED && it.sourceText.isNotBlank() }
                    .map { block -> segment(chapter.id, block, chapter.ordinal * 10 + block.ordinal) }
            }
            assertEquals(4, segments.size)
            segments.forEach(dao::insertAudioSegment)

            val interrupted = segments[1]
            val first = segments[0]
            val claimed = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val interruptedCalls = mutableListOf<String>()
            val firstJob = async {
                BoundedGenerationRunner(
                    state = GenerationStateService(database),
                    storage = storage,
                    artifactStore = artifactStore,
                    generator = SegmentGenerator { segment, _ ->
                        interruptedCalls += segment.id
                        if (segment.id == interrupted.id) {
                            claimed.complete(Unit)
                            hold.await()
                        }
                        audio(segment.id)
                    },
                ).run(run.id)
            }

            claimed.await()
            val verifiedBefore = database.audiobookDao().findAudioSegmentById(first.id)!!
            assertEquals(AudioSegmentStatus.READY, verifiedBefore.status)
            val verifiedFile = File(verifiedBefore.audioPath!!)
            val verifiedBytes = verifiedFile.readBytes()
            val verifiedSha256 = verifiedBefore.audioSha256

            firstJob.cancel()
            runCatching { firstJob.await() }

            // Inject the durable crash snapshot that an OS kill would leave, without killing the test process.
            database.close()
            database = AudiobookDatabase.create(context, databaseName)
            database.audiobookDao().updateAudioSegment(
                database.audiobookDao().findAudioSegmentById(interrupted.id)!!.copy(
                    status = AudioSegmentStatus.GENERATING,
                ),
            )
            database.audiobookDao().updateGenerationRun(
                database.audiobookDao().findGenerationRunById(run.id)!!.copy(
                    status = GenerationRunStatus.RUNNING,
                    startedAt = 1,
                    finishedAt = null,
                ),
            )
            database.close()
            database = AudiobookDatabase.create(context, databaseName)
            val reopenedDao = database.audiobookDao()
            val report = StartupReconciliation(
                database = RoomReconciliationDatabase(database),
                storage = storage,
                artifactStore = artifactStore,
            ).reconcile()
            assertEquals(listOf(run.id), report.interruptedRunIds)
            assertEquals(listOf(interrupted.id), report.interruptedSegmentIds)
            assertEquals(AudioSegmentStatus.READY, reopenedDao.findAudioSegmentById(first.id)!!.status)
            assertEquals(AudioSegmentStatus.PENDING, reopenedDao.findAudioSegmentById(interrupted.id)!!.status)
            assertTrue(accepted.source.sourceFile.isFile)
            assertTrue(verifiedFile.isFile)
            val resumedCalls = mutableListOf<String>()
            val resumed = BoundedGenerationRunner(
                state = GenerationStateService(database),
                storage = storage,
                artifactStore = artifactStore,
                generator = SegmentGenerator { segment, _ ->
                    resumedCalls += segment.id
                    audio(segment.id)
                },
            ).run(run.id)

            assertEquals(BoundedGenerationStatus.COMPLETED, resumed.status)
            assertEquals(segments.drop(1).map { it.id }, resumed.generatedSegmentIds)
            assertEquals(segments.drop(1).map { it.id }, resumedCalls)
            assertEquals(1, interruptedCalls.count { it == first.id })
            assertFalse(resumedCalls.contains(first.id))
            assertEquals(BookProjectStatus.COMPLETED, reopenedDao.findProjectById(projectId)!!.status)
            assertEquals(AudioSegmentStatus.READY, reopenedDao.findAudioSegmentById(first.id)!!.status)
            assertEquals(verifiedSha256, reopenedDao.findAudioSegmentById(first.id)!!.audioSha256)
            assertArrayEquals(verifiedBytes, verifiedFile.readBytes())
            assertEquals(AudioSegmentStatus.READY, reopenedDao.findAudioSegmentById(segments.last().id)!!.status)
        } finally {
            runCatching { database.close() }
            context.deleteDatabase(databaseName)
            storage.sourceDocument(projectId).parentFile?.deleteRecursively()
            storage.canonicalChapterText(projectId, "cleanup").parentFile?.deleteRecursively()
            storage.importWarnings(projectId).parentFile?.deleteRecursively()
            storage.readySegmentAudio(projectId, "cleanup", "cleanup").parentFile?.parentFile?.deleteRecursively()
        }
    }

    private fun model() = ModelPackageEntity(
        id = "test-model",
        packageIdentity = "test-model@1",
        packageVersion = "1",
        packageSha256 = PACKAGE_SHA256,
        modelSha256 = "test-model-sha",
        voiceSha256 = VOICE_SHA256,
        preprocessingVersion = PREPROCESSING,
        pronunciationVersion = PRONUNCIATION,
        packagePath = "test-only",
        status = ModelPackageStatus.ACTIVE,
        importedAt = 1,
    )

    private fun run(projectId: String) = GenerationRunEntity(
        id = "task-8-10-run",
        bookProjectId = projectId,
        modelPackageId = "test-model",
        preprocessingVersion = PREPROCESSING,
        pronunciationVersion = PRONUNCIATION,
        inferenceSettingsHash = SETTINGS,
        audioProcessingVersion = AUDIO,
        requestedAt = 1,
    )

    private fun segment(chapterId: String, block: NarrationBlockEntity, sequence: Int) = AudioSegmentEntity(
        id = "$chapterId-segment-${block.ordinal}",
        chapterId = chapterId,
        narrationBlockId = block.id,
        sequence = sequence,
        chunkOrdinal = 0,
        generationRunId = "task-8-10-run",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun audio(segmentId: String) = GeneratedSegmentAudio(
        provenance = GenerationProvenance(
            generationKey = "test-key-$segmentId",
            modelPackageId = "test-model",
            modelPackageSha256 = PACKAGE_SHA256,
            voiceSha256 = VOICE_SHA256,
            preprocessingVersion = PREPROCESSING,
            pronunciationVersion = PRONUNCIATION,
            inferenceSettingsHash = SETTINGS,
            audioProcessingVersion = AUDIO,
        ),
        sampleRateHz = 24_000,
        channels = 1,
        durationMs = 100,
        writer = { output -> output.write("deterministic-audio:$segmentId".toByteArray()) },
        validator = { file ->
            assertEquals("deterministic-audio:$segmentId", file.readText())
        },
    )

    private companion object {
        const val PACKAGE_SHA256 = "test-package"
        const val VOICE_SHA256 = "test-voice"
        const val PREPROCESSING = "test-preprocessing-v1"
        const val PRONUNCIATION = "test-pronunciation-v1"
        const val SETTINGS = "test-settings"
        const val AUDIO = "test-audio-v1"
    }
}
