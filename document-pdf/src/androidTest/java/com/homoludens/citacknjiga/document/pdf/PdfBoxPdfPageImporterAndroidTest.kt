package com.homoludens.citacknjiga.document.pdf

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

public class PdfBoxPdfPageImporterAndroidTest {
    @Test
    public fun selectedRangePreservesTextOrderGeometryAndExternalIsolation() = runBlocking {
        val file = createPdf()
        val source = staged(file)
        val result = PdfBoxPdfPageImporter().inspect(source, PageRange(2, 2), controls())
        val inspection = (result as PdfInspectionResult.Accepted).inspection
        val page = inspection.pages.single()
        assertEquals(listOf(2), inspection.pages.map { it.pageNumber })
        assertEquals("Druga strana", page.text)
        assertEquals(source.fingerprint, inspection.provenance.fingerprint)
        assertEquals("content://provider/pdf", inspection.provenance.sourceUri)
        assertTrue(page.locator.toString().endsWith("/page/2"))
        assertEquals(1, page.externalResourceCount)
        assertTrue(page.blocks.single().bounds.left in 0f..1f)
        assertTrue(page.blocks.single().bounds.top in 0f..1f)
        assertTrue(page.blocks.single().bounds.right in 0f..1f)
        assertTrue(page.blocks.single().bounds.bottom in 0f..1f)
        assertTrue(PdfPageInspector.inspect(page).warnings.any { it.code.name == "EXTERNAL_RESOURCE" })
    }

    @Test
    public fun stagedPdfRemainsReadableAfterSourceProviderDisappears() = runBlocking {
        val root = uniqueRoot("provider-disappears")
        val input = root.resolve("input.pdf")
        val outside = root.resolve("outside.txt").apply { writeText("unchanged") }
        createPdf(input, outside)
        val bytes = input.readBytes()
        var providerAvailable = true
        var opens = 0
        val storage = AppPrivateStorage(root)
        val repository = SafPdfSourceRepository(
            sourceReader = PdfSourceReader {
                opens++
                if (providerAvailable) bytes.inputStream() else null
            },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = EmptyIndex(),
            projectIdFactory = { "provider-disappears" },
        )
        val source = (repository.stageSource("content://provider/pdf") as PdfStageResult.Staged).source
        try {
            providerAvailable = false
            val result = PdfBoxPdfPageImporter().inspect(source, PageRange(1, 2), controls())
            val inspection = (result as PdfInspectionResult.Accepted).inspection

            assertEquals(1, opens)
            assertEquals(listOf("Prva strana", "Druga strana"), inspection.pages.map { it.text })
            assertTrue(source.sourceFile.canonicalPath.startsWith(root.canonicalPath + File.separator))
            assertEquals("unchanged", outside.readText())
        } finally {
            repository.discardStaged(source)
            root.deleteRecursively()
        }
    }

