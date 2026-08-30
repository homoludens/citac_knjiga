package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.document.DocumentBlock
import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportProvenance
import com.homoludens.citacknjiga.core.document.ImportWarning
import com.homoludens.citacknjiga.core.document.PageLocator
import com.homoludens.citacknjiga.core.document.PdfImportLimits
import com.homoludens.citacknjiga.core.document.SourceLocator
import com.homoludens.citacknjiga.core.document.acceptsSelectedPages
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

public data class StagedPdfSource(
    public val projectId: String,
    public val sourceUri: String,
    public val fingerprint: String,
    public val sourceFile: File,
    public val sizeBytes: Long,
)

public data class ImportedPdfSource(
    public val projectId: String,
    public val sourceUri: String,
    public val fingerprint: String,
    public val sourceFile: File,
    public val sizeBytes: Long,
)

public data class ExistingPdfProject(
    public val projectId: String,
    public val sourceUri: String,
    public val fingerprint: String,
    public val sourceFile: File?,
)

public fun interface PdfSourceReader {
    public fun open(sourceUri: String): InputStream?
}

public interface PdfProjectIndex {
    public fun findByFingerprint(fingerprint: String): ExistingPdfProject?

    public fun recordAcceptedDocument(
        source: ImportedPdfSource,
        document: com.homoludens.citacknjiga.core.document.DocumentIr,
        canonicalChapterPaths: Map<String, String>,
    )
}

public data class PageRange(val startPage: Int, val endPage: Int) {
    init {
        require(startPage >= 1 && endPage >= startPage) { "Page range must be 1-based and inclusive" }
    }

    public val pageCount: Int get() = endPage - startPage + 1

    public fun asList(): List<Int> = (startPage..endPage).toList()

    public companion object {
        public fun validate(
            startPage: Int,
            endPage: Int,
            pageCount: Int,
            limits: PdfImportLimits = PdfImportLimits.Production,
        ): PageRange {
            require(pageCount >= 1) { "PDF has no pages" }
            require(startPage >= 1 && endPage >= startPage && endPage <= pageCount) {
                "Page range is outside the document"
            }
            require(limits.acceptsSelectedPages(endPage - startPage + 1)) {
                "Selected page range exceeds the limit"
            }
            return PageRange(startPage, endPage)
        }
    }
}

public data class PdfPageCount(val pageCount: Int)

public data class PdfTextBlock(
    public val block: DocumentBlock,
    public val bounds: NormalizedRect,
)

public data class PdfPage(
    public val pageNumber: Int,
    public val text: String,
    public val blocks: List<PdfTextBlock>,
    public val locator: PageLocator,
    public val hasImageContent: Boolean = false,
    public val canDistinguishEmptyPage: Boolean = true,
    public val externalResourceCount: Int = 0,
)

public data class PdfImportInspection(
    public val pageCount: Int,
    public val range: PageRange,
    public val pages: List<PdfPage>,
    public val warnings: List<ImportWarning>,
    public val blockingDiagnostics: List<ImportDiagnostic>,
    public val provenance: ImportProvenance,
)

public sealed interface PdfPageCountResult {
    public data class Accepted(val count: PdfPageCount) : PdfPageCountResult
    public data class Failed(val diagnostic: ImportDiagnostic) : PdfPageCountResult
}

public sealed interface PdfInspectionResult {
    public data class Accepted(val inspection: PdfImportInspection) : PdfInspectionResult
    public data class Failed(val diagnostic: ImportDiagnostic) : PdfInspectionResult
}

/** Only staged bytes and bounded controls cross the parser boundary. */
public interface PdfPageImporter {
    public suspend fun pageCount(source: StagedPdfSource, controls: PdfInspectionControls): PdfPageCountResult

    public suspend fun inspect(
        source: StagedPdfSource,
        range: PageRange,
        controls: PdfInspectionControls,
    ): PdfInspectionResult
}

public interface PdfResourcePolicy {
    public fun allowExternalResource(): Nothing = error("External PDF resources are disabled")
}

public object LocalPdfResourcePolicy : PdfResourcePolicy

public class PdfDeadline private constructor(
    private val deadlineNanos: Long,
    private val nowNanos: () -> Long,
) {
    public suspend fun check() {
        coroutineContext.ensureActive()
        if (nowNanos() > deadlineNanos) throw PdfInspectionTimeoutException()
    }

    public companion object {
        public fun start(
            limits: PdfImportLimits = PdfImportLimits.Production,
            nowNanos: () -> Long = System::nanoTime,
        ): PdfDeadline = PdfDeadline(nowNanos() + limits.maxProcessingNanos, nowNanos)
    }
}

public class PdfInspectionTimeoutException : IllegalStateException("PDF inspection deadline exceeded")

public data class PdfInspectionControls(
    public val deadline: PdfDeadline,
    public val resourcePolicy: PdfResourcePolicy = LocalPdfResourcePolicy,
)

public enum class PdfStageError {
    SOURCE_UNAVAILABLE,
    COPY_FAILED,
    SOURCE_TOO_LARGE,
    INVALID_FORMAT,
    DUPLICATE,
}

public sealed interface PdfStageResult {
    public data class Staged(val source: StagedPdfSource) : PdfStageResult
    public data class Duplicate(val project: ExistingPdfProject) : PdfStageResult
    public data class Failed(val error: PdfStageError) : PdfStageResult
}

public sealed interface PdfPublishResult {
    public data class Published(val source: ImportedPdfSource) : PdfPublishResult
    public data class Failed(val diagnostic: ImportDiagnostic) : PdfPublishResult
}

public data class NormalizedRect(
    public val left: Float,
    public val top: Float,
    public val right: Float,
    public val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f)
        require(left <= right && top <= bottom)
    }

    public fun overlaps(other: NormalizedRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    public val width: Float get() = right - left
}

public object PdfFeatureAvailability {
    /** Qualification is intentionally a hard production gate. */
    public const val QUALIFIED: Boolean = false
}

public class PdfFeatureUnavailableException : UnsupportedOperationException(
    "PDF import is unavailable because no parser passed qualification",
)
