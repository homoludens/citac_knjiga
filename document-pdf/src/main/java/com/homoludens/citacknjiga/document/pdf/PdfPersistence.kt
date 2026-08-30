package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.document.DocumentIr
import java.io.File

public class RoomPdfProjectIndex(
    private val dao: AudiobookDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : PdfProjectIndex {
    override fun findByFingerprint(fingerprint: String): ExistingPdfProject? =
        dao.findProjectBySourceFingerprint(fingerprint)?.let { project ->
            ExistingPdfProject(project.id, project.sourceUri, project.sourceFingerprint, project.sourcePath?.let(::File))
        }

    override fun recordAcceptedDocument(
        source: ImportedPdfSource,
        document: DocumentIr,
        canonicalChapterPaths: Map<String, String>,
    ) {
        val projection = PdfDocumentProjector.toRoomProjection(
            source = source,
            document = document,
            now = clock(),
            canonicalChapterPaths = canonicalChapterPaths,
        )
        dao.insertDocument(projection.project.copy(status = BookProjectStatus.READY), projection.chapters, projection.narrationBlocks)
    }
}
