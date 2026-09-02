package com.homoludens.citacknjiga.core.storage

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import java.io.File

/** Files owned by one imported project; URI references and export destinations are excluded. */
public data class ProjectArtifactInventory(
    public val sourceFiles: Set<File> = emptySet(),
    public val canonicalTextFiles: Set<File> = emptySet(),
    public val coverFiles: Set<File> = emptySet(),
    public val readyAudioFiles: Set<File> = emptySet(),
    public val diagnosticFiles: Set<File> = emptySet(),
    public val temporaryFiles: Set<File> = emptySet(),
) {
    public val allFiles: Set<File> get() = sourceFiles + canonicalTextFiles + coverFiles +
        readyAudioFiles + diagnosticFiles + temporaryFiles
}

/** Resolves the complete set of project-owned files without treating SAF URIs as paths. */
public fun AppPrivateStorage.projectArtifactInventory(
    project: BookProjectEntity,
    chapters: Collection<ChapterEntity> = emptyList(),
    audioSegments: Collection<AudioSegmentEntity> = emptyList(),
    generationRuns: Collection<GenerationRunEntity> = emptyList(),
    additionalTemporaryFiles: Collection<File> = emptyList(),
): ProjectArtifactInventory {
    val projectDirectoryName = sourceDocument(project.id).parentFile!!.name
    val projectChapters = chapters.filter { it.bookProjectId == project.id }
    val chapterIds = projectChapters.mapTo(mutableSetOf(), ChapterEntity::id)
    val projectSegments = audioSegments.filter { it.chapterId in chapterIds }
    val projectRunIds = generationRuns.filter { it.bookProjectId == project.id }.map { it.id }

    val temporaryOwners = buildSet {
        add("pdf-${project.id}")
        add("epub-${project.id}")
        add("canonical-${project.id}")
        add("cover-${project.id}")
        projectRunIds.forEach { runId ->
            projectSegments.forEach { segment -> add("generation-$runId-${segment.id}") }
        }
        projectSegments.forEach { segment -> add("aac-${segment.id}") }
    }

    return ProjectArtifactInventory(
        sourceFiles = filesBelow(File(sourceDocumentsDirectory, projectDirectoryName)) +
            listOfNotNull(project.sourcePath?.takeIf(String::isNotBlank)?.let(::File)),
        canonicalTextFiles = filesBelow(File(canonicalTextDirectory, projectDirectoryName)) +
            projectChapters.mapNotNull { chapter ->
                chapter.canonicalMarkdownPath?.takeIf(String::isNotBlank)?.let(::File)
            },
        coverFiles = filesBelow(File(coversDirectory, projectDirectoryName)) +
            listOfNotNull(project.coverPath?.takeIf(String::isNotBlank)?.let(::File)),
        readyAudioFiles = filesBelow(File(readyAudioDirectory, projectDirectoryName)) +
            projectSegments.mapNotNull { segment -> segment.audioPath?.takeIf(String::isNotBlank)?.let(::File) },
        diagnosticFiles = filesBelow(File(diagnosticsDirectory, projectDirectoryName)),
        temporaryFiles = temporaryOwners.flatMapTo(mutableSetOf()) { ownerId ->
            filesBelow(temporaryFile(ownerId, ".inventory").parentFile!!)
        } + additionalTemporaryFiles,
    )
}

/** Validates all targets before deleting any file, so cleanup fails closed. */
public class ProjectArtifactCleanupPolicy(
    private val storage: AppPrivateStorage,
) {
    /** Canonical, deduplicated targets. SAF source/export URIs never enter this set. */
    public fun targets(inventory: ProjectArtifactInventory): Set<File> =
        inventory.allFiles.map(storage::requireContained).toSet()

    public fun cleanup(inventory: ProjectArtifactInventory): ProjectArtifactCleanupResult {
        val targets = targets(inventory)
        val deleted = targets.filter { it.isFile && it.delete() }.toSet()
        return ProjectArtifactCleanupResult(targets, deleted)
    }
}

public data class ProjectArtifactCleanupResult(
    public val targetedFiles: Set<File>,
    public val deletedFiles: Set<File>,
)

private fun AppPrivateStorage.filesBelow(path: File): Set<File> {
    val directory = requireContained(path)
    if (!directory.isDirectory) return emptySet()
    return directory.walkTopDown().filter(File::isFile).map(File::getCanonicalFile).toSet()
}
