package com.homoludens.citacknjiga.document.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.document.DocumentBlock
import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import com.homoludens.citacknjiga.core.document.ImportProvenance
import com.homoludens.citacknjiga.core.document.PageLocator
import com.homoludens.citacknjiga.core.document.PdfImportLimits
import com.homoludens.citacknjiga.core.document.acceptsPageCount
import com.homoludens.citacknjiga.core.document.acceptsPageTextBytes
import com.homoludens.citacknjiga.core.document.acceptsRangeTextBytes
import com.homoludens.citacknjiga.core.document.acceptsSelectedPages
import com.homoludens.citacknjiga.core.document.acceptsSourceBytes
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** PdfBox adapter that accepts only the already staged private file. */
public class PdfBoxPdfPageImporter(
    private val defaultLimits: PdfImportLimits = PdfImportLimits.Production,
) : PdfPageImporter {
    override suspend fun pageCount(
        source: StagedPdfSource,
        controls: PdfInspectionControls,
    ): PdfPageCountResult {
        val limits = effectiveLimits(controls)
        val job = coroutineContext[Job]
        return try {
            checkSource(source, limits)
            guard(controls)
            PDDocument.load(source.sourceFile).use { document ->
                guard(controls)
                if (document.isEncrypted) return PdfPageCountResult.Failed(protectedDiagnostic())
                val count = document.numberOfPages
                if (count < 1) return PdfPageCountResult.Failed(unsupportedDiagnostic())
                if (!limits.acceptsPageCount(count)) return PdfPageCountResult.Failed(limitDiagnostic(
                    ImportDiagnosticCode.PAGE_COUNT_TOO_LARGE,
                    "The PDF contains too many pages.",
                    "Choose a PDF with at most ${limits.maxPages} pages.",
                ))
                PdfPageCountResult.Accepted(PdfPageCount(count))
            }
        } catch (failure: Throwable) {
            pageCountFailure(failure)
        }
    }

    override suspend fun inspect(
        source: StagedPdfSource,
        range: PageRange,
        controls: PdfInspectionControls,
    ): PdfInspectionResult {
        val limits = effectiveLimits(controls)
        val job = coroutineContext[Job]
        return try {
            checkSource(source, limits)
            guard(controls)
            PDDocument.load(source.sourceFile).use { document ->
                guard(controls)
                if (document.isEncrypted) return PdfInspectionResult.Failed(protectedDiagnostic())
                val count = document.numberOfPages
                if (count < 1) return PdfInspectionResult.Failed(unsupportedDiagnostic())
                if (!limits.acceptsPageCount(count)) return PdfInspectionResult.Failed(limitDiagnostic(
                    ImportDiagnosticCode.PAGE_COUNT_TOO_LARGE,
                    "The PDF contains too many pages.",
                    "Choose a PDF with at most ${limits.maxPages} pages.",
                ))
                if (range.endPage > count) return PdfInspectionResult.Failed(rangeDiagnostic())
                if (!limits.acceptsSelectedPages(range.pageCount)) return PdfInspectionResult.Failed(limitDiagnostic(
                    ImportDiagnosticCode.SELECTED_PAGES_TOO_MANY,
                    "The selected page range is too large.",
                    "Select at most ${limits.maxSelectedPages} pages.",
                ))

                val pages = ArrayList<PdfPage>(range.pageCount)
                var rangeBytes = 0L
                range.asList().forEach { pageNumber ->
                    guard(controls)
                    val page = document.getPage(pageNumber - 1)
                    val collector = PositionCollector { checkInline(controls, job) }
                    collector.startPage = pageNumber
                    collector.endPage = pageNumber
                    collector.getText(document)
                    guard(controls)
                    val locator = PageLocator(source.fingerprint, pageNumber)
                    val positions = collector.positions
                    val blocks = groupBlocks(positions, locator, controls, job)
                    val text = PdfTextNormalizer.normalize(blocks.joinToString("\n") { it.block.sourceText })
                    val pageBytes = text.toByteArray(StandardCharsets.UTF_8).size.toLong()
                    if (!limits.acceptsPageTextBytes(pageBytes)) return PdfInspectionResult.Failed(limitDiagnostic(
                        ImportDiagnosticCode.PAGE_TEXT_TOO_LARGE,
                        "This page exceeds the extracted text limit.",
                        "Select a smaller page or document.",
                        locator.toString(),
                    ))
                    rangeBytes += pageBytes
                    if (!limits.acceptsRangeTextBytes(rangeBytes)) return PdfInspectionResult.Failed(limitDiagnostic(
                        ImportDiagnosticCode.RANGE_TEXT_TOO_LARGE,
                        "The selected pages contain too much extracted text.",
                        "Select a smaller page range.",
                    ))
                    val externalResourceCount = runCatching { page.annotations.size }.getOrElse { 0 }
                    pages += PdfPage(
                        pageNumber = pageNumber,
                        text = text,
                        blocks = blocks,
                        locator = locator,
                        hasImageContent = page.hasImageContent(),
                        canDistinguishEmptyPage = true,
                        externalResourceCount = externalResourceCount,
                    )
                }
                PdfInspectionResult.Accepted(
                    PdfImportInspection(
                        pageCount = count,
                        range = range,
                        pages = pages,
                        warnings = emptyList(),
                        blockingDiagnostics = emptyList(),
                        provenance = ImportProvenance(
                            fingerprint = source.fingerprint,
                            sourceUri = source.sourceUri,
                            sourcePath = source.sourceFile.path,
                            projectId = source.projectId,
                        ),
                    ),
                )
            }
        } catch (failure: Throwable) {
            inspectionFailure(failure)
        }
    }

    private fun checkSource(source: StagedPdfSource, limits: PdfImportLimits) {
        val file = source.sourceFile
        require(file.isFile && file.canRead()) { "staged PDF is unreadable" }
        require(file.length() > 0L) { "staged PDF is empty" }
        if (file.length() != source.sizeBytes) throw SourceChangedException()
        if (!limits.acceptsSourceBytes(source.sizeBytes)) throw SourceTooLargeException()
    }

    private fun effectiveLimits(controls: PdfInspectionControls): PdfImportLimits =
        if (controls.limits == PdfImportLimits.Production) defaultLimits else controls.limits

    private suspend fun guard(controls: PdfInspectionControls) {
        controls.deadline.check()
    }

    private fun groupBlocks(
        positions: List<CollectedPosition>,
        locator: PageLocator,
        controls: PdfInspectionControls,
        job: Job?,
    ): List<PdfTextBlock> {
        val lines = mutableListOf<MutableList<CollectedPosition>>()
        positions.sortedWith(compareBy<CollectedPosition> { it.bounds.top }.thenBy { it.bounds.left })
            .forEach { position ->
                checkInline(controls, job)
                val line = lines.firstOrNull { candidate ->
                    val bounds = candidateBounds(candidate)
                    val verticalDistance = kotlin.math.abs(position.bounds.top - bounds.top)
                    val verticalLimit = maxOf(position.bounds.bottom - position.bounds.top, bounds.bottom - bounds.top) * 0.75f
                    val horizontalGap = when {
                        position.bounds.left >= bounds.right -> position.bounds.left - bounds.right
                        bounds.left >= position.bounds.right -> bounds.left - position.bounds.right
                        else -> 0f
                    }
                    verticalDistance <= maxOf(0.012f, verticalLimit) && horizontalGap <= 0.08f
                }
                if (line == null) lines += mutableListOf(position) else line += position
            }
        val orderedLines = lines.map { it.sortedBy { position -> position.bounds.left } }
            .sortedWith(compareBy<List<CollectedPosition>> { candidateBounds(it).top }.thenBy { candidateBounds(it).left })
        val groups = mutableListOf<MutableList<List<CollectedPosition>>>()
        orderedLines.forEach { line ->
            checkInline(controls, job)
            val current = groups.lastOrNull()
            if (current == null || !sameTextColumn(current.last(), line)) {
                groups += mutableListOf(line)
            } else {
                current += line
            }
        }
        return groups.mapIndexedNotNull { ordinal, group ->
            checkInline(controls, job)
            val bounds = group.flatMap { it }.map { it.bounds }.reduceOrNull(::union) ?: return@mapIndexedNotNull null
            val text = PdfTextNormalizer.normalize(group.joinToString("\n") { line -> lineText(line) })
            if (text.isEmpty()) return@mapIndexedNotNull null
            PdfTextBlock(
                block = DocumentBlock(ordinal, NarrationBlockType.PARAGRAPH, text, locator.block(ordinal)),
                bounds = bounds,
            )
        }
    }

    private fun lineText(line: List<CollectedPosition>): String = buildString {
        line.forEachIndexed { index, position ->
            if (index > 0) {
                val previous = line[index - 1]
                val gap = position.bounds.left - previous.bounds.right
                if (gap > maxOf(0.008f, previous.bounds.width * 0.5f) && lastOrNull()?.isWhitespace() == false) append(' ')
            }
            append(position.unicode)
        }
    }

    private fun sameTextColumn(first: List<CollectedPosition>, second: List<CollectedPosition>): Boolean {
        val a = candidateBounds(first)
        val b = candidateBounds(second)
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        return overlap > 0f || kotlin.math.abs(a.left - b.left) < 0.12f
    }

    private fun candidateBounds(positions: List<CollectedPosition>): NormalizedRect =
        positions.map { it.bounds }.reduce(::union)

    private fun union(first: NormalizedRect, second: NormalizedRect): NormalizedRect = NormalizedRect(
        left = minOf(first.left, second.left),
        top = minOf(first.top, second.top),
        right = maxOf(first.right, second.right),
        bottom = maxOf(first.bottom, second.bottom),
    )

    private fun checkInline(controls: PdfInspectionControls, job: Job?) {
        controls.deadline.checkNow()
        if (job?.isActive == false) throw CancellationException("PDF inspection cancelled")
    }

    private fun pageCountFailure(failure: Throwable): PdfPageCountResult {
        if (failure is CancellationException || failure is PdfInspectionTimeoutException) throw failure
        return PdfPageCountResult.Failed(failure.toDiagnostic())
    }

    private fun inspectionFailure(failure: Throwable): PdfInspectionResult {
        if (failure is CancellationException || failure is PdfInspectionTimeoutException) throw failure
        return PdfInspectionResult.Failed(failure.toDiagnostic())
    }

    private fun Throwable.toDiagnostic(): ImportDiagnostic = when (this) {
        is InvalidPasswordException -> protectedDiagnostic()
        is SecurityException -> protectedDiagnostic()
        is SourceChangedException -> ImportDiagnostic(
            ImportDiagnosticCode.SOURCE_CHANGED,
            message = "The staged PDF changed before inspection.",
            action = "Select the PDF again.",
        )
        is SourceTooLargeException -> limitDiagnostic(
            ImportDiagnosticCode.SOURCE_TOO_LARGE,
            "The staged PDF exceeds the import size limit.",
            "Choose a smaller PDF.",
        )
        is IllegalArgumentException -> unsupportedDiagnostic()
        is IOException -> malformedDiagnostic()
        else -> malformedDiagnostic()
    }

    private fun protectedDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.PROTECTED_PDF,
        message = "The PDF is encrypted or password protected.",
        action = "Choose an unprotected PDF.",
    )

    private fun malformedDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.MALFORMED_PDF,
        message = "The PDF is malformed or truncated and could not be read safely.",
        action = "Choose a readable, unprotected PDF.",
    )

    private fun unsupportedDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.UNSUPPORTED_PDF,
        message = "The PDF format is not supported by the local parser.",
        action = "Choose a born-digital PDF with readable pages.",
    )

    private fun rangeDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.PAGE_RANGE_INVALID,
        message = "The selected page range is invalid.",
        action = "Choose an inclusive range within the PDF page count.",
    )

    private fun limitDiagnostic(
        code: ImportDiagnosticCode,
        message: String,
        action: String,
        locator: String? = null,
    ) = ImportDiagnostic(code, locator, message, action)

    private data class CollectedPosition(val unicode: String, val bounds: NormalizedRect)

    private class SourceChangedException : IOException()
    private class SourceTooLargeException : IOException()

    private class PositionCollector(private val guard: () -> Unit) : PDFTextStripper() {
        val positions = mutableListOf<CollectedPosition>()

        init {
            sortByPosition = false
        }

        override fun processTextPosition(text: TextPosition) {
            guard()
            if (text.unicode.isEmpty()) return
            val pageWidth = text.pageWidth.coerceAtLeast(1f)
            val pageHeight = text.pageHeight.coerceAtLeast(1f)
            // PdfBox's adjusted coordinates already apply crop origin and page rotation.
            val left = (text.xDirAdj / pageWidth).coerceIn(0f, 1f)
            val top = (text.yDirAdj / pageHeight).coerceIn(0f, 1f)
            val right = ((text.xDirAdj + text.widthDirAdj.coerceAtLeast(0f)) / pageWidth).coerceIn(left, 1f)
            val bottom = ((text.yDirAdj + text.heightDir.coerceAtLeast(0f)) / pageHeight).coerceIn(top, 1f)
            positions += CollectedPosition(text.unicode, NormalizedRect(left, top, right, bottom))
        }
    }

    private fun PDPage.hasImageContent(): Boolean {
        val resources: PDResources = resources ?: return false
        return resources.xObjectNames.any { name -> resources.getXObject(name) is PDImageXObject }
    }
}
