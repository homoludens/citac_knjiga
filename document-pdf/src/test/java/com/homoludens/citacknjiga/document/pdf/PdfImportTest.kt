package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.document.DocumentBlock
import com.homoludens.citacknjiga.core.document.ImportProvenance
import com.homoludens.citacknjiga.core.document.PageLocator
import com.homoludens.citacknjiga.core.document.PdfImportLimits
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class PdfImportTest {
    private val fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    public fun rangeIsOneBasedInclusiveAndRejectsInvalidForms() {
        assertEquals(listOf(3, 4, 5), PageRange.validate(3, 5, 20).asList())
        listOf(
            { PageRange.validate(0, 2, 20) },
            { PageRange.validate(6, 4, 20) },
            { PageRange.validate(1, 21, 20) },
            { PageRange.validate(1, 201, 201) },
        ).forEach { assertion -> assertTrue(runCatching(assertion).isFailure) }
    }

    @Test
    public fun normalizationPreservesBreaksAndDiscretionaryHyphen() {
        assertEquals("Prvi red\nDrugi\u00adred", PdfTextNormalizer.normalize("  Prvi   red\r\n Drugi\u00adred  "))
    }

    @Test
    public fun separatedColumnsAreOrderedAndOverlappingBlocksAreUnreliable() {
        val page = page(
            blocks = listOf(
                block("desno", 0.62f, 0.1f, 0.95f, 0.2f),
                block("levo", 0.05f, 0.2f, 0.38f, 0.3f),
            ),
        )
        val separated = PdfPageInspector.inspect(page)
        assertEquals(listOf("levo", "desno"), separated.page.blocks.map { it.block.sourceText })
        assertEquals("levo\ndesno", separated.page.text)
        assertTrue(separated.warnings.any { it.code.name == "MULTI_COLUMN" })

        val overlapping = PdfPageInspector.inspect(page.copy(blocks = listOf(
            block("a", 0.1f, 0.1f, 0.7f, 0.5f),
            block("b", 0.5f, 0.2f, 0.9f, 0.6f),
        )))
        assertTrue(overlapping.diagnostics.any { it.code.name == "UNRELIABLE_LAYOUT" })
    }

    @Test
    public fun unavailableQualificationAdapterFailsClosedAndDiscardsStaging() = runBlocking {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val index = FakeIndex()
        val repository = SafPdfSourceRepository(
            sourceReader = PdfSourceReader { "%PDF-1.4\nnot-a-parser-input".byteInputStream() },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = index,
            projectIdFactory = { "blocked" },
        )
        val result = PdfImportPreviewService(repository).previewSource("content://books/a", 1, 1)
        assertEquals("PDF_FEATURE_UNAVAILABLE", (result as PdfPreviewResult.Failed).diagnostic.code.name)
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
        assertTrue(index.sources.isEmpty())
    }

    @Test
    public fun deadlineFailureIsReportedSeparatelyAndDiscardsStaging() = runBlocking {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val repository = SafPdfSourceRepository(
            sourceReader = PdfSourceReader { "%PDF-1.4\nvalid-header".byteInputStream() },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = FakeIndex(),
            projectIdFactory = { "timeout" },
        )
        val importer = object : PdfPageImporter {
            override suspend fun pageCount(source: StagedPdfSource, controls: PdfInspectionControls) =
                PdfPageCountResult.Accepted(PdfPageCount(1))

            override suspend fun inspect(
                source: StagedPdfSource,
                range: PageRange,
                controls: PdfInspectionControls,
            ): PdfInspectionResult = throw PdfInspectionTimeoutException()
        }

        val result = PdfImportPreviewService(repository, importer).previewSource("content://books/a", 1, 1)
        assertEquals("INSPECTION_TIMEOUT", (result as PdfPreviewResult.Failed).diagnostic.code.name)
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
    }

    @Test
    public fun stagingStopsAtConfiguredLimitAndDeletesPartialFile() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val repository = SafPdfSourceRepository(
            sourceReader = PdfSourceReader { object : InputStream() {
                private var left = 6
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (left == 0) return -1
                    buffer[offset] = 'x'.code.toByte()
                    left--
                    return 1
                }
                override fun read(): Int = if (left-- > 0) 'x'.code else -1
            } },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = FakeIndex(),
            limits = PdfImportLimits(maxSourceBytes = 5, maxPages = 10, maxSelectedPages = 2, maxPageTextBytes = 10, maxRangeTextBytes = 20, maxProcessingNanos = 1_000_000),
            projectIdFactory = { "too-large" },
        )
        assertEquals(PdfStageError.SOURCE_TOO_LARGE, (repository.stageSource("content://books/a") as PdfStageResult.Failed).error)
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
    }

    @Test
    public fun nonPdfSelectionIsRejectedBeforeParserStaging() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val repository = SafPdfSourceRepository(
            sourceReader = PdfSourceReader { "plain text".byteInputStream() },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = FakeIndex(),
            projectIdFactory = { "not-pdf" },
        )
        assertEquals(PdfStageError.INVALID_FORMAT, (repository.stageSource("content://books/a") as PdfStageResult.Failed).error)
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
    }

    private fun page(blocks: List<PdfTextBlock>) = PdfPage(
        3,
        blocks.joinToString("\n") { it.block.sourceText },
        blocks,
        PageLocator(fingerprint, 3),
    )

    private fun block(text: String, left: Float, top: Float, right: Float, bottom: Float) = PdfTextBlock(
        DocumentBlock(0, NarrationBlockType.PARAGRAPH, text, "source"),
        NormalizedRect(left, top, right, bottom),
    )

    private class FakeIndex : PdfProjectIndex {
        val sources = mutableListOf<ExistingPdfProject>()
        override fun findByFingerprint(fingerprint: String): ExistingPdfProject? = sources.firstOrNull()
        override fun recordAcceptedDocument(source: ImportedPdfSource, document: com.homoludens.citacknjiga.core.document.DocumentIr, canonicalChapterPaths: Map<String, String>) = Unit
    }
}
