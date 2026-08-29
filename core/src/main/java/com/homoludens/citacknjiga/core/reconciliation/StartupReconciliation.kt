package com.homoludens.citacknjiga.core.reconciliation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File

public data class ReconciliationSnapshot(
    public val projects: List<BookProjectEntity>,
    public val chapters: List<ChapterEntity>,
    public val generationRuns: List<GenerationRunEntity>,
    public val audioSegments: List<AudioSegmentEntity>,
    public val activeModelPackage: ModelPackageEntity?,
)

/** Minimal database surface that lets reconciliation stay JVM-testable. */
public interface ReconciliationDatabase {
    public fun snapshot(): ReconciliationSnapshot

    public fun inTransaction(block: () -> Unit)

    public fun updateProject(project: BookProjectEntity)

    public fun updateChapter(chapter: ChapterEntity)

    public fun updateGenerationRun(run: GenerationRunEntity)

    public fun updateAudioSegment(segment: AudioSegmentEntity)
}

public class RoomReconciliationDatabase(
    private val database: AudiobookDatabase,
) : ReconciliationDatabase {
    private val dao: AudiobookDao = database.audiobookDao()

    override fun snapshot(): ReconciliationSnapshot = ReconciliationSnapshot(
        projects = dao.findAllProjects(),
        chapters = dao.findAllChapters(),
        generationRuns = dao.findAllGenerationRuns(),
        audioSegments = dao.findAllAudioSegments(),
        activeModelPackage = dao.findActiveModelPackage(),
    )

    override fun inTransaction(block: () -> Unit) {
        database.runInTransaction(block)
    }

    override fun updateProject(project: BookProjectEntity): Unit = dao.updateProject(project)

    override fun updateChapter(chapter: ChapterEntity): Unit = dao.updateChapter(chapter)

    override fun updateGenerationRun(run: GenerationRunEntity): Unit = dao.updateGenerationRun(run)

    override fun updateAudioSegment(segment: AudioSegmentEntity): Unit = dao.updateAudioSegment(segment)
}

public data class ReconciliationReport(
    public val removedTemporaryFileCount: Int,
    public val interruptedRunIds: List<String>,
    public val interruptedSegmentIds: List<String>,
    public val invalidReadySegmentIds: List<String>,
    public val staleProvenanceSegmentIds: List<String>,
    public val staleGenerationKeySegmentIds: List<String> = emptyList(),
)

