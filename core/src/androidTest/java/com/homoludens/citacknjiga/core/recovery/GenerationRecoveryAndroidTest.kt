package com.homoludens.citacknjiga.core.recovery

import android.content.Context
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
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.generation.RoomGenerationQueue
import com.homoludens.citacknjiga.core.reconciliation.RoomReconciliationDatabase
import com.homoludens.citacknjiga.core.reconciliation.StartupReconciliation
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class GenerationRecoveryAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var databaseName: String
    private lateinit var storage: AppPrivateStorage

    @Before
    public fun setUp() {
        databaseName = "generation-recovery-${UUID.randomUUID()}.db"
        storage = AppPrivateStorage(File(context.cacheDir, "generation-recovery-${UUID.randomUUID()}"))
        database = AudiobookDatabase.create(context, databaseName)
    }

    @After
    public fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun processDeathDuringInferenceIsRecoverableAfterRoomReopen() {
        assertReconciledAfterReopen("inference")
    }

    @Test
    public fun processDeathDuringWriteIsRecoverableAfterRoomReopen() {
        assertReconciledAfterReopen("write")
    }

    @Test
    public fun processDeathDuringPublicationIsRecoverableAfterRoomReopen() {
        assertReconciledAfterReopen("publication")
    }

    @Test
    public fun simulatedDeviceRebootReconcilesAndLeavesRetryQueued() {
        assertReconciledAfterReopen("device-reboot")
    }

    @Test
    public fun simulatedApplicationUpdateReconcilesAndLeavesRetryQueued() {
        assertReconciledAfterReopen("application-update")
    }

    @Test
    public fun insufficientStoragePreservesPrivateSourceAndRoomProjectState() {
        val source = storage.sourceDocument("book").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("private source")
        }
        database.audiobookDao().insertProject(project(BookProjectStatus.READY))
        val policy = com.homoludens.citacknjiga.core.storage.GenerationStoragePolicy(
            storage = storage,
            safetyMarginPercent = 0,
            minimumSafetyMarginBytes = 0,
            availableBytes = { 0 },
        )

        val failure = runCatching {
            policy.requireCapacity(
                listOf(
                    com.homoludens.citacknjiga.core.storage.GenerationStorageRequest("segment", 1),
                ),
            )
        }.exceptionOrNull()

        assertEquals("INSUFFICIENT_STORAGE", (failure as com.homoludens.citacknjiga.core.generation.GenerationFailureException).stableCode)
        assertEquals(BookProjectStatus.READY, database.audiobookDao().findProjectById("book")!!.status)
        assertTrue(source.exists())
    }

    private fun assertReconciledAfterReopen(phase: String) {
        val store = AtomicArtifactStore(storage)
        val readyArtifact = store.publish(
            ownerId = "ready",
            destination = storage.readySegmentAudio("book", "chapter", "ready"),
            writer = { it.write("verified audio".toByteArray()) },
        )
        val source = storage.sourceDocument("book").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("private source")
        }
        if (phase != "inference") {
            storage.temporaryFile("generation-$phase", "partial.tmp").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("partial")
                setLastModified(1)
            }
        }
        val dao = database.audiobookDao()
        val run = run(GenerationRunStatus.RUNNING)
        dao.insertProject(project(BookProjectStatus.GENERATING))
        dao.insertChapter(chapter(ChapterStatus.GENERATING))
        dao.insertNarrationBlock(block("ready", 0, NarrationBlockStatus.PROCESSED))
        dao.insertNarrationBlock(block("interrupted", 1, NarrationBlockStatus.PENDING))
        dao.insertModelPackage(model())
        dao.insertGenerationRun(run)
        dao.insertAudioSegment(
            segment("ready", 0, AudioSegmentStatus.READY).copy(
                audioPath = readyArtifact.file.path,
                audioSha256 = readyArtifact.sha256,
                sizeBytes = readyArtifact.sizeBytes,
            ),
        )
        dao.insertAudioSegment(segment("interrupted", 1, AudioSegmentStatus.GENERATING))

        database.close()
        database = AudiobookDatabase.create(context, databaseName)
        val reconciliation = StartupReconciliation(
            database = RoomReconciliationDatabase(database),
            storage = storage,
            artifactStore = store,
            temporaryMaxAgeMillis = 0,
        )
        val report = reconciliation.reconcile()

        assertEquals(listOf(run.id), report.interruptedRunIds)
        assertEquals(listOf("interrupted"), report.interruptedSegmentIds)
        assertEquals(GenerationRunStatus.QUEUED, database.audiobookDao().findGenerationRunById(run.id)!!.status)
        assertEquals(AudioSegmentStatus.READY, database.audiobookDao().findAudioSegmentById("ready")!!.status)
        assertEquals(AudioSegmentStatus.PENDING, database.audiobookDao().findAudioSegmentById("interrupted")!!.status)
        assertEquals(ChapterStatus.PARTIAL, database.audiobookDao().findChapterById("chapter")!!.status)
        assertEquals(BookProjectStatus.READY, database.audiobookDao().findProjectById("book")!!.status)
        assertEquals(listOf(run.id), RoomGenerationQueue(database, storage).queuedRunIds())
        assertTrue(readyArtifact.file.exists())
        assertTrue(source.exists())
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
        assertFalse(storage.readySegmentAudio("book", "chapter", "interrupted").isFile)
    }

    private fun project(status: BookProjectStatus) = BookProjectEntity(
        id = "book",
        title = "Book",
        sourceUri = "content://book",
        sourceFingerprint = "fingerprint-${UUID.randomUUID()}",
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

    private fun block(id: String, ordinal: Int, status: NarrationBlockStatus) = NarrationBlockEntity(
        id = "block-$id",
        chapterId = "chapter",
        ordinal = ordinal,
        blockType = NarrationBlockType.PARAGRAPH,
        sourceText = "Text $id",
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun model() = ModelPackageEntity(
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

    private fun segment(id: String, sequence: Int, status: AudioSegmentStatus) = AudioSegmentEntity(
        id = id,
        chapterId = "chapter",
        narrationBlockId = "block-$id",
        sequence = sequence,
        chunkOrdinal = 0,
        generationKey = "generation-$id",
        generationRunId = "run",
        modelPackageId = "model",
        modelPackageSha256 = "package",
        voiceSha256 = "voice",
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettingsHash = "settings",
        audioProcessingVersion = "audio-v1",
        status = status,
        createdAt = 1,
        updatedAt = 1,
    )
}
