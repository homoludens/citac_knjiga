package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.document.DocumentIr
import com.homoludens.citacknjiga.core.document.ImportWarning
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File

public data class PdfChapterArtifact(
    public val chapterId: String,
    public val path: File,
    public val markdown: String,
    public val sha256: String,
)

public data class PdfCanonicalPreview(
    public val artifacts: List<PdfChapterArtifact>,
    public val warningReport: String,
)

/** Deterministic UTF-8 artifacts use the shared atomic private store. */
public class PdfCanonicalTextService(
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val renderer: PdfMarkdownRenderer = PdfMarkdownRenderer(),
) {
    public fun preview(document: DocumentIr, warnings: List<ImportWarning> = emptyList()): PdfCanonicalPreview {
        val artifacts = document.chapters.sortedBy { it.ordinal }.map { chapter ->
            val id = PdfDocumentProjector.chapterId(document.provenance.projectId, chapter)
            val markdown = renderer.render(chapter)
            val owner = document.provenance.projectId.ifBlank { document.provenance.fingerprint }
            PdfChapterArtifact(id, storage.canonicalChapterText(owner, id), markdown, "")
        }
        return PdfCanonicalPreview(artifacts, warningReport(document.provenance.fingerprint, warnings))
    }

    public fun renderAndPersist(document: DocumentIr, warnings: List<ImportWarning> = emptyList()): PdfCanonicalPreview {
        val preview = preview(document, warnings)
        val written = mutableListOf<File>()
        return try {
            val published = preview.artifacts.map { artifact ->
                val result = artifactStore.publish(
                    ownerId = "pdf-canonical-${document.provenance.fingerprint}",
                    destination = artifact.path,
                    writer = { output -> output.write(artifact.markdown.toByteArray(Charsets.UTF_8)) },
                    validator = { file -> require(file.readText(Charsets.UTF_8) == artifact.markdown) },
                )
                written += result.file
                artifact.copy(path = result.file, sha256 = result.sha256)
            }
            val warnings = artifactStore.publish(
                ownerId = "pdf-canonical-${document.provenance.fingerprint}",
                destination = storage.importWarnings(document.provenance.projectId.ifBlank { document.provenance.fingerprint }),
                writer = { output -> output.write(preview.warningReport.toByteArray(Charsets.UTF_8)) },
            )
            written += warnings.file
            preview.copy(artifacts = published)
        } catch (failure: Throwable) {
            written.forEach(File::delete)
            throw failure
        }
    }

    private fun warningReport(fingerprint: String, warnings: List<ImportWarning>): String = buildString {
        append("{\n  \"schema\": 1,\n  \"fingerprint\": \"")
        append(fingerprint).append("\",\n  \"warnings\": [")
        warnings.forEachIndexed { index, warning ->
            if (index > 0) append(',')
            append("\n    {\"code\":\"").append(warning.code.name)
                .append("\",\"locator\":\"").append(json(warning.locator))
                .append("\",\"message\":\"").append(json(warning.message))
                .append("\",\"action\":\"").append(json(warning.action)).append("\"}")
        }
        if (warnings.isNotEmpty()) append('\n')
        append("  ]\n}\n")
    }

    private fun json(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
    }
}
