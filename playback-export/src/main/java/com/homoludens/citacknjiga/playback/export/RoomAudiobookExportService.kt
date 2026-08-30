package com.homoludens.citacknjiga.playback.export

import android.content.ContentResolver
import android.net.Uri
import com.google.gson.Gson
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.ExportChapterStatus
import com.homoludens.citacknjiga.core.database.ExportJobChapterEntity
import com.homoludens.citacknjiga.core.database.ExportJobEntity
import com.homoludens.citacknjiga.core.database.ExportJobStatus
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Room-backed export coordinator. Room is the recovery source of truth. */
public class RoomAudiobookExportService(
    private val dao: AudiobookDao,
    private val exporter: SafAudiobookExporter,
    private val contentResolver: ContentResolver,
    private val destinationFactory: (Uri) -> SafDocumentTree = { uri ->
        ContentResolverDocumentTree(contentResolver, uri)
    },
) {
    private val gson = Gson()

    public fun planForProject(destinationUri: Uri, projectId: String): ExportPlan {
        val request = requestForProject(projectId)
        val plan = exporter.plan(destinationFactory(destinationUri), request)
        val now = System.currentTimeMillis()
        val jobId = UUID.randomUUID().toString()
        val chapterFiles = plan.files.filter { it.sourceSegments.isNotEmpty() }
        val chapterRows = chapterFiles.map { file ->
            val chapter = file.sourceSegments.first().chapterId
            val input = request.chapters.first { it.chapter.id == chapter }
            ExportJobChapterEntity(
                exportJobId = jobId,
                chapterId = chapter,
                ordinal = input.chapter.ordinal,
                title = input.chapter.title,
                sourceSegmentIdsJson = gson.toJson(file.sourceSegments.map { it.id }),
                fileName = file.name,
                sha256 = sha256(file.sourceFiles.single()),
                sizeBytes = file.sourceFiles.single().length(),
                durationMs = file.assembledDurationMs,
                createdAt = now,
                updatedAt = now,
            )
        }
        val manifest = plan.files.first { it.mimeType == "application/json" }
        val cover = plan.files.firstOrNull { it.mimeType.startsWith("image/") }
        dao.insertExportJobWithChapters(
            ExportJobEntity(
                id = jobId,
                bookProjectId = projectId,
                destinationUri = destinationUri.toString(),
                selectedChapterIdsJson = gson.toJson(request.chapters.map { it.chapter.id }),
                totalChapters = chapterRows.size,
                manifestName = manifest.name,
                coverName = cover?.name,
                createdAt = now,
                updatedAt = now,
            ),
            chapterRows,
        )
        return plan.withJobId(jobId)
    }

    public fun export(plan: ExportPlan, overwriteConfirmed: Boolean = false): ExportedAudiobook {
        val jobId = requireNotNull(plan.jobId) { "Export plan is not persisted" }
        val job = requireNotNull(dao.findExportJobById(jobId)) { "Export job was not found" }
        val rows = dao.findExportJobChapters(jobId)
        require(rows.isNotEmpty()) { "Export job has no chapter plan" }
        val durablePlan = plan.withPersistedNames(
            chapterNames = rows.associate { it.chapterId to it.fileName },
            manifestName = job.manifestName,
            coverName = job.coverName,
            overwriteExisting = overwriteConfirmed || plan.overwriteExisting || job.status != ExportJobStatus.QUEUED,
        )
        return runJob(job, rows, durablePlan)
    }

    /** Rebuilds a plan from the persisted ordered chapter/segment plan after process death. */
    public fun resume(jobId: String): ExportedAudiobook {
        val job = requireNotNull(dao.findExportJobById(jobId)) { "Export job was not found" }
        val rows = dao.findExportJobChapters(jobId)
        val plan = exporter.plan(
            destinationFactory(Uri.parse(job.destinationUri)),
            requestForJob(job, rows),
            overwriteExisting = true,
        ).withJobId(jobId)
        return runJob(job, rows, plan.withPersistedNames(
            rows.associate { it.chapterId to it.fileName },
            job.manifestName,
            job.coverName,
            overwriteExisting = true,
        ))
    }

    /** Retries failed or interrupted chapters; VERIFIED rows are only rechecked. */
    public fun retry(jobId: String): ExportedAudiobook {
        val now = System.currentTimeMillis()
        dao.findExportJobChapters(jobId).filter {
            it.status == ExportChapterStatus.FAILED || it.status == ExportChapterStatus.CANCELLED
        }.forEach { row ->
            ExportJobStateValidator.requireTransition(row.status, ExportChapterStatus.PENDING)
            dao.updateExportJobChapter(row.copy(status = ExportChapterStatus.PENDING, updatedAt = now))
        }
        return resume(jobId)
    }

    public fun cancel(jobId: String) {
        val now = System.currentTimeMillis()
        dao.findExportJobChapters(jobId).filter { it.status == ExportChapterStatus.PENDING || it.status == ExportChapterStatus.WRITING }
            .forEach { row ->
                ExportJobStateValidator.requireTransition(row.status, ExportChapterStatus.CANCELLED)
                dao.updateExportJobChapter(row.copy(status = ExportChapterStatus.CANCELLED, updatedAt = now))
            }
        dao.findExportJobById(jobId)?.let { dao.updateExportJob(it.copy(status = ExportJobStatus.CANCELLED, updatedAt = now)) }
    }

    /** Returns interrupted WRITING checkpoints to the queue without touching verified files. */
    public fun reconcileInterruptedJobs() {
        val now = System.currentTimeMillis()
        dao.findAllExportJobs().filter { it.status == ExportJobStatus.RUNNING }.forEach { job ->
            dao.findExportJobChapters(job.id).filter { it.status == ExportChapterStatus.WRITING }.forEach { row ->
                ExportJobStateValidator.requireTransition(row.status, ExportChapterStatus.PENDING)
                dao.updateExportJobChapter(row.copy(status = ExportChapterStatus.PENDING, updatedAt = now))
            }
            dao.updateExportJob(job.copy(status = ExportJobStatus.QUEUED, updatedAt = now))
        }
    }

    private fun runJob(
        job: ExportJobEntity,
        rows: List<ExportJobChapterEntity>,
        plan: ExportPlan,
    ): ExportedAudiobook {
        val now = System.currentTimeMillis()
        dao.updateExportJob(job.copy(status = ExportJobStatus.RUNNING, currentChapterOrdinal = null, updatedAt = now))
        var activeChapterId: String? = null
        val listener = object : ExportProgressListener {
            override fun onTemporaryFile(planned: PlannedExportFile, uri: Uri) {
                if (planned.sourceSegments.isEmpty()) return
                activeChapterId = planned.sourceSegments.first().chapterId
                val current = requireNotNull(dao.findExportJobChapter(job.id, activeChapterId!!))
                val pending = if (current.status == ExportChapterStatus.VERIFIED) {
                    ExportJobStateValidator.requireTransition(current.status, ExportChapterStatus.PENDING)
                    current.copy(status = ExportChapterStatus.PENDING)
                } else current
                ExportJobStateValidator.requireTransition(pending.status, ExportChapterStatus.WRITING)
                dao.updateExportJobChapter(
                    pending.copy(
                        status = ExportChapterStatus.WRITING,
                        temporaryUri = uri.toString(),
                        attemptCount = pending.attemptCount + 1,
                        lastError = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                dao.findExportJobById(job.id)?.let {
                    dao.updateExportJob(it.copy(currentChapterOrdinal = currentOrdinal(job.id), updatedAt = System.currentTimeMillis()))
                }
            }

            override fun onVerifiedFile(planned: PlannedExportFile, verification: ExportedFileVerification) {
                if (planned.sourceSegments.isEmpty()) return
                val chapterId = planned.sourceSegments.first().chapterId
                val current = requireNotNull(dao.findExportJobChapter(job.id, chapterId))
                ExportJobStateValidator.requireTransition(current.status, ExportChapterStatus.VERIFIED)
                dao.updateExportJobChapter(
                    current.copy(
                        status = ExportChapterStatus.VERIFIED,
                        fileUri = verification.uri.toString(),
                        temporaryUri = null,
                        sha256 = verification.sha256,
                        sizeBytes = verification.sizeBytes,
                        lastError = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                val complete = dao.findExportJobChapters(job.id).count { it.status == ExportChapterStatus.VERIFIED }
                dao.findExportJobById(job.id)?.let {
                    dao.updateExportJob(
                        it.copy(
                            completedChapters = complete,
                            currentChapterOrdinal = currentOrdinal(job.id),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        val verifiedNames = rows.filter { it.status == ExportChapterStatus.VERIFIED }.map { it.fileName }.toSet()
        val temporaryUris = rows.mapNotNull { row -> row.temporaryUri?.let { row.fileName to Uri.parse(it) } }.toMap()
        return try {
            val result = exporter.export(
                plan,
                overwriteConfirmed = plan.overwriteExisting,
                skipNames = verifiedNames,
                temporaryUris = temporaryUris,
                listener = listener,
            )
            val completed = dao.findExportJobChapters(job.id).count { it.status == ExportChapterStatus.VERIFIED }
            require(completed == rows.size) { "Export completed without verifying every chapter" }
            val finished = dao.findExportJobById(job.id)?.copy(
                status = ExportJobStatus.COMPLETED,
                completedChapters = completed,
                manifestPath = result.manifestUri.toString(),
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            ) ?: error("Export job disappeared")
            dao.updateExportJob(finished)
            result
        } catch (cancelled: ExportCancelledException) {
            markInterrupted(job.id, ExportJobStatus.CANCELLED, "CANCELLED")
            throw cancelled
        } catch (failure: Throwable) {
            markInterrupted(job.id, ExportJobStatus.FAILED, failure.message ?: "EXPORT_FAILED")
            throw failure
        }
    }

    private fun markInterrupted(jobId: String, status: ExportJobStatus, error: String) {
        val now = System.currentTimeMillis()
        dao.findExportJobChapters(jobId).filter { it.status == ExportChapterStatus.WRITING || it.status == ExportChapterStatus.PENDING }
            .forEach { row ->
                val target = if (status == ExportJobStatus.CANCELLED) ExportChapterStatus.CANCELLED else ExportChapterStatus.FAILED
                ExportJobStateValidator.requireTransition(row.status, target)
                dao.updateExportJobChapter(row.copy(status = target, lastError = error, updatedAt = now))
            }
        dao.findExportJobById(jobId)?.let { dao.updateExportJob(it.copy(status = status, lastError = error, updatedAt = now)) }
    }

    private fun currentOrdinal(jobId: String): Int? = dao.findExportJobChapters(jobId)
        .firstOrNull { it.status == ExportChapterStatus.WRITING }?.ordinal

    private fun requestForProject(projectId: String): ExportRequest {
        val project = requireNotNull(dao.findProjectById(projectId)) { "Book was not found" }
        val chapters = dao.findAllChapters().filter { it.bookProjectId == projectId }.sortedBy { it.ordinal }.map { chapter ->
            val relations = requireNotNull(dao.findChapterWithRelations(chapter.id)) { "Chapter was not found" }
            ExportChapterInput(chapter, relations.audioSegments)
        }
        return ExportRequest(project, chapters)
    }

    private fun requestForJob(job: ExportJobEntity, rows: List<ExportJobChapterEntity>): ExportRequest {
        val project = requireNotNull(dao.findProjectById(job.bookProjectId)) { "Book was not found" }
        val chapters = rows.sortedBy { it.ordinal }.map { row ->
            val chapter = requireNotNull(dao.findChapterById(row.chapterId)) { "Chapter was deleted" }
            val relations = requireNotNull(dao.findChapterWithRelations(row.chapterId)) { "Chapter was deleted" }
            val ids = gson.fromJson(row.sourceSegmentIdsJson, Array<String>::class.java).toList()
            val byId = relations.audioSegments.associateBy { it.id }
            ExportChapterInput(chapter, ids.map { requireNotNull(byId[it]) { "Export source segment was deleted" } })
        }
        return ExportRequest(project, chapters)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
