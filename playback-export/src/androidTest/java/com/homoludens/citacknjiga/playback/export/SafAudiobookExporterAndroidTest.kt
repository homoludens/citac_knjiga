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
import com.homoludens.citacknjiga.tts.onnx.AndroidMediaCodecAacEncoder
import com.homoludens.citacknjiga.tts.onnx.AndroidM4aValidator
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

public class SafAudiobookExporterAndroidTest {
    private lateinit var storage: AppPrivateStorage
    private lateinit var source: java.io.File
    private lateinit var secondSource: java.io.File
    private lateinit var tree: FakeSafTree

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storage = AppPrivateStorage(java.io.File(context.cacheDir, "export-${System.nanoTime()}"))
        source = storage.readySegmentWav("book", "chapter", "segment").apply {
            parentFile!!.mkdirs()
            writeBytes(validWav(2_000))
        }
        secondSource = storage.readySegmentWav("book", "chapter", "segment-2").apply {
            parentFile!!.mkdirs()
            writeBytes(validWav(3_000))
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
        val baseName = "0001-Citanje_knjige.wav"
        tree.addExisting(baseName, "old audio".toByteArray())

        val plan = exporter.plan(tree, request)
        assertTrue(plan.hasCollisions)
        val result = exporter.export(plan)

        assertArrayEquals("old audio".toByteArray(), tree.bytes(baseName))
        assertEquals(1, result.writtenNames.count { it.endsWith(".wav") })
        assertTrue(result.writtenNames.any { it == "0001-Citanje_knjige-2.wav" })
        val merged = tree.bytes("0001-Citanje_knjige-2.wav")
        assertEquals(source.length() + secondSource.length() - 88L, merged.size.toLong() - 44L)
        assertArrayEquals(source.readBytes().copyOfRange(44, source.length().toInt()), merged.copyOfRange(44, 44 + source.length().toInt() - 44))
        assertArrayEquals(
            secondSource.readBytes().copyOfRange(44, secondSource.length().toInt()),
            merged.copyOfRange(44 + source.length().toInt() - 44, merged.size),
        )
        val manifest = ExportManifestCodec.decode(tree.bytes("manifest.json").decodeToString())
        assertEquals(1, manifest.chapters.single().files.size)
        assertEquals(listOf("segment", "segment-2"), manifest.chapters.single().files.single().sourceSegmentIds)
        assertEquals(40L, manifest.chapters.single().durationMs)
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
        tree.addExisting("0001-Citanje_knjige.wav", "old audio".toByteArray())
        val plan = exporter.plan(tree, request, overwriteExisting = true)

        assertTrue(runCatching { exporter.export(plan) }.isFailure)
        assertArrayEquals("old audio".toByteArray(), tree.bytes("0001-Citanje_knjige.wav"))
        exporter.export(plan, overwriteConfirmed = true)
        val merged = tree.bytes("0001-Citanje_knjige.wav")
        assertEquals(source.length() + secondSource.length() - 88L, merged.size.toLong() - 44L)
        assertArrayEquals(source.readBytes().copyOfRange(44, source.length().toInt()), merged.copyOfRange(44, 44 + source.length().toInt() - 44))
    }

    @Test
    public fun m4aSegmentsAreDecodedAndReencodedAsOneChapter() {
        val firstWav = validWavFile(4_800, 2_000)
        val secondWav = validWavFile(4_800, 3_000)
        val firstM4a = storage.readySegmentAudio("book", "chapter", "m4a-1").apply { parentFile!!.mkdirs() }
        val secondM4a = storage.readySegmentAudio("book", "chapter", "m4a-2").apply { parentFile!!.mkdirs() }
        AndroidMediaCodecAacEncoder().encode(firstWav, firstM4a)
        AndroidMediaCodecAacEncoder().encode(secondWav, secondM4a)
        val exporter = SafAudiobookExporter(storage)
        val request = request(listOf(segment("m4a-1", 0, firstM4a, 200L), segment("m4a-2", 1, secondM4a, 200L)))

        val plan = exporter.plan(tree, request)
        val result = exporter.export(plan)

        assertEquals(1, result.writtenNames.count { it.endsWith(".m4a") })
        val output = storage.temporaryFile("test", "chapter.m4a").apply {
            parentFile!!.mkdirs()
            writeBytes(tree.bytes("0001-Citanje_knjige.m4a"))
        }
        val info = AndroidM4aValidator().validate(output)
        assertTrue(info.durationMs > 200L)
        assertTrue(ExportManifestCodec.decode(tree.bytes("manifest.json").decodeToString()).chapters.single().durationMs > 200L)
    }

