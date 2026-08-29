package com.homoludens.citacknjiga.proof

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.BookProjectWithRelations
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.ChapterWithRelations
import com.homoludens.citacknjiga.core.database.ExportJobEntity
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.GenerationRunWithSegments
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.document.epub.CanonicalChapterPreview
import com.homoludens.citacknjiga.document.epub.EpubAcceptanceResult
import com.homoludens.citacknjiga.document.epub.EpubChapter
import com.homoludens.citacknjiga.document.epub.EpubDocument
import com.homoludens.citacknjiga.document.epub.EpubImportPreview
import com.homoludens.citacknjiga.document.epub.EpubPublicationMetadata
import com.homoludens.citacknjiga.document.epub.ImportedEpubSource
import com.homoludens.citacknjiga.document.epub.StagedEpubSource
import com.homoludens.citacknjiga.document.epub.EpubCanonicalTextPreview
import com.homoludens.citacknjiga.tts.onnx.OnnxTtsOutput
import com.homoludens.citacknjiga.tts.onnx.PcmWavWriter
import com.homoludens.citacknjiga.tts.onnx.WavArtifact
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubChapterProofServiceTest {
    @Test
    public fun acceptedChapterIsGeneratedPublishedAndRecordedWithProvenance() = runBlocking {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val source = ImportedEpubSource(
            projectId = "known-book",
            sourceUri = "content://known.epub",
            fingerprint = "b".repeat(64),
            sourceFile = storage.sourceDocument("known-book").apply {
                parentFile!!.mkdirs()
                writeText("known EPUB source")
            },
            sizeBytes = "known EPUB source".length.toLong(),
        )
        val chapter = EpubChapter(
            id = "known-book-chapter-0",
            ordinal = 0,
            title = "Прво поглавље",
            sourcePath = "OEBPS/chapter.xhtml",
            sourceLocator = "OEBPS/chapter.xhtml",
            blocks = listOf(
                com.homoludens.citacknjiga.document.epub.EpubNarrationBlock(
                    ordinal = 0,
                    type = com.homoludens.citacknjiga.core.database.NarrationBlockType.PARAGRAPH,
                    sourceText = "Добар дан.",
                    sourceLocator = "OEBPS/chapter.xhtml#/p[1]",
                ),
            ),
        )
        val preview = EpubImportPreview(
            stagedSource = StagedEpubSource(
                source.projectId,
                source.sourceUri,
                source.fingerprint,
                source.sourceFile,
                source.sizeBytes,
            ),
            document = EpubDocument(
                projectId = source.projectId,
                sourceUri = source.sourceUri,
                sourceFingerprint = source.fingerprint,
                sourcePath = source.sourceFile.path,
                metadata = EpubPublicationMetadata("Позната књига", listOf("Аутор"), "sr", "id"),
                cover = null,
                tableOfContents = emptyList(),
                chapters = listOf(chapter),
            ),
            canonical = EpubCanonicalTextPreview(
                chapters = listOf(CanonicalChapterPreview(chapter.id, chapter.title, "Добар дан.", "# Прво", 7)),
                warnings = emptyList(),
                warningReportSizeBytes = 2,
            ),
            storage = com.homoludens.citacknjiga.document.epub.EpubStorageEstimate(1, 7, 0, 2, 65_536),
        )
        val dao = RecordingDao()
        val engine = fakeEngine(storage)
        val accepted = EpubAcceptanceResult.Published(source, preview)

        val result = EpubChapterProofService(dao, storage, AtomicArtifactStore(storage), engine)
            .generate(accepted, chapterOrdinal = 0)

        assertEquals(BookProjectStatus.READY, dao.project.status)
        assertEquals(ChapterStatus.READY, dao.chapter.status)
        assertEquals(AudioSegmentStatus.READY, dao.segment.status)
        assertEquals(GenerationRunStatus.COMPLETED, dao.run.status)
        assertEquals(storage.readyChapterWav(source.projectId, chapter.id), result.audio.file)
        assertTrue(result.audio.file.isFile)
        assertEquals(64, dao.segment.audioSha256!!.length)
        assertEquals(result.audio.file.path, dao.segment.audioPath)
        assertEquals("c".repeat(64), dao.segment.voiceSha256)
    }

    private fun fakeEngine(storage: AppPrivateStorage): TypedTextProofEngine = object : TypedTextProofEngine {
        override suspend fun generate(
            text: String,
            onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
        ): TypedTextProofResult {
            val wav = PcmWavWriter.writeAtomic(
                destination = storage.temporaryFile("test", "proof.wav").apply { parentFile!!.mkdirs() },
                output = OnnxTtsOutput(FloatArray(600) { 0.1f }, longArrayOf(1)),
                expectedTokenCount = 1,
            )
            val diagnostics = TypedTextProofDiagnostics(
                cleanupText = text,
                normalizedText = text,
                phonemes = "d o b a r",
                tokenIds = listOf(0, 1, 0),
                protectedSpans = emptyList(),
                chunkBoundaries = listOf("0..7"),
                voiceRowIndex = 7,
                model = TypedTextModelProvenance("test", "1.0.0", "a".repeat(64), "c".repeat(64)),
            )
            onDiagnostics(diagnostics)
            return TypedTextProofResult(diagnostics, wav)
        }
    }

    private class RecordingDao : AudiobookDao {
        lateinit var project: BookProjectEntity
        lateinit var chapter: ChapterEntity
        lateinit var segment: AudioSegmentEntity
        lateinit var run: GenerationRunEntity

        override fun insertProject(project: BookProjectEntity) { this.project = project }
        override fun insertChapter(chapter: ChapterEntity) { this.chapter = chapter }
        override fun insertNarrationBlock(block: NarrationBlockEntity) { }
        override fun insertModelPackage(modelPackage: ModelPackageEntity) { }
        override fun insertGenerationRun(run: GenerationRunEntity) { this.run = run }
        override fun insertAudioSegment(segment: AudioSegmentEntity) { this.segment = segment }
        override fun findAllProjects(): List<BookProjectEntity> = listOf(project)
        override fun findProjectBySourceFingerprint(fingerprint: String): BookProjectEntity? = null
        override fun findAllChapters(): List<ChapterEntity> = listOf(chapter)
        override fun findAllGenerationRuns(): List<GenerationRunEntity> = listOf(run)
        override fun findAllAudioSegments(): List<AudioSegmentEntity> = listOf(segment)
        override fun findActiveModelPackage(): ModelPackageEntity? = null
        override fun updateProject(project: BookProjectEntity) { this.project = project }
        override fun updateChapter(chapter: ChapterEntity) { this.chapter = chapter }
        override fun updateNarrationBlock(block: NarrationBlockEntity) { }
        override fun updateGenerationRun(run: GenerationRunEntity) { this.run = run }
        override fun updateAudioSegment(segment: AudioSegmentEntity) { this.segment = segment }
        override fun findProjectWithRelations(projectId: String): BookProjectWithRelations? = null
        override fun findChapterWithRelations(chapterId: String): ChapterWithRelations? = null
        override fun findGenerationRunWithSegments(runId: String): GenerationRunWithSegments? = null
    }
}
