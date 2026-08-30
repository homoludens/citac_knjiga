package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.document.DocumentBlock
import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import com.homoludens.citacknjiga.core.document.ImportProvenance
import com.homoludens.citacknjiga.core.document.ImportWarning
import com.homoludens.citacknjiga.core.document.ImportWarningCode
import com.homoludens.citacknjiga.core.document.PdfImportLimits
import com.homoludens.citacknjiga.core.document.acceptsPageCount
import com.homoludens.citacknjiga.core.document.acceptsPageTextBytes
import com.homoludens.citacknjiga.core.document.acceptsRangeTextBytes
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

public object PdfTextNormalizer {
    public fun normalize(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.replace(Regex("[ \\t]+"), " ").trim() }
        .joinToString("\n")
        .trim()
}

public data class LayoutOrder(
    public val blocks: List<PdfTextBlock>,
    public val multiColumn: Boolean,
    public val unreliable: Boolean,
)

/** Versioned, conservative geometry ordering. It never uses parser input order as geometry. */
public object PdfLayoutProfile {
    public const val VERSION: String = "pdf-layout-v1"
    private const val GUTTER_RATIO = 0.08f

    public fun order(blocks: List<PdfTextBlock>): LayoutOrder {
        if (blocks.size < 2) return LayoutOrder(blocks, multiColumn = false, unreliable = false)
        val gaps = blocks.flatMap { block ->
            blocks.mapNotNull { other ->
                if (other.bounds.left > block.bounds.right) {
                    block.bounds.right to other.bounds.left
                } else null
            }
        }.filter { (left, right) -> right - left >= GUTTER_RATIO }
            .filter { (left, right) -> blocks.any { it.bounds.right <= left } && blocks.any { it.bounds.left >= right } }
            .distinct()
        val gutter = gaps.maxWithOrNull(compareBy<Pair<Float, Float>> { it.second - it.first }.thenByDescending { -it.first })
        if (gutter != null) {
            val (leftEdge, rightEdge) = gutter
            val left = blocks.filter { it.bounds.right <= leftEdge }
            val right = blocks.filter { it.bounds.left >= rightEdge }
            val crossing = blocks.any { it !in left && it !in right }
            if (!crossing && left.isNotEmpty() && right.isNotEmpty()) {
                return LayoutOrder(sortColumn(left), multiColumn = true, unreliable = false)
                    .let { LayoutOrder(it.blocks + sortColumn(right), multiColumn = true, unreliable = it.unreliable) }
            }
        }
        val sorted = blocks.sortedWith(compareBy<PdfTextBlock> { it.bounds.top }.thenBy { it.bounds.left })
        val unreliable = blocks.indices.any { index ->
            blocks.drop(index + 1).any { other ->
                val current = blocks[index]
                current.bounds.overlaps(other.bounds) && current.bounds.top < other.bounds.bottom &&
                    other.bounds.top < current.bounds.bottom
            }
        }
        return LayoutOrder(sorted, multiColumn = false, unreliable = unreliable)
    }

    private fun sortColumn(blocks: List<PdfTextBlock>): List<PdfTextBlock> =
        blocks.sortedWith(compareBy<PdfTextBlock> { it.bounds.top }.thenBy { it.bounds.left })
}

public object PdfPageInspector {
    public fun inspect(
        page: PdfPage,
        limits: PdfImportLimits = PdfImportLimits.Production,
    ): InspectedPdfPage {
        val normalizedBlocks = page.blocks.map { block ->
            block.copy(block = block.block.copy(sourceText = PdfTextNormalizer.normalize(block.block.sourceText)))
        }.filter { it.block.sourceText.isNotEmpty() }
        val layout = PdfLayoutProfile.order(normalizedBlocks)
        val normalizedText = PdfTextNormalizer.normalize(
            if (layout.blocks.isEmpty()) page.text else layout.blocks.joinToString("\n") { it.block.sourceText },
        )
        val locator = page.locator.toString()
        val warnings = buildList {
            if (layout.multiColumn) add(
                ImportWarning(
                    ImportWarningCode.MULTI_COLUMN,
                    locator,
                    "This page has two separated text columns.",
                    "Review the reading order before accepting the import.",
                ),
            )
            if (page.externalResourceCount > 0) add(
                ImportWarning(
                    ImportWarningCode.EXTERNAL_RESOURCE,
                    locator,
                    "External PDF references were ignored.",
                    "Only text from the selected local PDF was inspected.",
                ),
            )
        }
        val diagnostics = buildList {
            if (layout.unreliable) add(
                ImportDiagnostic(
                    ImportDiagnosticCode.UNRELIABLE_LAYOUT,
                    locator,
                    "The page reading order cannot be determined safely.",
                    "Do not accept this page; use a simpler PDF layout.",
                ),
            )
            if (normalizedText.isEmpty()) {
                add(
                    if (page.hasImageContent) ImportDiagnostic(
                        ImportDiagnosticCode.OCR_UNSUPPORTED,
                        locator,
                        "This page contains an image without extractable text.",
                        "OCR is not supported; choose a born-digital PDF.",
                    ) else if (page.canDistinguishEmptyPage) ImportDiagnostic(
                        ImportDiagnosticCode.EMPTY_PAGE,
                        locator,
                        "This selected page contains no narratable text.",
                        "Choose a range containing text pages.",
                    ) else ImportDiagnostic(
                        ImportDiagnosticCode.UNSUPPORTED_PDF,
                        locator,
                        "The PDF adapter could not classify this empty page safely.",
                        "Choose a PDF with supported text and page markers.",
                    ),
                )
            }
            val bytes = normalizedText.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (!limits.acceptsPageTextBytes(bytes)) add(
                ImportDiagnostic(
                    ImportDiagnosticCode.PAGE_TEXT_TOO_LARGE,
                    locator,
                    "This page exceeds the extracted text limit.",
                    "Select a smaller page or document.",
                ),
            )
        }
        return InspectedPdfPage(page.copy(text = normalizedText, blocks = layout.blocks), warnings, diagnostics)
    }
}

