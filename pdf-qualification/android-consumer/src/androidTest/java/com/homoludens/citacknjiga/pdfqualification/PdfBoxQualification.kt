package com.homoludens.citacknjiga.pdfqualification

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException

internal object PdfBoxQualification {
    fun initialize(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun inspect(
        file: File,
        firstPage: Int = 1,
        lastPage: Int? = null,
        limits: Limits = Limits(),
        guard: () -> Unit = {},
    ): Inspection {
        PDDocument.load(file).use { document ->
            guard()
            if (document.isEncrypted) throw ProtectedPdf()
            val count = document.numberOfPages
            if (count < 1) throw UnsupportedPdf()
            if (count > limits.maxPages) throw LimitExceeded()
            require(firstPage in 1..count)
            val end = lastPage ?: count
            require(end in firstPage..count)
            if (end - firstPage + 1 > limits.maxSelectedPages) throw LimitExceeded()
            val collector = PositionCollector(guard)
            collector.startPage = firstPage
            collector.endPage = end
            val extracted = collector.getText(document)
            guard()
            val pages = (firstPage..end).map { pageNumber ->
                val page = document.getPage(pageNumber - 1)
                val positions = collector.positions.filter { it.pageNumber == pageNumber }
                PageEvidence(
                    pageNumber = pageNumber,
                    text = positions.joinToString("") { it.unicode },
                    positions = positions,
                    hasImageContent = page.hasImageContent(),
                    externalResourceCount = page.annotations.size,
                )
            }
            val rangeBytes = pages.sumOf { it.text.toByteArray(StandardCharsets.UTF_8).size.toLong() }
            if (pages.any { it.text.toByteArray(StandardCharsets.UTF_8).size > limits.maxPageTextBytes } ||
                rangeBytes > limits.maxRangeTextBytes
            ) throw LimitExceeded()
            return Inspection(
                pageCount = count,
                selectedPages = pages,
                plainText = extracted,
                normalizedTextBytes = pages.sumOf { it.text.toByteArray(StandardCharsets.UTF_8).size.toLong() },
            )
        }
    }

    fun classifyFailure(file: File, guard: () -> Unit = {}): FailureKind = try {
        inspect(file, guard = guard)
        FailureKind.ACCEPTED
    } catch (_: InvalidPasswordException) {
        FailureKind.PROTECTED
    } catch (_: ProtectedPdf) {
        FailureKind.PROTECTED
    } catch (_: UnsupportedPdf) {
        FailureKind.UNSUPPORTED
    } catch (_: IllegalStateException) {
        FailureKind.UNSUPPORTED
    } catch (_: IOException) {
        FailureKind.MALFORMED
    }

    data class Limits(
        val maxPages: Int = 10_000,
        val maxSelectedPages: Int = 200,
        val maxPageTextBytes: Int = 1 shl 20,
        val maxRangeTextBytes: Long = 32L shl 20,
    )

    data class Inspection(
        val pageCount: Int,
        val selectedPages: List<PageEvidence>,
        val plainText: String,
        val normalizedTextBytes: Long,
    )

    data class PageEvidence(
        val pageNumber: Int,
        val text: String,
        val positions: List<PositionEvidence>,
        val hasImageContent: Boolean,
        val externalResourceCount: Int,
    )

    data class PositionEvidence(
        val pageNumber: Int,
        val unicode: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    enum class FailureKind { ACCEPTED, PROTECTED, MALFORMED, UNSUPPORTED }

    class ProtectedPdf : IOException("protected PDF")
    class UnsupportedPdf : IOException("unsupported PDF")
    class LimitExceeded : IOException("qualification limit exceeded")

    private class PositionCollector(private val guard: () -> Unit) : PDFTextStripper() {
        val positions = mutableListOf<PositionEvidence>()

        override fun processTextPosition(text: TextPosition) {
            guard()
            val pageWidth = text.pageWidth.coerceAtLeast(1f)
            val pageHeight = text.pageHeight.coerceAtLeast(1f)
            val left = (text.xDirAdj / pageWidth).coerceIn(0f, 1f)
            val top = (text.yDirAdj / pageHeight).coerceIn(0f, 1f)
            val right = ((text.xDirAdj + text.widthDirAdj) / pageWidth).coerceIn(left, 1f)
            val bottom = ((text.yDirAdj + text.heightDir) / pageHeight).coerceIn(top, 1f)
            positions += PositionEvidence(
                pageNumber = currentPageNo,
                unicode = text.unicode,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }
    }

    private fun PDPage.hasImageContent(): Boolean {
        val resources: PDResources = resources ?: return false
        return resources.xObjectNames.any { name -> resources.getXObject(name) is PDImageXObject }
    }
}