/** Repairs durable state after a process, storage, reboot, or update interruption. */
public class StartupReconciliation(
    private val database: ReconciliationDatabase,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore = AtomicArtifactStore(storage),
    private val temporaryMaxAgeMillis: Long = DEFAULT_TEMPORARY_MAX_AGE_MILLIS,
) {
    public fun reconcile(expectedGenerationKeys: Map<String, String> = emptyMap()): ReconciliationReport {
        val removedTemporaryFileCount = artifactStore.cleanupStaleTemporaryFiles(temporaryMaxAgeMillis)
        val snapshot = database.snapshot()
        val runsById = snapshot.generationRuns.associateBy { it.id }
        val segmentsByChapter = snapshot.audioSegments.groupBy { it.chapterId }
        val changedRuns = snapshot.generationRuns
            .filter { it.status == GenerationRunStatus.RUNNING }
            .map { it.copy(status = GenerationRunStatus.QUEUED, startedAt = null, finishedAt = null) }
        val changedSegments = snapshot.audioSegments
            .filter { it.status == AudioSegmentStatus.GENERATING }
            .map { it.copy(status = AudioSegmentStatus.PENDING) }
        val invalidReadySegmentIds = mutableListOf<String>()
        val staleProvenanceSegmentIds = mutableListOf<String>()
        val staleGenerationKeySegmentIds = mutableListOf<String>()
        val changedReadySegments = snapshot.audioSegments
            .filter { it.status == AudioSegmentStatus.READY }
            .mapNotNull { segment ->
                val integrityFailure = !hasValidArtifact(segment)
                val provenanceFailure = !hasCurrentProvenance(segment, snapshot.activeModelPackage, runsById)
                val generationKeyFailure = expectedGenerationKeys[segment.id]?.let { expected ->
                    !segment.generationKey.equals(expected, ignoreCase = true)
                } ?: false
                if (!integrityFailure && !provenanceFailure && !generationKeyFailure) return@mapNotNull null
                if (integrityFailure) invalidReadySegmentIds += segment.id
                if (provenanceFailure) staleProvenanceSegmentIds += segment.id
                if (generationKeyFailure) staleGenerationKeySegmentIds += segment.id
                val reasons = buildList {
                    if (integrityFailure) add("ready audio is missing or checksum-invalid")
                    if (provenanceFailure) add("generation provenance is stale")
                    if (generationKeyFailure) add("generation key is stale")
                }
                segment.copy(
                    status = AudioSegmentStatus.STALE,
                    attemptCount = 0,
                    lastError = reasons.joinToString("; "),
                )
        }
        val repairedSegments = (changedSegments + changedReadySegments).associateBy { it.id }
        val originalSegmentsById = snapshot.audioSegments.associateBy { it.id }
        val segmentsToUpdate = repairedSegments.values.filter { segment ->
            segment != originalSegmentsById[segment.id]
        }
        val changedChapters = snapshot.chapters.mapNotNull { chapter ->
            val chapterSegments = segmentsByChapter[chapter.id].orEmpty()
                .map { repairedSegments[it.id] ?: it }
            val hasNonReadySegment = chapterSegments.isNotEmpty() &&
                chapterSegments.any { it.status != AudioSegmentStatus.READY }
            val newStatus = when {
                chapter.status == ChapterStatus.GENERATING ->
                    if (chapterSegments.any { it.status == AudioSegmentStatus.READY }) {
                        ChapterStatus.PARTIAL
                    } else {
                        ChapterStatus.PENDING
                    }
                chapter.status == ChapterStatus.READY && hasNonReadySegment -> ChapterStatus.PARTIAL
                else -> chapter.status
            }
            if (newStatus == chapter.status) null else chapter.copy(status = newStatus)
        }
        val repairedChapterState = snapshot.chapters.map { chapter ->
            changedChapters.firstOrNull { it.id == chapter.id } ?: chapter
        }
        val chaptersByProject = repairedChapterState.groupBy { it.bookProjectId }
        val changedProjects = snapshot.projects.mapNotNull { project ->
            val projectChapters = chaptersByProject[project.id].orEmpty()
            val hasIncompleteChapter = projectChapters.any { it.status != ChapterStatus.READY }
            val newStatus = when {
                project.status == BookProjectStatus.GENERATING -> BookProjectStatus.READY
                project.status == BookProjectStatus.COMPLETED && hasIncompleteChapter -> BookProjectStatus.READY
                else -> project.status
            }
            if (newStatus == project.status) null else project.copy(status = newStatus)
        }

        database.inTransaction {
            changedRuns.forEach(database::updateGenerationRun)
            segmentsToUpdate.forEach(database::updateAudioSegment)
            changedChapters.forEach(database::updateChapter)
            changedProjects.forEach(database::updateProject)
        }
        return ReconciliationReport(
            removedTemporaryFileCount = removedTemporaryFileCount,
            interruptedRunIds = changedRuns.map { it.id }.sorted(),
            interruptedSegmentIds = changedSegments.map { it.id }.sorted(),
            invalidReadySegmentIds = invalidReadySegmentIds.sorted(),
            staleProvenanceSegmentIds = staleProvenanceSegmentIds.sorted(),
            staleGenerationKeySegmentIds = staleGenerationKeySegmentIds.sorted(),
        )
    }

    private fun hasValidArtifact(segment: AudioSegmentEntity): Boolean {
        val path = segment.audioPath?.let(::File) ?: return false
        val canonical = path.canonicalFile
        val readyRoot = storage.readyAudioDirectory.canonicalFile.toPath()
        if (!canonical.toPath().startsWith(readyRoot) || !canonical.isFile) return false
        if (segment.sampleRate != 24_000 || segment.channels != 1) return false
        if (segment.sizeBytes != null && canonical.length() != segment.sizeBytes) return false
        val expectedSha256 = segment.audioSha256 ?: return false
        return runCatching { artifactStore.sha256(canonical) == expectedSha256.lowercase() }.getOrDefault(false)
    }

    private fun hasCurrentProvenance(
        segment: AudioSegmentEntity,
        activeModelPackage: ModelPackageEntity?,
        runsById: Map<String, GenerationRunEntity>,
    ): Boolean {
        val model = activeModelPackage ?: return false
        val run = segment.generationRunId?.let(runsById::get) ?: return false
        return segment.modelPackageId == model.id &&
            segment.modelPackageSha256.equals(model.packageSha256, ignoreCase = true) &&
            segment.voiceSha256.equals(model.voiceSha256, ignoreCase = true) &&
            segment.preprocessingVersion == model.preprocessingVersion &&
            segment.pronunciationVersion == model.pronunciationVersion &&
            run.modelPackageId == model.id &&
            run.preprocessingVersion == model.preprocessingVersion &&
            run.pronunciationVersion == model.pronunciationVersion &&
            segment.inferenceSettingsHash == run.inferenceSettingsHash &&
            segment.audioProcessingVersion == run.audioProcessingVersion
    }

    private companion object {
        const val DEFAULT_TEMPORARY_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
