package com.homoludens.citacknjiga.playback.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

public class SafAudiobookExporterAndroidTest {
    private lateinit var storage: AppPrivateStorage
    private lateinit var source: java.io.File
    private lateinit var tree: FakeSafTree

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storage = AppPrivateStorage(java.io.File(context.cacheDir, "export-${System.nanoTime()}"))
        source = storage.readySegmentWav("book", "chapter", "segment").apply {
            parentFile!!.mkdirs()
            writeBytes(validWav())
        }
        storage.coverImage("book").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }
        tree = FakeSafTree()
    }

    @After
    public fun tearDown() {
        storage.rootDirectory.deleteRecursively()
    }

    @Test
    public fun createsProviderFilesWithCoverAndManifestWithoutOverwritingCollision() {
        val request = request()
        val exporter = SafAudiobookExporter(storage)
        val baseName = "0001-001-Citanje_knjige.wav"
        tree.addExisting(baseName, "old audio".toByteArray())

        val plan = exporter.plan(tree, request)
        assertTrue(plan.hasCollisions)
        val result = exporter.export(plan)

        assertArrayEquals("old audio".toByteArray(), tree.bytes(baseName))
        assertTrue(result.writtenNames.any { it == "0001-001-Citanje_knjige-2.wav" })
        assertTrue(tree.bytes("manifest.json").decodeToString().contains("Book"))
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            tree.bytes("cover.png"),
        )
    }

    @Test
    public fun replacementNeedsExplicitConfirmationAndThenReplacesSelectedFiles() {
        val exporter = SafAudiobookExporter(storage)
        val request = request()
        tree.addExisting("0001-001-Citanje_knjige.wav", "old audio".toByteArray())
        val plan = exporter.plan(tree, request, overwriteExisting = true)

        assertTrue(runCatching { exporter.export(plan) }.isFailure)
        assertArrayEquals("old audio".toByteArray(), tree.bytes("0001-001-Citanje_knjige.wav"))
        exporter.export(plan, overwriteConfirmed = true)
        assertArrayEquals(source.readBytes(), tree.bytes("0001-001-Citanje_knjige.wav"))
    }

    private fun request(): ExportRequest {
        val artifactStore = AtomicArtifactStore(storage)
        val segment = AudioSegmentEntity(
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
            audioSha256 = artifactStore.sha256(source),
            sizeBytes = source.length(),
            durationMs = 20L,
            sampleRate = 24_000,
            channels = 1,
            createdAt = 1L,
            updatedAt = 1L,
        )
        return ExportRequest(
            project = BookProjectEntity(
                id = "book",
                title = "Book",
                author = "Author",
                sourceUri = "content://private",
                sourceFingerprint = HASH,
                coverPath = storage.coverImage("book").path,
                language = "sr",
                status = BookProjectStatus.COMPLETED,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            chapters = listOf(
                ExportChapterInput(
                    ChapterEntity("chapter", "book", 0, "Čitanje knjige", status = ChapterStatus.READY, createdAt = 1L, updatedAt = 1L),
                    listOf(segment),
                ),
            ),
        )
    }

    private class FakeSafTree : SafDocumentTree {
        private val values = linkedMapOf<String, ByteArray>()

        fun addExisting(name: String, bytes: ByteArray) {
            values[name] = bytes
        }

        fun bytes(name: String): ByteArray = checkNotNull(values[name])

        override fun listChildren(): List<SafDocument> = values.keys.map { name ->
            SafDocument(Uri.parse("content://fake/$name"), name, "application/octet-stream", false)
        }

        override fun createFile(name: String, mimeType: String): Uri? {
            if (name in values) return null
            values[name] = ByteArray(0)
            return Uri.parse("content://fake/$name")
        }

        override fun openForWrite(uri: Uri): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                values[uri.lastPathSegment!!] = toByteArray()
            }
        }

        override fun delete(uri: Uri): Boolean = values.remove(uri.lastPathSegment) != null
    }

    private fun validWav(): ByteArray {
        val dataSize = 480 * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(24_000)
            putInt(48_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
            repeat(480) { putShort(if (it % 2 == 0) 2_000 else -2_000) }
        }.array()
    }

    private companion object {
        const val HASH = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
