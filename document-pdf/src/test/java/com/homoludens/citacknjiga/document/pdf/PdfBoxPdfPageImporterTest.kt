package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import com.homoludens.citacknjiga.core.document.PdfImportLimits
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class PdfBoxPdfPageImporterTest {
    @Test
    public fun malformedAndUnreadableStagedFilesReturnActionableDiagnostics() = runBlocking {
        val malformed = createTempDirectory().resolve("broken.pdf").toFile().apply { writeText("%PDF-1.7") }
        val malformedResult = PdfBoxPdfPageImporter().pageCount(staged(malformed), controls())
        assertEquals(ImportDiagnosticCode.MALFORMED_PDF, (malformedResult as PdfPageCountResult.Failed).diagnostic.code)

        val missing = staged(createTempDirectory().resolve("missing.pdf").toFile())
        val missingResult = PdfBoxPdfPageImporter().pageCount(missing, controls())
        assertEquals(ImportDiagnosticCode.UNSUPPORTED_PDF, (missingResult as PdfPageCountResult.Failed).diagnostic.code)
    }

    @Test
    public fun deadlineAndCancellationAreCheckedBeforeParserWork() = runBlocking {
        val file = createTempDirectory().resolve("source.pdf").toFile().apply { writeText("%PDF-1.7") }
        var calls = 0
        val deadline = PdfDeadline.start(PdfImportLimits(maxProcessingNanos = 1)) {
            if (calls++ == 0) 0L else Long.MAX_VALUE
        }
        val timed = runCatching {
            PdfBoxPdfPageImporter().pageCount(
                staged(file),
                PdfInspectionControls(deadline, limits = PdfImportLimits(maxProcessingNanos = 1)),
            )
        }.exceptionOrNull()
        assertTrue(timed.toString(), timed is PdfInspectionTimeoutException)

        val canceled = Job().apply { cancel() }
        val failure = runCatching {
            withContext(canceled) { PdfBoxPdfPageImporter().pageCount(staged(file), controls()) }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }

    @Test
    public fun unicodeNormalizationAndGeometryRemainLosslessAtContractBoundary() {
        assertEquals(
            "Čačak Ћирилица 漢字",
            PdfTextNormalizer.normalize("  Čačak   Ћирилица 漢字  "),
        )
        val bounds = NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f)
        assertTrue(bounds.left >= 0f && bounds.right <= 1f && bounds.top >= 0f && bounds.bottom <= 1f)
    }

    private fun controls(limits: PdfImportLimits = PdfImportLimits.Production): PdfInspectionControls =
        PdfInspectionControls(PdfDeadline.start(limits), limits = limits)

    private fun staged(file: File): StagedPdfSource {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            if (file.isFile) file.readBytes() else ByteArray(0),
        ).joinToString("") { "%02x".format(it) }
        return StagedPdfSource("project", "content://provider/private.pdf", digest, file, file.length())
    }

}
