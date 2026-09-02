package com.homoludens.citacknjiga.core.lifecycle

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
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.generation.BoundedGenerationRunner
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationStateService
import com.homoludens.citacknjiga.core.generation.SegmentGenerator
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class ProjectOperationCoordinatorAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var storage: AppPrivateStorage

    @Before
    public fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = AppPrivateStorage(createTempDirectory().toFile())
        insertFixture()
    }

    @After
    public fun tearDown() {
        database.close()
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun deletionMarkerRejectsFuturePublication() {
        val coordinator = ProjectOperationCoordinator(database) { 10L }

        assertEquals("published", coordinator.withPublicationLock("book") { "published" })
        assertTrue(coordinator.beginDeletion("book"))
        assertTrue(database.audiobookDao().findProjectById("book")!!.isDeleting)
        assertNull(coordinator.withPublicationLock("book") { "must not publish" })
    }

    @Test
    public fun deletionWaitsForPublicationAndWorkerCannotPublishAfterMarker() = runBlocking {
        val coordinator = ProjectOperationCoordinator(database) { 10L }
        val enteredPublication = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val publication = async(Dispatchers.IO) {
            coordinator.withPublicationLock("book") {
                enteredPublication.countDown()
                assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(enteredPublication.await(5, TimeUnit.SECONDS))
        val deletion = async(Dispatchers.IO) { coordinator.beginDeletion("book") }
        assertFalse(deletion.isCompleted)
        releasePublication.countDown()
        publication.await()
        assertTrue(deletion.await())

        val inferenceStarted = CompletableDeferred<Unit>()
        val releaseInference = CompletableDeferred<Unit>()
        val runner = BoundedGenerationRunner(
            state = GenerationStateService(database, projectOperations = coordinator),
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            generator = SegmentGenerator { _, _ ->
                inferenceStarted.complete(Unit)
                releaseInference.await()
                GeneratedSegmentAudio(
                    provenance = GenerationProvenance(
                        generationKey = "generated",
                        modelPackageId = "model",
                        modelPackageSha256 = "package",
                        voiceSha256 = "voice",
                        preprocessingVersion = "prep-v1",
                        pronunciationVersion = "pron-v1",
                        inferenceSettingsHash = "settings",
                        audioProcessingVersion = "audio-v1",
                    ),
                    sampleRateHz = 24_000,
                    channels = 1,
                    durationMs = 10,
                    writer = { it.write("audio".toByteArray()) },
                    validator = {},
                )
            },
        )
        val worker = async(Dispatchers.IO) { runner.run("run") }
        inferenceStarted.await()
        assertTrue(database.audiobookDao().findProjectById("book")!!.isDeleting)
        releaseInference.complete(Unit)

        try {
            worker.await()
            error("Deleting project worker unexpectedly completed")
        } catch (_: CancellationException) {
            // Deletion cancellation is not a generation failure.
        }

        assertEqualsPendingSegment()
        assertFalse(storage.readyAudioDirectory.walkTopDown().any(File::isFile))
    }

    private fun insertFixture() {
        val dao = database.audiobookDao()
        dao.insertProject(
            BookProjectEntity(
                id = "book",
                title = "Book",
                sourceUri = "content://book",
                sourceFingerprint = "fingerprint",
                status = BookProjectStatus.READY,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        dao.insertChapter(ChapterEntity("chapter", "book", 0, "Chapter", createdAt = 1, updatedAt = 1))
        dao.insertNarrationBlock(
            NarrationBlockEntity(
                id = "block",
                chapterId = "chapter",
                ordinal = 0,
                blockType = NarrationBlockType.PARAGRAPH,
                sourceText = "Text",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        dao.insertModelPackage(
            ModelPackageEntity(
                id = "model",
                packageIdentity = "model@1",
                packageVersion = "1",
                packageSha256 = "package",
                modelSha256 = "model",
                voiceSha256 = "voice",
                preprocessingVersion = "prep-v1",
                pronunciationVersion = "pron-v1",
                packagePath = "model.zip",
                status = ModelPackageStatus.ACTIVE,
                importedAt = 1,
            ),
        )
        dao.insertGenerationRun(
            GenerationRunEntity(
                id = "run",
                bookProjectId = "book",
                modelPackageId = "model",
                preprocessingVersion = "prep-v1",
                pronunciationVersion = "pron-v1",
                inferenceSettingsHash = "settings",
                audioProcessingVersion = "audio-v1",
                requestedAt = 1,
            ),
        )
        dao.insertAudioSegment(
            AudioSegmentEntity(
                id = "segment",
                chapterId = "chapter",
                narrationBlockId = "block",
                sequence = 0,
                chunkOrdinal = 0,
                generationRunId = "run",
                modelPackageId = "model",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun assertEqualsPendingSegment() {
        assertTrue(database.audiobookDao().findAudioSegmentById("segment")!!.status == AudioSegmentStatus.PENDING)
        assertTrue(database.audiobookDao().findChapterById("chapter")!!.status == ChapterStatus.GENERATING)
        assertTrue(database.audiobookDao().findGenerationRunById("run")!!.status == GenerationRunStatus.RUNNING)
    }
}