    @Test
    public fun failedAssemblyLeavesReadySourcesUntouched() {
        val before = source.readBytes()
        val exporter = SafAudiobookExporter(
            storage = storage,
            chapterAssembler = ChapterAudioAssembler { _, _, _ -> error("simulated chapter failure") },
        )

        assertTrue(runCatching { exporter.plan(tree, request()) }.isFailure)
        assertArrayEquals(before, source.readBytes())
    }

    @Test
    public fun providerTemporaryCanBeRetriedAndFinalizedOnce() {
        val exporter = SafAudiobookExporter(storage, chapterAssembler = WavChapterAudioAssembler())
        val plan = exporter.plan(tree, request())
        val temporary = mutableMapOf<String, Uri>()
        val listener = object : ExportProgressListener {
            override fun onTemporaryFile(planned: PlannedExportFile, uri: Uri) {
                temporary[planned.name] = uri
            }
        }
        tree.failRename = true

        assertTrue(runCatching { exporter.export(plan, listener = listener) }.isFailure)
        assertTrue(tree.listChildren().any { it.name.endsWith(".incomplete") })
        tree.failRename = false
        exporter.export(plan, temporaryUris = temporary, listener = listener)

        assertEquals(1, tree.listChildren().count { it.name == "0001-Citanje_knjige.wav" })
        assertEquals(1, tree.listChildren().count { it.name == "manifest.json" })
        assertFalse(tree.listChildren().any { it.name.endsWith(".incomplete") })
    }

    @Test
    public fun providerWithoutRenameCopiesAndPublishesACompleteName() {
        val noRenameTree = FakeSafTree(supportsRename = false)
        val exporter = SafAudiobookExporter(storage, chapterAssembler = WavChapterAudioAssembler())
        val plan = exporter.plan(noRenameTree, request())

        exporter.export(plan)

        assertTrue(noRenameTree.listChildren().any { it.name == "0001-Citanje_knjige.wav" })
        assertFalse(noRenameTree.listChildren().any { it.name.endsWith(".incomplete") })
    }

    @Test
    public fun destinationLossKeepsVerifiedChapterAndRetryDoesNotDuplicateIt() {
        val exporter = SafAudiobookExporter(storage, chapterAssembler = WavChapterAudioAssembler())
        val plan = exporter.plan(tree, request())
        val chapterName = plan.files.first { it.sourceSegments.isNotEmpty() }.name
        val verified = mutableMapOf<String, Int>()
        val listener = object : ExportProgressListener {
            override fun onVerifiedFile(planned: PlannedExportFile, verification: ExportedFileVerification) {
                verified[planned.name] = (verified[planned.name] ?: 0) + 1
            }
        }
        tree.loseAfterFirstRename = true

        assertTrue(runCatching { exporter.export(plan, listener = listener) }.isFailure)
        assertEquals(1, verified[chapterName])
        tree.unavailable = false
        tree.loseAfterFirstRename = false
        exporter.export(plan, skipNames = setOf(chapterName), listener = listener)

        assertEquals(1, verified[chapterName])
        assertEquals(1, tree.listChildren().count { it.name == chapterName })
        assertTrue(tree.listChildren().any { it.name == "manifest.json" })
    }

