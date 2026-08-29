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

public data class ExistingEpubProject(
    public val projectId: String,
    public val sourceUri: String,
    public val fingerprint: String,
    public val sourceFile: File?,
)

public sealed interface EpubImportResult {
    public data class Imported(public val source: ImportedEpubSource) : EpubImportResult

    public data class Duplicate(public val existingProject: ExistingEpubProject) : EpubImportResult

    public data class Failed(public val error: EpubImportError) : EpubImportResult
}

public enum class EpubImportError {
    SOURCE_UNAVAILABLE,
    COPY_FAILED,
    PUBLICATION_FAILED,
    INDEX_LOOKUP_FAILED,
    INDEX_WRITE_FAILED,
}

/** Durable lookup and record boundary used before a future EPUB parser owns the project. */
public interface EpubProjectIndex {
    public fun findByFingerprint(fingerprint: String): ExistingEpubProject?

    public fun recordImportedSource(source: ImportedEpubSource)
}

/** Imports only the source artifact; EPUB archive parsing is intentionally a later task. */
public interface EpubSourceRepository {
    public fun importSelected(uri: Uri): EpubImportResult
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
) : EpubSourceRepository {
    override fun importSelected(uri: Uri): EpubImportResult = importSource(uri.toString())

    /** String overload keeps the repository JVM-testable while production callers pass Uri. */
    public fun importSource(sourceUri: String): EpubImportResult {
        val projectId = projectIdFactory()
        val destination = try {
            storage.sourceDocument(projectId)
        } catch (_: IllegalArgumentException) {
            return EpubImportResult.Failed(EpubImportError.PUBLICATION_FAILED)
        }
        if (destination.exists()) {
            return EpubImportResult.Failed(EpubImportError.PUBLICATION_FAILED)
        }

        val ownerId = "epub-$projectId"
        val staging = try {
            val input = sourceReader.open(sourceUri)
                ?: return EpubImportResult.Failed(EpubImportError.SOURCE_UNAVAILABLE)
            input.use { stream ->
                artifactStore.publish(
                    ownerId = ownerId,
                    destination = storage.temporaryFile(ownerId, "source.epub"),
                    writer = { output -> stream.copyTo(output) },
                )
            }
        } catch (_: Exception) {
            return EpubImportResult.Failed(EpubImportError.COPY_FAILED)
        }

        try {
            val existing = try {
                projectIndex.findByFingerprint(staging.sha256)
            } catch (_: Exception) {
                return EpubImportResult.Failed(EpubImportError.INDEX_LOOKUP_FAILED)
            }
            if (existing != null) {
                return EpubImportResult.Duplicate(existing)
            }

            val published = try {
                artifactStore.publish(
                    ownerId = ownerId,
                    destination = destination,
                    writer = { output -> staging.file.inputStream().use { it.copyTo(output) } },
                    validator = { file ->
                        require(file.length() == staging.sizeBytes) { "Published EPUB size changed" }
                        require(artifactStore.sha256(file) == staging.sha256) {
                            "Published EPUB fingerprint changed"
                        }
                    },
                )
            } catch (_: Exception) {
                return EpubImportResult.Failed(EpubImportError.PUBLICATION_FAILED)
            }
            val source = ImportedEpubSource(
                projectId = projectId,
                sourceUri = sourceUri,
                fingerprint = staging.sha256,
                sourceFile = published.file,
                sizeBytes = published.sizeBytes,
            )
            try {
                projectIndex.recordImportedSource(source)
            } catch (_: Exception) {
                published.file.delete()
                return EpubImportResult.Failed(EpubImportError.INDEX_WRITE_FAILED)
            }
            return EpubImportResult.Imported(source)
        } finally {
            staging.file.delete()
        }
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
}
