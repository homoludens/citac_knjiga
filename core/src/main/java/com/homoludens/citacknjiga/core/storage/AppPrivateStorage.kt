package com.homoludens.citacknjiga.core.storage

import java.io.File

/** Fixed app-private areas and the paths owned by each area. */
public enum class AppPrivateDirectory(public val directoryName: String) {
    SOURCE_DOCUMENTS("sources"),
    MODEL_PACKAGES("model-packages"),
    CANONICAL_TEXT("canonical-text"),
    COVERS("covers"),
    TEMPORARY("temporary"),
    READY_AUDIO("ready-audio"),
    DIAGNOSTICS("diagnostics"),
    TYPED_PROOF("typed-proof"),
    BENCHMARK_REPORTS("benchmark-reports"),
    PARITY_INPUT("parity-input"),
    PARITY_REPORTS("parity-reports"),
}

/**
 * Resolves all durable and diagnostic paths below Android's private files directory.
 * This class only describes paths; callers own directory creation and publication semantics.
 */
public class AppPrivateStorage(filesDir: File) {
    public val rootDirectory: File = filesDir.canonicalFile

    public val sourceDocumentsDirectory: File = directory(AppPrivateDirectory.SOURCE_DOCUMENTS)
    public val modelPackagesDirectory: File = directory(AppPrivateDirectory.MODEL_PACKAGES)
    public val canonicalTextDirectory: File = directory(AppPrivateDirectory.CANONICAL_TEXT)
    public val coversDirectory: File = directory(AppPrivateDirectory.COVERS)
    public val temporaryDirectory: File = directory(AppPrivateDirectory.TEMPORARY)
    public val readyAudioDirectory: File = directory(AppPrivateDirectory.READY_AUDIO)
    public val diagnosticsDirectory: File = directory(AppPrivateDirectory.DIAGNOSTICS)
    public val typedProofDirectory: File = directory(AppPrivateDirectory.TYPED_PROOF)
    public val benchmarkReportsDirectory: File = directory(AppPrivateDirectory.BENCHMARK_REPORTS)
    public val parityInputDirectory: File = directory(AppPrivateDirectory.PARITY_INPUT)
    public val parityReportsDirectory: File = directory(AppPrivateDirectory.PARITY_REPORTS)

    public val activeModelPackage: File = child(modelPackagesDirectory, "active.zip")
    public val lastValidModelPackage: File = child(modelPackagesDirectory, "last-valid.zip")

    public fun sourceDocument(projectId: String): File =
        child(sourceDocumentsDirectory, projectId, "source.epub")

    public fun sourcePdf(projectId: String): File =
        child(sourceDocumentsDirectory, projectId, "source.pdf")

    public fun canonicalChapterText(projectId: String, chapterId: String): File =
        child(canonicalTextDirectory, projectId, "$chapterId.md")

    public fun coverImage(projectId: String): File =
        child(coversDirectory, projectId, "cover")

    public fun importWarnings(projectId: String): File =
        child(diagnosticsDirectory, projectId, "import-warnings.json")

    public fun modelPackageFile(name: String): File = child(modelPackagesDirectory, name)

    public fun temporaryFile(ownerId: String, name: String): File =
        child(temporaryDirectory, ownerId, name)

    public fun readySegmentAudio(
        projectId: String,
        chapterId: String,
        segmentId: String,
        fileName: String = "$segmentId.m4a",
    ): File = child(readyAudioDirectory, projectId, chapterId, fileName)

    public fun readySegmentWav(projectId: String, chapterId: String, segmentId: String): File =
        child(readyAudioDirectory, projectId, chapterId, "$segmentId.wav")

    public fun readyChapterWav(projectId: String, chapterId: String): File =
        child(readyAudioDirectory, projectId, chapterId, "chapter.wav")

    public fun diagnosticFile(name: String): File = child(diagnosticsDirectory, name)

    private fun directory(area: AppPrivateDirectory): File = child(rootDirectory, area.directoryName)

    private fun child(parent: File, vararg components: String): File {
        require(components.all(::isSafeComponent)) { "Private storage path component is unsafe" }
        val candidate = components.fold(parent) { current, component -> File(current, component) }.canonicalFile
        return requireContained(candidate)
    }

    /** Returns a canonical path below this app-private root, rejecting the root itself. */
    public fun requireContained(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(rootDirectory.toPath())) {
            "Path escapes the app-private root"
        }
        require(canonical != rootDirectory) { "Path must be below the app-private root" }
        return canonical
    }

    private companion object {
        fun isSafeComponent(value: String): Boolean =
            value.isNotEmpty() && value != "." && value != ".." &&
                '/' !in value && '\\' !in value && '\u0000' !in value
    }
}