    private fun request(segments: List<AudioSegmentEntity> = listOf(
        segment("segment", 0, source, 20L),
        segment("segment-2", 1, secondSource, 20L),
    )): ExportRequest {
        return ExportRequest(
            project = project(),
            chapters = listOf(
                ExportChapterInput(
                    ChapterEntity("chapter", "book", 0, "Čitanje knjige", status = ChapterStatus.READY, createdAt = 1L, updatedAt = 1L),
                    segments,
                ),
            ),
        )
    }

    private fun segment(id: String, sequence: Int, file: java.io.File, durationMs: Long): AudioSegmentEntity {
        val artifactStore = AtomicArtifactStore(storage)
        return AudioSegmentEntity(
            id = id,
            chapterId = "chapter",
            narrationBlockId = "block",
            sequence = sequence,
            chunkOrdinal = sequence,
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
            audioPath = file.path,
            audioSha256 = artifactStore.sha256(file),
            sizeBytes = file.length(),
            durationMs = durationMs,
            sampleRate = 24_000,
            channels = 1,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private fun project(): BookProjectEntity = BookProjectEntity(
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
    )

    private fun validWavFile(sampleCount: Int, value: Short): java.io.File = storage.temporaryFile(
        "test",
        "${sampleCount}-${value}.wav",
    ).apply {
        parentFile!!.mkdirs()
        writeBytes(validWav(value, sampleCount))
    }

    private fun validWav(value: Short, sampleCount: Int = 480): ByteArray {
        val dataSize = sampleCount * 2
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
            repeat(sampleCount) { putShort(if (it % 2 == 0) value else (-value).toShort()) }
        }.array()
    }

    private class FakeSafTree(
        private val supportsRename: Boolean = true,
    ) : SafDocumentTree {
        private val values = linkedMapOf<String, ByteArray>()

        override val capabilities: SafProviderCapabilities = SafProviderCapabilities(supportsRename, Long.MAX_VALUE)
        var failRename: Boolean = false
        var loseAfterFirstRename: Boolean = false
        var unavailable: Boolean = false
        private var renamed = false
        private var loseOnNextList = false

        fun addExisting(name: String, bytes: ByteArray) {
            values[name] = bytes
        }

        fun bytes(name: String): ByteArray = checkNotNull(values[name])

        override fun listChildren(): List<SafDocument> {
            if (loseOnNextList) {
                loseOnNextList = false
                unavailable = true
            }
            check(!unavailable) { "provider unavailable" }
            return values.keys.map { name ->
                SafDocument(Uri.parse("content://fake/$name"), name, "application/octet-stream", false)
            }
        }

        override fun createFile(name: String, mimeType: String): Uri? {
            check(!unavailable) { "provider unavailable" }
            if (name in values) return null
            values[name] = ByteArray(0)
            return Uri.parse("content://fake/$name")
        }

        override fun openForWrite(uri: Uri): OutputStream = object : ByteArrayOutputStream() {
            init { check(!unavailable) { "provider unavailable" } }

            override fun close() {
                super.close()
                values[uri.lastPathSegment!!] = toByteArray()
            }
        }

        override fun openForRead(uri: Uri): InputStream {
            check(!unavailable) { "provider unavailable" }
            return ByteArrayInputStream(values.getValue(uri.lastPathSegment!!))
        }

        override fun rename(uri: Uri, name: String): Uri {
            check(!unavailable) { "provider unavailable" }
            check(!failRename) { "rename unavailable" }
            val oldName = uri.lastPathSegment!!
            values[name] = values.remove(oldName) ?: error("missing document")
            renamed = true
            if (loseAfterFirstRename && renamed) loseOnNextList = true
            return Uri.parse("content://fake/$name")
        }

        override fun delete(uri: Uri): Boolean = values.remove(uri.lastPathSegment) != null
    }


    private companion object {
        const val HASH = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