public data class InspectedPdfPage(
    public val page: PdfPage,
    public val warnings: List<ImportWarning>,
    public val diagnostics: List<ImportDiagnostic>,
)

/** Qualification gate implementation: no parser is selected, so production fails closed. */
public class UnavailablePdfPageImporter : PdfPageImporter {
    override suspend fun pageCount(source: StagedPdfSource, controls: PdfInspectionControls): PdfPageCountResult {
        controls.deadline.check()
        return PdfPageCountResult.Failed(unavailableDiagnostic())
    }

    override suspend fun inspect(
        source: StagedPdfSource,
        range: PageRange,
        controls: PdfInspectionControls,
    ): PdfInspectionResult {
        controls.deadline.check()
        return PdfInspectionResult.Failed(unavailableDiagnostic())
    }

    private fun unavailableDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.PDF_FEATURE_UNAVAILABLE,
        message = "PDF import is unavailable because parser qualification did not pass.",
        action = "Use EPUB import or wait for a qualified offline PDF parser.",
    )
}

public data class PdfImportPreview(
    public val stagedSource: StagedPdfSource,
    public val inspection: PdfImportInspection,
) {
    public val canAccept: Boolean get() = inspection.blockingDiagnostics.none { it.blocking }
}

public sealed interface PdfPreviewResult {
    public data class Ready(val preview: PdfImportPreview) : PdfPreviewResult
    public data class Duplicate(val project: ExistingPdfProject) : PdfPreviewResult
    public data class Failed(val diagnostic: ImportDiagnostic) : PdfPreviewResult
}

