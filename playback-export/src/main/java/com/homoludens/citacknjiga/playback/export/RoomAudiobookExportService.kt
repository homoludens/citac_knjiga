package com.homoludens.citacknjiga.playback.export

import android.net.Uri
import android.content.ContentResolver
import com.homoludens.citacknjiga.core.database.AudiobookDao

/** Room adapter for the one-shot export boundary. Progress and retry remain task 10.6. */
public class RoomAudiobookExportService(
    private val dao: AudiobookDao,
    private val exporter: SafAudiobookExporter,
    private val contentResolver: ContentResolver,
) {
    public fun planForProject(destinationUri: Uri, projectId: String): ExportPlan {
        val project = dao.findProjectWithRelations(projectId)?.project
            ?: throw IllegalArgumentException("Book was not found")
        val chapters = dao.findAllChapters()
            .filter { it.bookProjectId == projectId }
            .sortedBy { it.ordinal }
            .map { chapter ->
                val relations = dao.findChapterWithRelations(chapter.id)
                    ?: throw IllegalArgumentException("Chapter was not found")
                ExportChapterInput(chapter, relations.audioSegments)
            }
        return exporter.plan(ContentResolverDocumentTree(contentResolver, destinationUri), ExportRequest(project, chapters))
    }

    public fun export(plan: ExportPlan, overwriteConfirmed: Boolean = false): ExportedAudiobook =
        exporter.export(plan, overwriteConfirmed)
}
