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
import com.homoludens.citacknjiga.core.generation.BoundedGenerationRunner
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationFailureException
import com.homoludens.citacknjiga.core.generation.GenerationKeyCalculator
import com.homoludens.citacknjiga.core.generation.GenerationKeyInput
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationStateService
import com.homoludens.citacknjiga.core.generation.SegmentGenerator
import com.homoludens.citacknjiga.core.reconciliation.RoomReconciliationDatabase
import com.homoludens.citacknjiga.core.reconciliation.StartupReconciliation
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.core.storage.GenerationStoragePolicy
import com.homoludens.citacknjiga.core.storage.GenerationStorageRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class CrossComponentGenerationRecoveryAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var databaseName: String
    private lateinit var storage: AppPrivateStorage
    private lateinit var artifactStore: AtomicArtifactStore

    @Before
    public fun setUp() {
        databaseName = "cross-generation-${UUID.randomUUID()}.db"
        storage = AppPrivateStorage(File(context.cacheDir, "cross-generation-${UUID.randomUUID()}"))
        artifactStore = AtomicArtifactStore(storage)
        database = AudiobookDatabase.create(context, databaseName)
    }

    @After
    public fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun changingOneBlockStalesAndRegeneratesOnlyItsSegment() = runBlocking {
        val firstKey = key(listOf(0, 11, 0))
        val oldSecondKey = key(listOf(0, 12, 0))
        val newSecondKey = key(listOf(0, 13, 0))
        val firstBytes = "first-ready".toByteArray()
        val oldSecondBytes = "old-second-ready".toByteArray()
        seedProject(
            projectStatus = BookProjectStatus.COMPLETED,
            chapterStatus = ChapterStatus.READY,
            runStatus = GenerationRunStatus.QUEUED,
            segments = listOf(
                readySegment("first", "block-first", 0, firstKey, firstBytes),
                readySegment("second", "block-second", 1, oldSecondKey, oldSecondBytes),
            ),
        )
        val dao = database.audiobookDao()
        dao.updateNarrationBlock(
            dao.findNarrationBlockById("block-second")!!.copy(sourceText = "Text block-second changed"),
        )

        val report = StartupReconciliation(
            database = RoomReconciliationDatabase(database),
            storage = storage,
            artifactStore = artifactStore,
        ).reconcile(expectedGenerationKeys = mapOf("first" to firstKey, "second" to newSecondKey))

        assertEquals(listOf("second"), report.staleGenerationKeySegmentIds)
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("first")!!.status)
        assertEquals(AudioSegmentStatus.STALE, dao.findAudioSegmentById("second")!!.status)
        val oldSecondPath = File(requireNotNull(dao.findAudioSegmentById("second")!!.audioPath))
        val oldFirstPath = File(requireNotNull(dao.findAudioSegmentById("first")!!.audioPath))
        GenerationStateService(database) { 2L }.transitionAudioSegment("second", AudioSegmentStatus.PENDING)

        val generated = mutableListOf<String>()
        val result = BoundedGenerationRunner(
            state = GenerationStateService(database) { 2L },
            storage = storage,
            artifactStore = artifactStore,
            ioDispatcher = Dispatchers.Unconfined,
            generator = SegmentGenerator { segment, _ ->
                generated += segment.id
                GeneratedSegmentAudio(
                    provenance = provenance(newSecondKey),
                    sampleRateHz = 24_000,
                    channels = 1,
                    durationMs = 20L,
                    writer = { it.write("regenerated-second".toByteArray()) },
                    validator = { file -> require(file.readBytes().contentEquals("regenerated-second".toByteArray())) },
                )
            },
        ).run("run")

        assertEquals(listOf("second"), generated)
        assertEquals(listOf("second"), result.generatedSegmentIds)
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("first")!!.status)
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("second")!!.status)
        assertEquals(firstBytes.toList(), oldFirstPath.readBytes().toList())
        assertEquals(oldSecondBytes.toList(), oldSecondPath.readBytes().toList())
        assertNotEquals(oldSecondPath.path, dao.findAudioSegmentById("second")!!.audioPath)
        assertEquals("regenerated-second", File(dao.findAudioSegmentById("second")!!.audioPath!!).readText())
    }

    @Test
    public fun insufficientStorageStopsBeforeGenerationAndDuringGeneration() = runBlocking {
        val source = storage.sourceDocument("book").apply {
            parentFile!!.mkdirs()
            writeText("private source")
        }
        seedProject(
            projectStatus = BookProjectStatus.READY,
            chapterStatus = ChapterStatus.PENDING,
            runStatus = GenerationRunStatus.QUEUED,
            segments = listOf(pendingSegment("first", "block-first", 0), pendingSegment("second", "block-second", 1)),
        )
        var available = 0L
        val policy = storagePolicy { available }
        val before = BoundedGenerationRunner(
            state = GenerationStateService(database),
            storage = storage,
            artifactStore = artifactStore,
            generator = deterministicGenerator(),
            ioDispatcher = Dispatchers.Unconfined,
            storagePolicy = policy,
            storageRequests = listOf(GenerationStorageRequest("first", 10L)),
        )
        val beforeFailure = runCatching { before.run("run") }.exceptionOrNull()

        assertTrue(beforeFailure is GenerationFailureException)
        assertEquals("INSUFFICIENT_STORAGE", (beforeFailure as GenerationFailureException).stableCode)
        assertEquals(GenerationRunStatus.QUEUED, database.audiobookDao().findGenerationRunById("run")!!.status)
        assertTrue(source.exists())

        database.audiobookDao().updateGenerationRun(
            database.audiobookDao().findGenerationRunById("run")!!.copy(status = GenerationRunStatus.QUEUED),
        )
        available = 30L
        val generated = mutableListOf<String>()
        val during = BoundedGenerationRunner(
            state = GenerationStateService(database),
            storage = storage,
            artifactStore = artifactStore,
            generator = deterministicGenerator(generated) { available = 0L },
            ioDispatcher = Dispatchers.Unconfined,
            storagePolicy = storagePolicy { available },
            storageRequests = listOf(
                GenerationStorageRequest("first", 10L),
                GenerationStorageRequest("second", 10L),
            ),
        )
        val result = during.run("run")

        assertEquals(listOf("first"), generated)
        assertEquals(listOf("first"), result.generatedSegmentIds)
        assertEquals(com.homoludens.citacknjiga.core.database.GenerationRunStatus.FAILED, resultStatus())
        assertEquals(AudioSegmentStatus.READY, database.audiobookDao().findAudioSegmentById("first")!!.status)
        assertEquals(AudioSegmentStatus.PENDING, database.audiobookDao().findAudioSegmentById("second")!!.status)
        assertTrue(source.exists())
    }

    private fun resultStatus(): GenerationRunStatus = database.audiobookDao().findGenerationRunById("run")!!.status

    private fun storagePolicy(available: () -> Long) = GenerationStoragePolicy(
        storage = storage,
        safetyMarginPercent = 0,
        minimumSafetyMarginBytes = 0,
        availableBytes = available,
    )

    private fun deterministicGenerator(
        generated: MutableList<String> = mutableListOf(),
        afterGenerate: () -> Unit = {},
    ): SegmentGenerator =
        SegmentGenerator { segment, _ ->
            generated += segment.id
            afterGenerate()
            GeneratedSegmentAudio(
                provenance = provenance(segment.generationKey ?: "generated-${segment.id}"),
                sampleRateHz = 24_000,
                channels = 1,
                durationMs = 20L,
                writer = { it.write("audio-${segment.id}".toByteArray()) },
                validator = { file -> require(file.length() > 0L) },
            )
        }

    private fun provenance(generationKey: String) = GenerationProvenance(
        generationKey = generationKey,
        modelPackageId = "model",
        modelPackageSha256 = PACKAGE_SHA,
        voiceSha256 = VOICE_SHA,
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettingsHash = SETTINGS_SHA,
        audioProcessingVersion = "audio-v1",
    )

    private fun key(tokens: List<Int>): String = GenerationKeyCalculator.generationKey(
        GenerationKeyInput(
            tokens = tokens,
            modelSha256 = MODEL_SHA,
            voiceSha256 = VOICE_SHA,
            preprocessingVersion = "prep-v1",
            pronunciationVersion = "pron-v1",
            inferenceSettings = mapOf("speed" to "1.0"),
            audioProcessingVersion = "audio-v1",
        ),
    )

    private fun seedProject(
        projectStatus: BookProjectStatus,
        chapterStatus: ChapterStatus,
        runStatus: GenerationRunStatus,
        segments: List<AudioSegmentEntity>,
    ) {
        val dao = database.audiobookDao()
        dao.insertProject(BookProjectEntity("book", "Book", "Author", "content://book", "fingerprint", status = projectStatus, createdAt = 1L, updatedAt = 1L))
        dao.insertChapter(ChapterEntity("chapter", "book", 0, "Chapter", status = chapterStatus, createdAt = 1L, updatedAt = 1L))
        segments.map { it.narrationBlockId }.distinct().forEachIndexed { index, id ->
            dao.insertNarrationBlock(NarrationBlockEntity(id, "chapter", index, NarrationBlockType.PARAGRAPH, "Text $id", status = NarrationBlockStatus.PROCESSED, createdAt = 1L, updatedAt = 1L))
        }
        dao.insertModelPackage(ModelPackageEntity("model", "model@1", "1", PACKAGE_SHA, MODEL_SHA, VOICE_SHA, "prep-v1", "pron-v1", "model.zip", ModelPackageStatus.ACTIVE, 1L))
        dao.insertGenerationRun(GenerationRunEntity("run", "book", "model", "prep-v1", "pron-v1", SETTINGS_SHA, "audio-v1", runStatus, requestedAt = 1L))
        segments.forEach(dao::insertAudioSegment)
    }

    private fun readySegment(id: String, blockId: String, sequence: Int, generationKey: String, bytes: ByteArray): AudioSegmentEntity {
        val file = storage.readySegmentAudio("book", "chapter", id)
        val artifact = artifactStore.publish("seed-$id", file, { it.write(bytes) })
        return baseSegment(id, blockId, sequence, generationKey, AudioSegmentStatus.READY).copy(
            audioPath = artifact.file.path,
            audioSha256 = artifact.sha256,
            sizeBytes = artifact.sizeBytes,
            durationMs = 20L,
        )
    }

    private fun pendingSegment(id: String, blockId: String, sequence: Int): AudioSegmentEntity =
        baseSegment(id, blockId, sequence, key(listOf(0, sequence + 11, 0)), AudioSegmentStatus.PENDING)

    private fun baseSegment(id: String, blockId: String, sequence: Int, generationKey: String, status: AudioSegmentStatus) = AudioSegmentEntity(
        id = id,
        chapterId = "chapter",
        narrationBlockId = blockId,
        sequence = sequence,
        chunkOrdinal = 0,
        generationKey = generationKey,
        generationRunId = "run",
        modelPackageId = "model",
        modelPackageSha256 = PACKAGE_SHA,
        voiceSha256 = VOICE_SHA,
        preprocessingVersion = "prep-v1",
        pronunciationVersion = "pron-v1",
        inferenceSettingsHash = SETTINGS_SHA,
        audioProcessingVersion = "audio-v1",
        status = status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private companion object {
        val PACKAGE_SHA = "a".repeat(64)
        val MODEL_SHA = "b".repeat(64)
        val VOICE_SHA = "c".repeat(64)
        val SETTINGS_SHA = "d".repeat(64)
    }
}
