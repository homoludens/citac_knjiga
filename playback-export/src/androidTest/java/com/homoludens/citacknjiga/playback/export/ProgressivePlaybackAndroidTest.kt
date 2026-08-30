package com.homoludens.citacknjiga.playback.export

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
import com.homoludens.citacknjiga.core.generation.BoundedGenerationStatus
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationStateService
import com.homoludens.citacknjiga.core.generation.SegmentGenerator
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end progressive playback proof using Room and deterministic local audio.
 * The test uses ExoPlayer through the same queue port as the Media3 service. The
 * WAV payload and virtual generation gate keep it independent of the TTS model.
 */
public class ProgressivePlaybackAndroidTest {
    private lateinit var database: AudiobookDatabase
    private lateinit var storage: AppPrivateStorage
    private lateinit var artifactStore: AtomicArtifactStore
    private lateinit var projectId: String

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        projectId = "task-9-8-${UUID.randomUUID()}"
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = AppPrivateStorage(context.filesDir)
        artifactStore = AtomicArtifactStore(storage)
    }

    @After
    public fun tearDown() {
        database.close()
        storage.readySegmentAudio(projectId, "cleanup", "cleanup").parentFile?.parentFile?.deleteRecursively()
    }

    @Test
    public fun completedChapterKeepsPlayingWhileLaterChapterGenerates() = runBlocking {
        val firstChapter = chapter("chapter-1", 0, ChapterStatus.READY)
        val laterChapter = chapter("chapter-2", 1, ChapterStatus.PENDING)
        val firstSegmentId = "chapter-1-segment"
        val laterSegmentId = "chapter-2-segment"
        val runId = "progressive-run"
        val dao = database.audiobookDao()

        dao.insertProject(project())
        dao.insertChapter(firstChapter)
        dao.insertChapter(laterChapter)
        dao.insertNarrationBlock(block(firstChapter.id))
        dao.insertNarrationBlock(block(laterChapter.id))
        dao.insertModelPackage(model())
        dao.insertGenerationRun(completedRun("initial-run"))
        dao.insertGenerationRun(queuedRun(runId))

        val firstArtifact = publish(firstSegmentId, firstChapter.id, seed = 1)
        dao.insertAudioSegment(
            segment(
                id = firstSegmentId,
                chapterId = firstChapter.id,
                runId = "initial-run",
                status = AudioSegmentStatus.READY,
                artifact = firstArtifact,
            ),
        )
        val laterSegment = segment(laterSegmentId, laterChapter.id, runId = runId)
        dao.insertAudioSegment(laterSegment)

        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var player: ExoPlayer
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(context).build()
        }
        val queuePlayer = Media3PlaybackQueuePlayer(player)
        val initialQueueApplied = CompletableDeferred<Unit>()
        val laterQueueApplied = CompletableDeferred<Unit>()
        val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val coordinator = PlaybackQueueCoordinator(
            readyAudio = ReadyAudioRepository(
                source = RoomReadyAudioSource(dao),
                storage = storage,
                artifactStore = artifactStore,
                formatValidator = wavFormatValidator(),
                validationDispatcher = Dispatchers.IO,
            ),
            player = queuePlayer,
            scope = queueScope,
            mediaItemFactory = { audio ->
                MediaItem.Builder()
                    .setMediaId(audio.segment.id)
                    .setUri(android.net.Uri.fromFile(audio.file))
                    .setMimeType(MimeTypes.AUDIO_WAV)
                    .build()
            },
            onCatalogChanged = { catalog ->
                when (catalog.mediaItemIds) {
                    listOf(firstSegmentId) -> initialQueueApplied.complete(Unit)
                    listOf(firstSegmentId, laterSegmentId) -> laterQueueApplied.complete(Unit)
                }
            },
        )
        val generationStarted = CompletableDeferred<Unit>()
        val releaseGeneration = CompletableDeferred<Unit>()
        var generationJob: kotlinx.coroutines.Deferred<com.homoludens.citacknjiga.core.generation.BoundedGenerationResult>? = null

        try {
            coordinator.start(projectId, listOf(firstChapter, laterChapter))
            withTimeout(TIMEOUT_MS) { initialQueueApplied.await() }

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player.prepare()
                player.play()
            }
            awaitPlaying(player)
            generationJob = async(Dispatchers.Default) {
                BoundedGenerationRunner(
                    state = GenerationStateService(database),
                    storage = storage,
                    artifactStore = artifactStore,
                    generator = SegmentGenerator { segment, _ ->
                        assertEquals(laterSegmentId, segment.id)
                        generationStarted.complete(Unit)
                        releaseGeneration.await()
                        audio(segment.id)
                    },
                ).run(runId)
            }

            withTimeout(TIMEOUT_MS) { generationStarted.await() }
            assertEquals(GenerationRunStatus.RUNNING, dao.findGenerationRunById(runId)!!.status)
            assertEquals(AudioSegmentStatus.GENERATING, dao.findAudioSegmentById(laterSegmentId)!!.status)
            assertEquals(listOf(firstSegmentId), playerSnapshot(player).itemIds)

            val positionBeforeGenerationFinishes = playerSnapshot(player).positionMs
            delay(250L)
            val whileGenerating = awaitPlaying(player)
            assertEquals(firstSegmentId, whileGenerating.currentItemId)
            assertTrue(whileGenerating.positionMs > positionBeforeGenerationFinishes)

            releaseGeneration.complete(Unit)
            withTimeout(TIMEOUT_MS) { laterQueueApplied.await() }

            val afterQueueGrowth = awaitPlaying(player, expectedItemCount = 2)
            assertEquals(listOf(firstSegmentId, laterSegmentId), afterQueueGrowth.itemIds)
            assertEquals(firstSegmentId, afterQueueGrowth.currentItemId)
            assertTrue(afterQueueGrowth.positionMs >= whileGenerating.positionMs - POSITION_TOLERANCE_MS)

            assertEquals(BoundedGenerationStatus.COMPLETED, generationJob.await()!!.status)
            assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById(laterSegmentId)!!.status)
            assertTrue(File(dao.findAudioSegmentById(laterSegmentId)!!.audioPath!!).isFile)
        } finally {
            releaseGeneration.complete(Unit)
            generationJob?.cancel()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { coordinator.close() }
            queueScope.cancel()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() }
        }
    }

    private suspend fun awaitPlaying(player: ExoPlayer, expectedItemCount: Int = 1): PlayerSnapshot {
        var result = playerSnapshot(player)
        withTimeout(TIMEOUT_MS) {
            while (!result.isPlaying || result.itemIds.size != expectedItemCount) {
                delay(25L)
                result = playerSnapshot(player)
            }
        }
        return result
    }

    private fun playerSnapshot(player: ExoPlayer): PlayerSnapshot {
        var result = PlayerSnapshot(emptyList(), null, 0L, false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = PlayerSnapshot(
                itemIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
                currentItemId = player.currentMediaItem?.mediaId,
                positionMs = player.currentPosition,
                isPlaying = player.isPlaying,
            )
        }
        return result
    }

    private fun publish(segmentId: String, chapterId: String, seed: Int) = artifactStore.publish(
        ownerId = "proof-$segmentId",
        destination = storage.readySegmentAudio(projectId, chapterId, segmentId),
        writer = { it.write(wavBytes(seed)) },
        validator = { file -> require(isPcmWav(file)) },
    )

    private fun audio(segmentId: String) = GeneratedSegmentAudio(
        provenance = GenerationProvenance(
            generationKey = "generation-$segmentId",
            modelPackageId = MODEL_ID,
            modelPackageSha256 = PACKAGE_SHA256,
            voiceSha256 = VOICE_SHA256,
            preprocessingVersion = PREPROCESSING,
            pronunciationVersion = PRONUNCIATION,
            inferenceSettingsHash = SETTINGS,
            audioProcessingVersion = AUDIO,
        ),
        sampleRateHz = 24_000,
        channels = 1,
        durationMs = DURATION_MS,
        writer = { it.write(wavBytes(2)) },
        validator = { file -> require(isPcmWav(file)) },
    )

    private fun segment(
        id: String,
        chapterId: String,
        runId: String,
        status: AudioSegmentStatus = AudioSegmentStatus.PENDING,
        artifact: com.homoludens.citacknjiga.core.storage.PublishedArtifact? = null,
    ) = AudioSegmentEntity(
        id = id,
        chapterId = chapterId,
        narrationBlockId = "block-$chapterId",
        sequence = if (chapterId == "chapter-1") 0 else 1,
        chunkOrdinal = 0,
        generationKey = artifact?.let { "generation-$id" },
        generationRunId = runId,
        modelPackageId = artifact?.let { MODEL_ID },
        modelPackageSha256 = artifact?.let { PACKAGE_SHA256 },
        voiceSha256 = artifact?.let { VOICE_SHA256 },
        preprocessingVersion = artifact?.let { PREPROCESSING },
        pronunciationVersion = artifact?.let { PRONUNCIATION },
        inferenceSettingsHash = artifact?.let { SETTINGS },
        audioProcessingVersion = artifact?.let { AUDIO },
        status = status,
        audioPath = artifact?.file?.path,
        audioSha256 = artifact?.sha256,
        sizeBytes = artifact?.sizeBytes,
        durationMs = artifact?.let { DURATION_MS },
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun project() = BookProjectEntity(
        id = projectId,
        title = "Progressive proof",
        sourceUri = "content://task-9-8",
        sourceFingerprint = projectId,
        status = BookProjectStatus.READY,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun chapter(id: String, ordinal: Int, status: ChapterStatus) = ChapterEntity(
        id = id,
        bookProjectId = projectId,
        ordinal = ordinal,
        title = id,
        status = status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun block(chapterId: String) = NarrationBlockEntity(
        id = "block-$chapterId",
        chapterId = chapterId,
        ordinal = 0,
        blockType = NarrationBlockType.PARAGRAPH,
        sourceText = "Deterministic proof text.",
        status = NarrationBlockStatus.PROCESSED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun model() = ModelPackageEntity(
        id = MODEL_ID,
        packageIdentity = "task-9-8-model@1",
        packageVersion = "1",
        packageSha256 = PACKAGE_SHA256,
        modelSha256 = "test-model-sha",
        voiceSha256 = VOICE_SHA256,
        preprocessingVersion = PREPROCESSING,
        pronunciationVersion = PRONUNCIATION,
        packagePath = "test-only",
        status = ModelPackageStatus.ACTIVE,
        importedAt = 1L,
    )

    private fun completedRun(id: String) = GenerationRunEntity(
        id = id,
        bookProjectId = projectId,
        modelPackageId = MODEL_ID,
        preprocessingVersion = PREPROCESSING,
        pronunciationVersion = PRONUNCIATION,
        inferenceSettingsHash = SETTINGS,
        audioProcessingVersion = AUDIO,
        status = GenerationRunStatus.COMPLETED,
        requestedAt = 1L,
    )

    private fun queuedRun(id: String) = completedRun(id).copy(status = GenerationRunStatus.QUEUED)

    private fun wavFormatValidator() = PlaybackAudioFormatValidator { file, _ ->
        if (isPcmWav(file)) null else PlaybackUnavailableReason.FORMAT_INVALID
    }

    private fun wavBytes(seed: Int): ByteArray {
        val sampleCount = (SAMPLE_RATE * DURATION_MS / 1_000L).toInt()
        val dataSize = sampleCount * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(36 + dataSize)
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * 2)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray())
            .putInt(dataSize)
            .also { buffer ->
                repeat(sampleCount) { index ->
                    buffer.putShort(if ((index + seed) % 2 == 0) 2_000 else -2_000)
                }
            }
            .array()
    }

    private fun isPcmWav(file: File): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 44 || !bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) ||
            !bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) ||
            !bytes.copyOfRange(36, 40).contentEquals("data".toByteArray())
        ) return false
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 1 &&
            header.getInt(24) == SAMPLE_RATE && header.getShort(34).toInt() == 16 &&
            header.getInt(40) > 0 && header.getInt(40) + 44 <= bytes.size
    }

    private data class PlayerSnapshot(
        val itemIds: List<String>,
        val currentItemId: String?,
        val positionMs: Long,
        val isPlaying: Boolean,
    )

    private companion object {
        const val MODEL_ID = "task-9-8-model"
        const val PACKAGE_SHA256 = "test-package"
        const val VOICE_SHA256 = "test-voice"
        const val PREPROCESSING = "test-preprocessing-v1"
        const val PRONUNCIATION = "test-pronunciation-v1"
        const val SETTINGS = "test-settings"
        const val AUDIO = "test-audio-v1"
        const val SAMPLE_RATE = 24_000
        const val DURATION_MS = 2_000L
        const val TIMEOUT_MS = 5_000L
        const val POSITION_TOLERANCE_MS = 100L
    }
}
