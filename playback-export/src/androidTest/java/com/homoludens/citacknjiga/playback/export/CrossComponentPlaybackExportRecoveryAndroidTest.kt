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
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.reconciliation.RoomReconciliationDatabase
import com.homoludens.citacknjiga.core.reconciliation.StartupReconciliation
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

public class CrossComponentPlaybackExportRecoveryAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AudiobookDatabase
    private lateinit var storage: AppPrivateStorage
    private lateinit var source: File
    private lateinit var tree: DisappearingSafTree

    @Before
    public fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = AppPrivateStorage(File(context.cacheDir, "cross-playback-${UUID.randomUUID()}"))
        source = storage.readySegmentWav("book", "chapter", "segment").apply {
            parentFile!!.mkdirs()
            writeBytes(validWav())
        }
        tree = DisappearingSafTree()
        seed()
    }

    @After
    public fun tearDown() {
        database.close()
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun corruptReadyAudioIsInvalidatedAndPlaybackOffersRegeneration() = runBlocking {
        val dao = database.audiobookDao()
        val artifactStore = AtomicArtifactStore(storage)
        val before = requireNotNull(dao.findAudioSegmentById("segment"))
        val original = source.readBytes()
        source.writeBytes(original.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() })

        assertEquals(
            PlaybackUnavailableReason.CHECKSUM_MISMATCH,
            PlaybackAvailabilityPolicy(storage, artifactStore).check(before)?.reason,
        )
        val report = StartupReconciliation(
            database = RoomReconciliationDatabase(database),
            storage = storage,
            artifactStore = artifactStore,
        ).reconcile()
        val snapshot = ReadyAudioRepository(
            source = RoomReadyAudioSource(dao),
            storage = storage,
            artifactStore = artifactStore,
        ).observe("book").first()
        var requested: String? = null

        assertEquals(listOf("segment"), report.invalidReadySegmentIds)
        assertEquals(AudioSegmentStatus.STALE, dao.findAudioSegmentById("segment")!!.status)
        assertEquals(PlaybackUnavailableReason.NOT_READY, snapshot.unavailable.single().reason)
        assertEquals("generation/retry/segment", PlaybackRegenerationRoute { requested = it }.request(snapshot.unavailable.single()))
        assertEquals("segment", requested)
        assertTrue(source.exists())
    }

    @Test
    public fun disappearingExportProviderFailsJobAndRetryUsesPrivateProjectData() {
        val dao = database.audiobookDao()
        val artifactStore = AtomicArtifactStore(storage)
        val beforeProject = dao.findProjectById("book")
        val beforeAudio = source.readBytes()
        val service = RoomAudiobookExportService(
            dao = dao,
            exporter = SafAudiobookExporter(storage, artifactStore, WavChapterAudioAssembler()),
            contentResolver = context.contentResolver,
            destinationFactory = { tree },
        )
        val plan = service.planForProject(Uri.parse("content://fake/tree"), "book")
        tree.disappearAfterCreate = true

        assertTrue(runCatching { service.export(plan) }.isFailure)
        val failedJob = dao.findAllExportJobs().single()
        assertEquals(com.homoludens.citacknjiga.core.database.ExportJobStatus.FAILED, failedJob.status)
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("segment")!!.status)
        assertEquals(beforeProject, dao.findProjectById("book"))
        assertArrayEquals(beforeAudio, source.readBytes())

        tree.unavailable = false
        tree.disappearAfterCreate = false
        service.retry(failedJob.id)

        assertEquals(com.homoludens.citacknjiga.core.database.ExportJobStatus.COMPLETED, dao.findExportJobById(failedJob.id)!!.status)
        assertTrue(tree.values.containsKey("0001-Chapter.wav"))
        assertTrue(tree.values.containsKey("manifest.json"))
        assertTrue(source.exists())
    }

    @Test
    public fun insufficientExportStorageFailsBeforeProviderOrRoomMutation() {
        val dao = database.audiobookDao()
        val beforeAudio = source.readBytes()
        tree.availableBytes = 0L
        val service = RoomAudiobookExportService(
            dao = dao,
            exporter = SafAudiobookExporter(storage, AtomicArtifactStore(storage), WavChapterAudioAssembler()),
            contentResolver = context.contentResolver,
            destinationFactory = { tree },
        )

        val failure = runCatching {
            service.planForProject(Uri.parse("content://fake/full"), "book")
        }.exceptionOrNull()

        assertTrue(failure is InsufficientExportStorageException)
        assertTrue(dao.findAllExportJobs().isEmpty())
        assertTrue(tree.values.isEmpty())
        assertArrayEquals(beforeAudio, source.readBytes())
        assertEquals(AudioSegmentStatus.READY, dao.findAudioSegmentById("segment")!!.status)
    }

    private fun seed() {
        val dao = database.audiobookDao()
        val store = AtomicArtifactStore(storage)
        dao.insertProject(BookProjectEntity("book", "Book", "Author", "content://book", HASH, status = BookProjectStatus.COMPLETED, createdAt = 1L, updatedAt = 1L))
        dao.insertChapter(ChapterEntity("chapter", "book", 0, "Chapter", status = ChapterStatus.READY, createdAt = 1L, updatedAt = 1L))
        dao.insertNarrationBlock(NarrationBlockEntity("block", "chapter", 0, NarrationBlockType.PARAGRAPH, "Tekst", status = NarrationBlockStatus.PROCESSED, createdAt = 1L, updatedAt = 1L))
        dao.insertModelPackage(ModelPackageEntity("model", "model@1", "1", HASH, HASH, HASH, "prep-v1", "pron-v1", "model.zip", ModelPackageStatus.ACTIVE, 1L))
        dao.insertGenerationRun(GenerationRunEntity("run", "book", "model", "prep-v1", "pron-v1", HASH, "audio-v1", GenerationRunStatus.COMPLETED, requestedAt = 1L))
        val artifact = store.sha256(source)
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
                audioSha256 = artifact,
                sizeBytes = source.length(),
                durationMs = 20L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun validWav(sampleCount: Int = 480): ByteArray = ByteBuffer.allocate(44 + sampleCount * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + sampleCount * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(sampleCount * 2)
        .also { buffer -> repeat(sampleCount) { buffer.putShort(if (it % 2 == 0) 2_000 else -2_000) } }
        .array()

    private class DisappearingSafTree : SafDocumentTree {
        val values = linkedMapOf<String, ByteArray>()
        var unavailable: Boolean = false
        var disappearAfterCreate: Boolean = false
        var availableBytes: Long? = Long.MAX_VALUE
        override val capabilities: SafProviderCapabilities
            get() = SafProviderCapabilities(supportsDocumentRename = true, availableBytes = availableBytes)

        override fun listChildren(): List<SafDocument> {
            check(!unavailable) { "provider disappeared" }
            return values.keys.map { SafDocument(Uri.parse("content://fake/$it"), it, "application/octet-stream", false) }
        }

        override fun createFile(name: String, mimeType: String): Uri? {
            check(!unavailable) { "provider disappeared" }
            if (name in values) return null
            values[name] = ByteArray(0)
            if (disappearAfterCreate) unavailable = true
            return Uri.parse("content://fake/$name")
        }

        override fun openForWrite(uri: Uri): OutputStream {
            check(!unavailable) { "provider disappeared" }
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    values[uri.lastPathSegment!!] = toByteArray()
                }
            }
        }

        override fun openForRead(uri: Uri): InputStream {
            check(!unavailable) { "provider disappeared" }
            return values.getValue(uri.lastPathSegment!!).inputStream()
        }

        override fun rename(uri: Uri, name: String): Uri {
            check(!unavailable) { "provider disappeared" }
            values[name] = values.remove(uri.lastPathSegment!!) ?: error("missing temporary")
            return Uri.parse("content://fake/$name")
        }

        override fun delete(uri: Uri): Boolean = values.remove(uri.lastPathSegment) != null
    }

    private companion object {
        const val HASH = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
