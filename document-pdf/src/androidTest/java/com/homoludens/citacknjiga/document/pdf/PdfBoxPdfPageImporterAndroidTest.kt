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
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun createPdf(): File {
        val file = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("source.pdf")
        PDDocument().use { document ->
            document.addPage(textPage(document, "Prva strana", 0))
            document.addPage(textPage(document, "Druga strana", 90).also { page ->
                val link = PDAnnotationLink().apply { action = PDActionURI().apply { uri = "file:///outside.pdf" } }
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

    public companion object {
        @JvmStatic
        @BeforeClass
        public fun initializePdfBox() {
            PdfBoxResourceLoaderInitializer.initialize(ApplicationProvider.getApplicationContext())
        }
    }
}
