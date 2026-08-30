package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.document.DocumentIr
import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore

public sealed interface PdfAcceptanceResult {
    public data class Published(val source: ImportedPdfSource, val document: DocumentIr) : PdfAcceptanceResult
    public data class Failed(val diagnostic: ImportDiagnostic) : PdfAcceptanceResult
}

/** Publishes only new owner paths, then projects all Room rows through the index transaction. */
public class PdfAcceptanceService(
    private val repository: SafPdfSourceRepository,
    private val index: PdfProjectIndex,
    private val canonical: PdfCanonicalTextService,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
) {
    public fun accept(preview: PdfImportPreview, document: DocumentIr): PdfAcceptanceResult {
        if (!preview.canAccept || !matchesPreview(preview, document)) {
            repository.discardStaged(preview.stagedSource)
            return PdfAcceptanceResult.Failed(failure("The PDF preview is no longer valid."))
        }
        if (!repository.verifyCurrent(preview.stagedSource)) {
            repository.discardStaged(preview.stagedSource)
            return PdfAcceptanceResult.Failed(sourceChanged())
        }
        val published = repository.publishStaged(preview.stagedSource)
        if (published !is PdfPublishResult.Published) {
            return PdfAcceptanceResult.Failed((published as PdfPublishResult.Failed).diagnostic)
        }
        val source = published.source
        return try {
            val output = canonical.renderAndPersist(document, preview.inspection.warnings)
            index.recordAcceptedDocument(
                source,
                document,
                output.artifacts.associate { it.chapterId to it.path.path },
            )
            PdfAcceptanceResult.Published(source, document)
        } catch (_: Exception) {
            storage.sourcePdf(source.projectId).delete()
            outputFiles(document).forEach { it.delete() }
            storage.importWarnings(source.projectId).delete()
            PdfAcceptanceResult.Failed(failure("The PDF import could not be completed atomically."))
        }
    }

    private fun matchesPreview(preview: PdfImportPreview, document: DocumentIr): Boolean = runCatching {
        val expected = PdfDocumentProjector.toIr(
            preview = preview,
            title = document.title,
            author = document.author,
            language = document.language,
        )
        document.provenance == expected.provenance &&
            document.canonicalSerialization() == expected.canonicalSerialization()
    }.getOrDefault(false)

    private fun outputFiles(document: DocumentIr): List<java.io.File> {
        val owner = document.provenance.projectId.ifBlank { document.provenance.fingerprint }
        return document.chapters.map { chapter ->
            storage.canonicalChapterText(owner, "${document.provenance.fingerprint}-page-${chapter.locator.pageNumber}")
        }
    }

    private fun sourceChanged() = ImportDiagnostic(
        ImportDiagnosticCode.SOURCE_CHANGED,
        message = "The staged PDF changed before acceptance.",
        action = "Select the PDF again.",
    )

    private fun failure(message: String) = ImportDiagnostic(
        ImportDiagnosticCode.ACCEPTANCE_FAILED,
        message = message,
        action = "Retry with the same local PDF.",
    )
}
