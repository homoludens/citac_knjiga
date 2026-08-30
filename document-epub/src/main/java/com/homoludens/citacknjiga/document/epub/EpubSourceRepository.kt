package com.homoludens.citacknjiga.document.epub

import android.content.ContentResolver
import android.net.Uri
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Opens a source selected through SAF without giving the importer provider ownership. */
public fun interface EpubSourceReader {
    public fun open(sourceUri: String): InputStream?
}

/** ContentResolver adapter for a URI returned by the system document picker. */
public class ContentResolverEpubSourceReader(
    private val contentResolver: ContentResolver,
) : EpubSourceReader {
    override fun open(sourceUri: String): InputStream? =
        contentResolver.openInputStream(Uri.parse(sourceUri))
}

public data class ImportedEpubSource(
    public val projectId: String,
    public val sourceUri: String,
    public val fingerprint: String,
    public val sourceFile: File,
    public val sizeBytes: Long,
)

/** A validated source kept private while the user reviews its import preview. */
public data class StagedEpubSource(
    public val projectId: String,
    public val sourceUri: String,
    public val fingerprint: String,
    public val sourceFile: File,
    public val sizeBytes: Long,
)

public data class ExistingEpubProject(
    public val projectId: String,
    public val sourceUri: String,
    public val fingerprint: String,
    public val sourceFile: File?,
)

public sealed interface EpubImportResult {
    public data class Imported(public val source: ImportedEpubSource) : EpubImportResult

    public data class Duplicate(public val existingProject: ExistingEpubProject) : EpubImportResult

    public data class Failed(
        public val error: EpubImportError,
        public val securityDiagnostic: EpubSecurityDiagnostic? = null,
    ) : EpubImportResult
}

public sealed interface EpubStageResult {
    public data class Staged(public val source: StagedEpubSource) : EpubStageResult

    public data class Duplicate(public val existingProject: ExistingEpubProject) : EpubStageResult

    public data class Failed(
        public val error: EpubImportError,
        public val securityDiagnostic: EpubSecurityDiagnostic? = null,
    ) : EpubStageResult
}

public enum class EpubImportError {
    SOURCE_UNAVAILABLE,
    COPY_FAILED,
    PUBLICATION_FAILED,
    INDEX_LOOKUP_FAILED,
    INDEX_WRITE_FAILED,
    SECURITY_VALIDATION_FAILED,
}

/** Durable lookup and record boundary used by the source import boundary. */
public interface EpubProjectIndex {
    public fun findByFingerprint(fingerprint: String): ExistingEpubProject?

    public fun recordImportedSource(source: ImportedEpubSource)

    public fun recordAcceptedDocument(
        source: ImportedEpubSource,
        document: EpubDocument,
        coverPath: String?,
        canonicalChapterPaths: Map<String, String>,
    ) {
        // Source-only indexes do not need to project the accepted document.
    }
}

/** Imports the validated source artifact; document parsing consumes its private copy separately. */
public interface EpubSourceRepository {
    public fun importSelected(uri: Uri): EpubImportResult

    public fun stageSelected(uri: Uri): EpubStageResult

    public fun stageSource(sourceUri: String): EpubStageResult

    public fun publishStaged(source: StagedEpubSource): EpubImportResult

    public fun discardStaged(source: StagedEpubSource)

    /** Completes the Room projection after the user accepts the parsed preview. */
    public fun recordAcceptedDocument(
        source: ImportedEpubSource,
        document: EpubDocument,
        canonicalChapterPaths: Map<String, String>,
    ) {
        // Preview-only implementations can keep their existing behavior.
    }
}

/**
 * Copies a selected SAF document to private temporary storage, fingerprints it, and publishes
 * a new project source only when its fingerprint is not already indexed.
 */
