package com.homoludens.citacknjiga.playback.export

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.ExportChapterStatus
import com.homoludens.citacknjiga.core.database.ExportJobStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Destination failures may change export checkpoints, but never the internal project. */
public class ExportFailureIsolationAndroidTest {
    private lateinit var database: AudiobookDatabase
    private lateinit var storage: AppPrivateStorage
    private lateinit var source: File
    private lateinit var tree: FailingSafTree

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = AppPrivateStorage(File(context.cacheDir, "task-10-7-${UUID.randomUUID()}"))
        source = storage.readySegmentWav("book", "chapter", "segment").apply {
            parentFile!!.mkdirs()
            writeBytes(validWav(480))
        }
        tree = FailingSafTree()
        seedProject(database.audiobookDao())
    }

    @After
    public fun tearDown() {
        database.close()
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun destinationWriteFailureOnlyChangesExportState() = runBlocking {
        val dao = database.audiobookDao()
        val artifactStore = AtomicArtifactStore(storage)
        val beforeProject = dao.findProjectById("book")
        val beforeSegment = dao.findAudioSegmentById("segment")
        val beforeBytes = source.readBytes()
        val beforeChecksum = artifactStore.sha256(source)
        val validationIssue = PlaybackAvailabilityPolicy(storage, artifactStore, wavPlaybackValidator()).check(requireNotNull(beforeSegment))
        assertTrue(validationIssue?.message ?: "", validationIssue == null)
        val playback = ReadyAudioRepository(
            source = com.homoludens.citacknjiga.playback.export.RoomReadyAudioSource(dao),
            storage = storage,
            artifactStore = artifactStore,
            formatValidator = wavPlaybackValidator(),
        ).observe("book").first()
        assertEquals(listOf("segment"), playback.available.map { it.segment.id })

        val player = SnapshotQueuePlayer()
        val coordinator = PlaybackQueueCoordinator(
            readyAudio = ReadyAudioRepository(
                com.homoludens.citacknjiga.playback.export.RoomReadyAudioSource(dao),
                storage,
                artifactStore,
                formatValidator = wavPlaybackValidator(),
            ),
            player = player,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            mediaItemFactory = { audio -> androidx.media3.common.MediaItem.fromUri(audio.file.toURI().toString()).buildUpon().setMediaId(audio.segment.id).build() },
        )
        coordinator.update(playback)
        player.positionMs = 123L
        val beforeQueue = player.items.map { it.mediaId }

        val service = RoomAudiobookExportService(
            dao = dao,
            exporter = SafAudiobookExporter(
                storage = storage,
                chapterAssembler = WavChapterAudioAssembler(),
                privateAvailableBytes = { Long.MAX_VALUE },
            ),
            contentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver,
            destinationFactory = { tree },
        )
        val plan = service.planForProject(Uri.parse("content://task-10-7/tree"), "book")
        tree.failWrites = true

        assertTrue(runCatching { service.export(plan) }.isFailure)

        assertEquals(beforeProject, dao.findProjectById("book"))
        assertEquals(beforeSegment, dao.findAudioSegmentById("segment"))
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("segment")!!.status)
        assertEquals(beforeChecksum, artifactStore.sha256(source))
        assertArrayEquals(beforeBytes, source.readBytes())
        assertEquals(listOf("segment"), ReadyAudioRepository(
            com.homoludens.citacknjiga.playback.export.RoomReadyAudioSource(dao),
            storage,
            artifactStore,
            formatValidator = wavPlaybackValidator(),
        ).observe("book").first().available.map { it.segment.id })
        assertEquals(beforeQueue, player.items.map { it.mediaId })
        assertEquals(123L, player.positionMs)

        val job = dao.findAllExportJobs().single()
        assertEquals(ExportJobStatus.FAILED, job.status)
        assertEquals(ExportChapterStatus.FAILED, dao.findExportJobChapters(job.id).single().status)
        coordinator.close()
    }

    private fun seedProject(dao: com.homoludens.citacknjiga.core.database.AudiobookDao) {
        dao.insertProject(BookProjectEntity("book", "Book", "Author", "content://source", HASH, status = BookProjectStatus.COMPLETED, createdAt = 1L, updatedAt = 1L))
        dao.insertChapter(ChapterEntity("chapter", "book", 0, "Chapter", status = ChapterStatus.READY, createdAt = 1L, updatedAt = 1L))
        dao.insertNarrationBlock(NarrationBlockEntity("block", "chapter", 0, NarrationBlockType.PARAGRAPH, "Tekst", status = NarrationBlockStatus.PROCESSED, createdAt = 1L, updatedAt = 1L))
        dao.insertModelPackage(ModelPackageEntity("model", "model@1", "1", HASH, HASH, HASH, "prep-v1", "pron-v1", "private", ModelPackageStatus.ACTIVE, 1L))
        dao.insertGenerationRun(GenerationRunEntity("run", "book", "model", "prep-v1", "pron-v1", HASH, "audio-v1", GenerationRunStatus.COMPLETED, requestedAt = 1L))
        val store = AtomicArtifactStore(storage)
        dao.insertAudioSegment(
            AudioSegmentEntity(
                id = "segment",
                chapterId = "chapter",
                narrationBlockId = "block",
                sequence = 0,
                chunkOrdinal = 0,
                generationKey = HASH,
                generationRunId = "run",
                modelPackageId = "model",
                modelPackageSha256 = HASH,
                voiceSha256 = HASH,
                preprocessingVersion = "prep-v1",
                pronunciationVersion = "pron-v1",
                inferenceSettingsHash = HASH,
                audioProcessingVersion = "audio-v1",
                status = AudioSegmentStatus.READY,
                audioPath = source.path,
                audioSha256 = store.sha256(source),
                sizeBytes = source.length(),
                durationMs = 20L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun validWav(sampleCount: Int): ByteArray = ByteBuffer.allocate(44 + sampleCount * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + sampleCount * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(sampleCount * 2)
        .also { buffer -> repeat(sampleCount) { buffer.putShort(if (it % 2 == 0) 2_000 else -2_000) } }
        .array()

    private fun wavPlaybackValidator() = PlaybackAudioFormatValidator { file, _ ->
        runCatching { com.homoludens.citacknjiga.tts.onnx.PcmWavValidator.validate(file) }
            .fold(onSuccess = { null }, onFailure = { PlaybackUnavailableReason.FORMAT_INVALID })
    }

    private class FailingSafTree : SafDocumentTree {
        private val values = linkedMapOf<String, ByteArray>()
        var failWrites: Boolean = false
        override val capabilities = SafProviderCapabilities(supportsDocumentRename = true, availableBytes = Long.MAX_VALUE)

        override fun listChildren(): List<SafDocument> = values.keys.map { name ->
            SafDocument(Uri.parse("content://task-10-7/$name"), name, "application/octet-stream", false)
        }

        override fun createFile(name: String, mimeType: String): Uri? {
            values[name] = ByteArray(0)
            return Uri.parse("content://task-10-7/$name")
        }

        override fun openForWrite(uri: Uri): OutputStream? {
            check(!failWrites) { "simulated destination write failure" }
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    values[uri.lastPathSegment!!] = toByteArray()
                }
            }
        }

        override fun openForRead(uri: Uri): InputStream = values.getValue(uri.lastPathSegment!!).inputStream()
        override fun rename(uri: Uri, name: String): Uri {
            values[name] = values.remove(uri.lastPathSegment!!) ?: error("missing temporary")
            return Uri.parse("content://task-10-7/$name")
        }

        override fun delete(uri: Uri): Boolean = values.remove(uri.lastPathSegment) != null
    }

    private class SnapshotQueuePlayer : PlaybackQueuePlayerPort {
        override var isPlaying: Boolean = false
        var positionMs: Long = 0L
        override var currentMediaItemId: String? = null
            private set
        var items: List<androidx.media3.common.MediaItem> = emptyList()
            private set

        override val currentPositionMs: Long get() = positionMs
        override fun pause() { isPlaying = false }
        override fun replaceQueue(items: List<androidx.media3.common.MediaItem>, currentItemIndex: Int, positionMs: Long, resumePlayback: Boolean) {
            this.items = items
            this.positionMs = positionMs
            currentMediaItemId = items.getOrNull(currentItemIndex)?.mediaId
            isPlaying = resumePlayback
        }
        override fun addListener(listener: () -> Unit) = Unit
        override fun removeListener(listener: () -> Unit) = Unit
    }

    private companion object {
        const val HASH = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