    @Test
    public fun canceledPreviewDeletesStagedParserInput() = runBlocking {
        val root = uniqueRoot("cancellation")
        val input = createPdf(root.resolve("input.pdf"))
        val bytes = input.readBytes()
        val enteredParser = CompletableDeferred<Unit>()
        val storage = AppPrivateStorage(root)
        val repository = SafPdfSourceRepository(
            sourceReader = PdfSourceReader { bytes.inputStream() },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = EmptyIndex(),
            projectIdFactory = { "cancellation" },
        )
        val realImporter = PdfBoxPdfPageImporter()
        val importer = object : PdfPageImporter {
            override suspend fun pageCount(source: StagedPdfSource, controls: PdfInspectionControls) =
                realImporter.pageCount(source, controls)

            override suspend fun inspect(
                source: StagedPdfSource,
                range: PageRange,
                controls: PdfInspectionControls,
            ): PdfInspectionResult {
                enteredParser.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            val job = launch {
                PdfImportPreviewService(repository, importer).previewSource("content://provider/pdf", 1, 1)
            }
            enteredParser.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    public fun malformedAndProtectedPreviewsPublishNoTextOrPrivateState() = runBlocking {
        val root = uniqueRoot("closed-failures")
        val malformed = "%PDF-1.7\n1 0 obj<<".toByteArray()
        val protectedFile = root.resolve("protected-input.pdf")
        createProtectedPdf(protectedFile)
        try {
            listOf(
                malformed to ImportDiagnosticCode.MALFORMED_PDF,
                protectedFile.readBytes() to ImportDiagnosticCode.PROTECTED_PDF,
            ).forEachIndexed { index, (bytes, expected) ->
                val storage = AppPrivateStorage(root)
                val repository = SafPdfSourceRepository(
                    sourceReader = PdfSourceReader { bytes.inputStream() },
                    storage = storage,
                    artifactStore = AtomicArtifactStore(storage),
                    projectIndex = EmptyIndex(),
                    projectIdFactory = { "closed-failure-$index" },
                )
                val result = PdfImportPreviewService(repository, PdfBoxPdfPageImporter())
                    .previewSource("content://provider/pdf", 1, 1)

                assertEquals(expected, (result as PdfPreviewResult.Failed).diagnostic.code)
                assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    public fun protectedPdfFailsBeforeAnyTextIsReturned() = runBlocking {
        val file = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("protected.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.LETTER))
            document.protect(StandardProtectionPolicy("owner", "user", AccessPermission()))
            document.save(file)
        }
        val result = PdfBoxPdfPageImporter().pageCount(staged(file), controls())
        assertEquals(ImportDiagnosticCode.PROTECTED_PDF, (result as PdfPageCountResult.Failed).diagnostic.code)
    }

    @Test
    public fun serbianLatinCyrillicAndUnicodeArePreserved() = runBlocking {
        val file = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("unicode.pdf")
        PDDocument().use { document ->
            val font = PDType0Font.load(document, File("/system/fonts/NotoSerif-Regular.ttf"))
            document.addPage(textPage(document, "Čačak Ћирилица Ω", 0, font))
            document.save(file)
        }
        val result = PdfBoxPdfPageImporter().inspect(staged(file), PageRange(1, 1), controls())
        assertEquals("Čačak Ћирилица Ω", (result as PdfInspectionResult.Accepted).inspection.pages.single().text)
    }

    @Test
    public fun imageOnlyPageReportsOcrUnsupported() = runBlocking {
        val file = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("image-only.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xff336699.toInt()) }
            JPEGFactory.createFromImage(document, bitmap).also { image ->
                PDPageContentStream(document, page).use { stream -> stream.drawImage(image, 0f, 0f, 100f, 100f) }
            }
            bitmap.recycle()
            document.save(file)
        }
        val result = PdfBoxPdfPageImporter().inspect(staged(file), PageRange(1, 1), controls())
        val page = (result as PdfInspectionResult.Accepted).inspection.pages.single()
        assertEquals(ImportDiagnosticCode.OCR_UNSUPPORTED, PdfPageInspector.inspect(page).diagnostics.single().code)
    }

    @Test
    public fun malformedAndTruncatedFilesFailClosed() = runBlocking {
        val file = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("broken.pdf")
        file.writeBytes("%PDF-1.7\n1 0 obj<<".toByteArray())
        val result = PdfBoxPdfPageImporter().pageCount(staged(file), controls())
        assertEquals(ImportDiagnosticCode.MALFORMED_PDF, (result as PdfPageCountResult.Failed).diagnostic.code)
    }

    @Test
    public fun emptyPdfIsUnsupported() = runBlocking {
        val file = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("empty.pdf")
        PDDocument().use { it.save(file) }
        val result = PdfBoxPdfPageImporter().pageCount(staged(file), controls())
        assertEquals(ImportDiagnosticCode.UNSUPPORTED_PDF, (result as PdfPageCountResult.Failed).diagnostic.code)
    }

    private fun controls(): PdfInspectionControls = PdfInspectionControls(PdfDeadline.start())

    private fun staged(file: File): StagedPdfSource {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return StagedPdfSource("android-test", "content://provider/pdf", digest, file, file.length())
    }

    private fun createPdf(
        file: File = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("source.pdf"),
        externalTarget: File? = null,
    ): File {
        file.parentFile?.mkdirs()
        PDDocument().use { document ->
            document.addPage(textPage(document, "Prva strana", 0))
            document.addPage(textPage(document, "Druga strana", 90).also { page ->
                val link = PDAnnotationLink().apply {
                    action = PDActionURI().apply { uri = externalTarget?.toURI()?.toString() ?: "file:///outside.pdf" }
                }
                page.annotations = listOf(link)
            })
            document.save(file)
        }
        return file
    }

    private fun textPage(document: PDDocument, text: String, rotation: Int, font: PDFont = PDType1Font.HELVETICA): PDPage =
        PDPage(PDRectangle(10f, 20f, 300f, 400f)).also { page ->
            page.rotation = rotation
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(font, 12f)
                stream.newLineAtOffset(30f, 350f)
                stream.showText(text)
                stream.endText()
            }
        }

    private fun createProtectedPdf(file: File) {
        file.parentFile?.mkdirs()
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.LETTER))
            document.protect(StandardProtectionPolicy("owner", "user", AccessPermission()))
            document.save(file)
        }
    }

    private fun uniqueRoot(name: String): File = ApplicationProvider.getApplicationContext<Context>().cacheDir
        .resolve("pdf-$name-${UUID.randomUUID()}").apply { mkdirs() }

    private class EmptyIndex : PdfProjectIndex {
        override fun findByFingerprint(fingerprint: String): ExistingPdfProject? = null

        override fun recordAcceptedDocument(
            source: ImportedPdfSource,
            document: com.homoludens.citacknjiga.core.document.DocumentIr,
            canonicalChapterPaths: Map<String, String>,
        ) = Unit
    }

    public companion object {
        @JvmStatic
        @BeforeClass
        public fun initializePdfBox() {
            PdfBoxResourceLoaderInitializer.initialize(ApplicationProvider.getApplicationContext())
        }
    }
}