public class SafEpubSourceRepository(
    private val sourceReader: EpubSourceReader,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val projectIndex: EpubProjectIndex,
    private val projectIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val securityValidator: EpubSecurityValidator = EpubSecurityValidator(),
) : EpubSourceRepository {
    override fun importSelected(uri: Uri): EpubImportResult = importSource(uri.toString())

    /** String overload keeps the repository JVM-testable while production callers pass Uri. */
    public fun importSource(sourceUri: String): EpubImportResult {
        return when (val staged = stageSource(sourceUri)) {
            is EpubStageResult.Staged -> publishStaged(staged.source)
            is EpubStageResult.Duplicate -> EpubImportResult.Duplicate(staged.existingProject)
            is EpubStageResult.Failed -> EpubImportResult.Failed(staged.error, staged.securityDiagnostic)
        }
    }

    override fun stageSelected(uri: Uri): EpubStageResult = stageSource(uri.toString())

    /** String overload keeps the preview boundary JVM-testable while production callers pass Uri. */
    override fun stageSource(sourceUri: String): EpubStageResult {
        val projectId = projectIdFactory()
        val ownerId = "epub-$projectId"
        val staging = try {
            val input = sourceReader.open(sourceUri)
                ?: return EpubStageResult.Failed(EpubImportError.SOURCE_UNAVAILABLE)
            input.use { stream ->
                artifactStore.publish(
                    ownerId = ownerId,
                    destination = storage.temporaryFile(ownerId, "source.epub"),
                    writer = { output -> stream.copyTo(output) },
                )
            }
        } catch (_: Exception) {
            return EpubStageResult.Failed(EpubImportError.COPY_FAILED)
        }

        var retainStaging = false
        try {
            val security = securityValidator.validate(staging.file)
            if (security is EpubSecurityValidation.Rejected) {
                return EpubStageResult.Failed(
                    error = EpubImportError.SECURITY_VALIDATION_FAILED,
                    securityDiagnostic = security.diagnostic,
                )
            }
            val existing = try {
                projectIndex.findByFingerprint(staging.sha256)
            } catch (_: Exception) {
                return EpubStageResult.Failed(EpubImportError.INDEX_LOOKUP_FAILED)
            }
            if (existing != null) {
                return EpubStageResult.Duplicate(existing)
            }

            retainStaging = true
            return EpubStageResult.Staged(
                StagedEpubSource(
                    projectId = projectId,
                    sourceUri = sourceUri,
                    fingerprint = staging.sha256,
                    sourceFile = staging.file,
                    sizeBytes = staging.sizeBytes,
                ),
            )
        } catch (_: Exception) {
            return EpubStageResult.Failed(EpubImportError.SECURITY_VALIDATION_FAILED)
        } finally {
            if (!retainStaging) staging.file.delete()
        }
    }

    override fun publishStaged(source: StagedEpubSource): EpubImportResult {
        val expectedStaging = try {
            storage.temporaryFile("epub-${source.projectId}", "source.epub").canonicalFile
        } catch (_: IllegalArgumentException) {
            return EpubImportResult.Failed(EpubImportError.PUBLICATION_FAILED)
        }
        val staging = source.sourceFile.canonicalFile
        if (staging != expectedStaging || !staging.isFile) {
            discardStaged(source)
            return EpubImportResult.Failed(EpubImportError.SOURCE_UNAVAILABLE)
        }
        val destination = try {
            storage.sourceDocument(source.projectId)
        } catch (_: IllegalArgumentException) {
            discardStaged(source)
            return EpubImportResult.Failed(EpubImportError.PUBLICATION_FAILED)
        }
        if (destination.exists()) {
            discardStaged(source)
            return EpubImportResult.Failed(EpubImportError.PUBLICATION_FAILED)
        }

        try {
            val published = try {
                artifactStore.publish(
                    ownerId = "epub-${source.projectId}",
                    destination = destination,
                    writer = { output -> staging.inputStream().use { it.copyTo(output) } },
                    validator = { file ->
                        require(file.length() == source.sizeBytes) { "Published EPUB size changed" }
                        require(artifactStore.sha256(file) == source.fingerprint) {
                            "Published EPUB fingerprint changed"
                        }
                    },
                )
            } catch (_: Exception) {
                return EpubImportResult.Failed(EpubImportError.PUBLICATION_FAILED)
            }
            val importedSource = ImportedEpubSource(
                projectId = source.projectId,
                sourceUri = source.sourceUri,
                fingerprint = source.fingerprint,
                sourceFile = published.file,
                sizeBytes = published.sizeBytes,
            )
            try {
                projectIndex.recordImportedSource(importedSource)
            } catch (_: Exception) {
                published.file.delete()
                return EpubImportResult.Failed(EpubImportError.INDEX_WRITE_FAILED)
            }
            return EpubImportResult.Imported(importedSource)
        } finally {
            discardStaged(source)
        }
    }

    override fun discardStaged(source: StagedEpubSource) {
        runCatching {
            val expected = storage.temporaryFile("epub-${source.projectId}", "source.epub").canonicalFile
            if (source.sourceFile.canonicalFile == expected) expected.delete()
        }
    }

    override fun recordAcceptedDocument(
        source: ImportedEpubSource,
        document: EpubDocument,
        canonicalChapterPaths: Map<String, String>,
    ) {
        val coverPath = document.cover?.let { cover ->
            artifactStore.publish(
                ownerId = "cover-${source.projectId}",
                destination = storage.coverImage(source.projectId),
                writer = { output -> output.write(cover.bytes) },
                validator = { file -> require(file.length() == cover.bytes.size.toLong()) },
            ).file.path
        }
        projectIndex.recordAcceptedDocument(source, document, coverPath, canonicalChapterPaths)
    }
}

/** Room-backed source fingerprint index for the import boundary. */
public class RoomEpubProjectIndex(
    private val dao: AudiobookDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : EpubProjectIndex {
    override fun findByFingerprint(fingerprint: String): ExistingEpubProject? =
        dao.findProjectBySourceFingerprint(fingerprint)?.let { project ->
            ExistingEpubProject(
                projectId = project.id,
                sourceUri = project.sourceUri,
                fingerprint = project.sourceFingerprint,
                sourceFile = project.sourcePath?.let(::File),
            )
        }

    override fun recordImportedSource(source: ImportedEpubSource) {
        val now = clock()
        dao.insertProject(
            BookProjectEntity(
                id = source.projectId,
                title = "Imported EPUB",
                sourceUri = source.sourceUri,
                sourceFingerprint = source.fingerprint,
                sourcePath = source.sourceFile.path,
                status = BookProjectStatus.IMPORTING,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override fun recordAcceptedDocument(
        source: ImportedEpubSource,
        document: EpubDocument,
        coverPath: String?,
        canonicalChapterPaths: Map<String, String>,
    ) {
        val now = clock()
        val projection = document.toRoomProjection(source, now)
        val current = dao.findProjectById(source.projectId)
        dao.updateProject(
            (current ?: projection.project).copy(
                title = projection.project.title,
                author = projection.project.author,
                language = projection.project.language,
                coverPath = coverPath,
                status = BookProjectStatus.READY,
                updatedAt = now,
            ),
        )
        projection.chapters.forEach { chapter ->
            dao.insertChapter(chapter.copy(canonicalMarkdownPath = canonicalChapterPaths[chapter.id]))
        }
        projection.narrationBlocks.forEach(dao::insertNarrationBlock)
    }
}
