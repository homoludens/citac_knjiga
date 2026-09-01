package com.homoludens.citacknjiga.pdfqualification

import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class PdfBoxAndroidQualificationTest {
    private val root = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        .resolve("pdfbox-qualification")

    @Before
    public fun initializePdfBox() {
        PdfBoxQualification.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test
    public fun corpusLoadsPageCountsAndBasicExtraction() {
        val latin = fixture("latin-unicode")
        val result = PdfBoxQualification.inspect(latin)
        assertEquals(1, result.pageCount)
        assertTrue(result.selectedPages.single().positions.isNotEmpty())
        assertTrue(result.selectedPages.single().text.contains("Читанка"))
    }

    @Test
    public fun selectedRangeAndPositionGeometryAreStructured() {
        val file = fixture("soft-wrapping", pageCount = 3)
        val result = PdfBoxQualification.inspect(file, firstPage = 2, lastPage = 3)
        assertEquals(listOf(2, 3), result.selectedPages.map { it.pageNumber })
        assertTrue(result.selectedPages.all { page -> page.positions.all { it.left in 0f..1f && it.right in 0f..1f && it.top in 0f..1f && it.bottom in 0f..1f } })
        val columns = PdfBoxQualification.inspect(fixture("separated-columns")).selectedPages.single().positions
        assertTrue(columns.maxOf { it.left } - columns.minOf { it.left } > 0.2f)
        val overlap = PdfBoxQualification.inspect(fixture("overlapping-columns")).selectedPages.single().positions
        assertTrue(overlap.size >= 2)
    }

    @Test
    public fun unicodeAndCyrillicAreNotTransliterated() {
        assertTrue(PdfBoxQualification.inspect(fixture("latin-unicode")).selectedPages.single().text.contains("č ć š ž đ"))
        assertTrue(PdfBoxQualification.inspect(fixture("cyrillic-unicode")).selectedPages.single().text.contains("Српски текст"))
    }

    @Test
    public fun failuresAreClosedAndExternalReferenceIsNotResolved() {
        assertEquals(PdfBoxQualification.FailureKind.PROTECTED, classify("protected"))
        assertEquals(PdfBoxQualification.FailureKind.MALFORMED, classify("malformed-truncated"))
        assertEquals(PdfBoxQualification.FailureKind.UNSUPPORTED, classify("unsupported-encoding"))
        val image = PdfBoxQualification.inspect(fixture("image-only-page")).selectedPages.single()
        assertTrue(image.hasImageContent)
        assertTrue(image.positions.isEmpty())
        val external = PdfBoxQualification.inspect(fixture("external-reference")).selectedPages.single()
        assertEquals(1, external.externalResourceCount)
        assertFalse(root.resolve("sentinel").exists())
    }

    @Test
    public fun cancellationDeadlineAndTextLimitsStopBeforePublication() {
        val checks = AtomicInteger()
        val cancellation = runCatching {
            PdfBoxQualification.inspect(fixture("soft-wrapping")) {
                if (checks.incrementAndGet() > 2) throw CancellationException("qualification cancelled")
            }
        }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertTrue(checks.get() > 0)

        val deadline = runCatching {
            PdfBoxQualification.inspect(fixture("soft-wrapping")) { throw DeadlineExceeded() }
        }.exceptionOrNull()
        assertTrue(deadline is DeadlineExceeded)

        assertTrue(runCatching {
            PdfBoxQualification.inspect(
                fixture("soft-wrapping", pageCount = 3),
                firstPage = 1,
                lastPage = 2,
                limits = PdfBoxQualification.Limits(maxSelectedPages = 1),
            )
        }.exceptionOrNull() is PdfBoxQualification.LimitExceeded)

        val measured = measure(fixture("latin-unicode"))
        assertTrue(measured.elapsedMillis >= 0)
        assertTrue(measured.peakPssKb > 0)
    }

    @Test
    public fun writesRedactedEvidenceReport() {
        val report = root.resolve(
            "qualification-report-${android.os.Build.VERSION.SDK_INT}-${android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}.json",
        )
        root.mkdirs()
        val measurement = measure(fixture("latin-unicode"))
        report.writeText(
            """{"schema":"citac-knjiga-pdfbox-android-qualification-v1","candidate":"com.tom-roush:pdfbox-android:2.0.27.0","status":"executed","production_pdf_enabled":false,"fixture_count":${PdfFixtureFactory.names().size},"api_level":${android.os.Build.VERSION.SDK_INT},"abi":"${android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}","apk_size_bytes":${ApplicationProvider.getApplicationContext<android.content.Context>().applicationInfo.sourceDir.let { File(it).length() }},"inspection_elapsed_ms":${measurement.elapsedMillis},"peak_pss_kb":${measurement.peakPssKb},"external_resources_opened":false,"ocr_claimed":false,"limits_checked":true,"cancellation_checked":true,"deadline_checked":true}""",
        )
        assertTrue(report.isFile)
    }

    private fun classify(name: String): PdfBoxQualification.FailureKind =
        PdfBoxQualification.classifyFailure(fixture(name))

    private fun fixture(name: String, pageCount: Int = 1): File {
        root.mkdirs()
        val file = root.resolve("$name-$pageCount-v2.pdf")
        if (!file.exists()) {
            if (name == "protected") createProtectedFixture(file) else file.writeBytes(PdfFixtureFactory.bytes(name, pageCount))
        }
        return file
    }

    private fun createProtectedFixture(file: File) {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val policy = StandardProtectionPolicy("owner-password", "user-password", AccessPermission())
            policy.encryptionKeyLength = 40
            document.protect(policy)
            document.save(file)
        }
    }

    private fun measure(file: File): Measurement {
        val start = SystemClock.elapsedRealtime()
        val before = Debug.getPss()
        PdfBoxQualification.inspect(file)
        val after = Debug.getPss()
        return Measurement(SystemClock.elapsedRealtime() - start, maxOf(before, after))
    }

    private data class Measurement(val elapsedMillis: Long, val peakPssKb: Long)
    private class DeadlineExceeded : RuntimeException()
}