public class PdfImportPreviewService(
    private val repository: SafPdfSourceRepository,
    private val importer: PdfPageImporter = UnavailablePdfPageImporter(),
    private val limits: PdfImportLimits = PdfImportLimits.Production,
) {
    public suspend fun previewSelected(uri: android.net.Uri, startPage: Int, endPage: Int): PdfPreviewResult =
        previewSource(uri.toString(), startPage, endPage)

    public suspend fun previewSource(sourceUri: String, startPage: Int, endPage: Int): PdfPreviewResult {
        coroutineContext.ensureActive()
        val callerContext = coroutineContext
        return when (val staged = repository.stageSource(sourceUri) { callerContext.ensureActive() }) {
            is PdfStageResult.Duplicate -> PdfPreviewResult.Duplicate(staged.project)
            is PdfStageResult.Failed -> PdfPreviewResult.Failed(staged.toDiagnostic())
            is PdfStageResult.Staged -> inspectStaged(staged.source, startPage, endPage)
        }
    }

    public fun discard(preview: PdfImportPreview) = repository.discardStaged(preview.stagedSource)

    private suspend fun inspectStaged(source: StagedPdfSource, startPage: Int, endPage: Int): PdfPreviewResult {
        try {
            if (!repository.verifyCurrent(source)) return failAndDiscard(source, sourceChangedDiagnostic())
            val deadline = PdfDeadline.start(limits)
            val controls = PdfInspectionControls(deadline)
            val countResult = importer.pageCount(source, controls)
            deadline.check()
            val count = (countResult as? PdfPageCountResult.Accepted)?.count?.pageCount
                ?: return failAndDiscard(source, (countResult as PdfPageCountResult.Failed).diagnostic)
            if (!limits.acceptsPageCount(count)) {
                return failAndDiscard(source, ImportDiagnostic(
                    ImportDiagnosticCode.PAGE_COUNT_TOO_LARGE,
                    message = "The PDF contains too many pages.",
                    action = "Choose a PDF with at most ${limits.maxPages} pages.",
                ))
            }
            val range = runCatching { PageRange.validate(startPage, endPage, count, limits) }
                .getOrElse { return failAndDiscard(source, rangeDiagnostic()) }
            if (!repository.verifyCurrent(source)) return failAndDiscard(source, sourceChangedDiagnostic())
            val result = importer.inspect(source, range, controls)
            deadline.check()
            val inspection = (result as? PdfInspectionResult.Accepted)?.inspection
                ?: return failAndDiscard(source, (result as PdfInspectionResult.Failed).diagnostic)
            if (!repository.verifyCurrent(source)) return failAndDiscard(source, sourceChangedDiagnostic())
            if (inspection.pageCount != count || inspection.range != range ||
                inspection.pages.map { it.pageNumber } != range.asList() ||
                inspection.provenance.fingerprint != source.fingerprint ||
                inspection.provenance.sourcePath != source.sourceFile.path
            ) {
                return failAndDiscard(source, ImportDiagnostic(
                    ImportDiagnosticCode.UNSUPPORTED_PDF,
                    message = "The PDF adapter returned an invalid inspection.",
                    action = "Choose a different readable PDF.",
                ))
            }
            val inspected = inspection.pages.map { PdfPageInspector.inspect(it, limits) }
            val pages = inspected.map { it.page }
            val warnings = (inspection.warnings + inspected.flatMap { it.warnings }).sortedWith(
                compareBy<ImportWarning>({ locatorPage(it.locator) }, { locatorBlock(it.locator) }, { it.locator }),
            )
            val diagnostics = (inspection.blockingDiagnostics + inspected.flatMap { it.diagnostics }).sortedWith(
                compareBy<ImportDiagnostic>({ locatorPage(it.locator) }, { locatorBlock(it.locator) }, { it.locator.orEmpty() }),
            ) +
                rangeTextDiagnosticIfNeeded(pages)
            return PdfPreviewResult.Ready(
                PdfImportPreview(
                    source,
                    inspection.copy(pages = pages, warnings = warnings, blockingDiagnostics = diagnostics),
                ),
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            repository.discardStaged(source)
            throw kotlinx.coroutines.CancellationException("PDF preview cancelled")
        } catch (_: PdfInspectionTimeoutException) {
            return failAndDiscard(source, ImportDiagnostic(
                ImportDiagnosticCode.INSPECTION_TIMEOUT,
                message = "PDF inspection exceeded the time limit.",
                action = "Choose a smaller PDF or page range.",
            ))
        } catch (_: Exception) {
            return failAndDiscard(source, ImportDiagnostic(
                ImportDiagnosticCode.MALFORMED_PDF,
                message = "The PDF could not be inspected safely.",
                action = "Choose a readable, unprotected PDF.",
            ))
        }
    }

    private fun rangeTextDiagnosticIfNeeded(pages: List<PdfPage>): List<ImportDiagnostic> {
        val bytes = pages.sumOf { it.text.toByteArray(StandardCharsets.UTF_8).size.toLong() }
        return if (limits.acceptsRangeTextBytes(bytes)) emptyList() else listOf(
            ImportDiagnostic(
                ImportDiagnosticCode.RANGE_TEXT_TOO_LARGE,
                message = "The selected pages contain too much extracted text.",
                action = "Select a smaller page range.",
            ),
        )
    }

    private fun failAndDiscard(source: StagedPdfSource, diagnostic: ImportDiagnostic): PdfPreviewResult {
        repository.discardStaged(source)
        return PdfPreviewResult.Failed(diagnostic)
    }

    private fun PdfStageResult.Failed.toDiagnostic() = ImportDiagnostic(
        when (error) {
            PdfStageError.SOURCE_UNAVAILABLE -> ImportDiagnosticCode.SOURCE_UNAVAILABLE
            PdfStageError.SOURCE_TOO_LARGE -> ImportDiagnosticCode.SOURCE_TOO_LARGE
            PdfStageError.INVALID_FORMAT -> ImportDiagnosticCode.UNSUPPORTED_PDF
            PdfStageError.COPY_FAILED -> ImportDiagnosticCode.SOURCE_CHANGED
            PdfStageError.DUPLICATE -> ImportDiagnosticCode.ACCEPTANCE_FAILED
        },
        message = "The selected PDF could not be staged safely.",
        action = "Select a local readable PDF and retry.",
    )

    private fun rangeDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.PAGE_RANGE_INVALID,
        message = "The selected page range is invalid.",
        action = "Enter one inclusive range from page 1 to the displayed page count.",
    )

    private fun sourceChangedDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.SOURCE_CHANGED,
        message = "The staged PDF changed before inspection.",
        action = "Select the PDF again.",
    )
}

private fun locatorPage(locator: String?): Int =
    Regex("/page/(\\d+)").find(locator.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

private fun locatorBlock(locator: String?): Int =
    Regex("/block/(\\d+)").find(locator.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

private fun PdfImportInspection.copy(
    pages: List<PdfPage>,
    warnings: List<ImportWarning>,
    blockingDiagnostics: List<ImportDiagnostic>,
): PdfImportInspection = PdfImportInspection(
    pageCount = pageCount,
    range = range,
    pages = pages,
    warnings = warnings,
    blockingDiagnostics = blockingDiagnostics,
    provenance = provenance,
)
