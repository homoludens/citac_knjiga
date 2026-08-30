package com.homoludens.citacknjiga.document.pdf

import android.content.ContentResolver
import android.net.Uri
import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

public class ContentResolverPdfSourceReader(
    private val contentResolver: ContentResolver,
) : PdfSourceReader {
    override fun open(sourceUri: String): InputStream? = contentResolver.openInputStream(Uri.parse(sourceUri))
}

/** Copies the SAF stream once into an owner directory; the URI is never used after this call. */
public class SafPdfSourceRepository(
    private val sourceReader: PdfSourceReader,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val projectIndex: PdfProjectIndex,
    private val limits: com.homoludens.citacknjiga.core.document.PdfImportLimits =
        com.homoludens.citacknjiga.core.document.PdfImportLimits.Production,
    private val projectIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    public fun stageSelected(uri: Uri): PdfStageResult = stageSource(uri.toString())

    public fun stageSource(sourceUri: String, checkActive: () -> Unit = {}): PdfStageResult {
        val projectId = projectIdFactory()
        val ownerId = "pdf-$projectId"
        val destination = try {
            storage.temporaryFile(ownerId, "source.pdf")
        } catch (_: IllegalArgumentException) {
            return PdfStageResult.Failed(PdfStageError.COPY_FAILED)
        }
        val staged = try {
            val input = sourceReader.open(sourceUri) ?: return PdfStageResult.Failed(PdfStageError.SOURCE_UNAVAILABLE)
            input.use { stream -> copyBounded(stream, destination, checkActive) }
        } catch (failure: CancellationException) {
            destination.delete()
            throw failure
        } catch (failure: SourceTooLarge) {
            destination.delete()
            return PdfStageResult.Failed(PdfStageError.SOURCE_TOO_LARGE)
        } catch (_: Exception) {
            destination.delete()
            return PdfStageResult.Failed(PdfStageError.COPY_FAILED)
        }
        if (!destination.inputStream().use { stream ->
                val header = ByteArray(5)
                var offset = 0
                while (offset < header.size) {
                    val read = stream.read(header, offset, header.size - offset)
                    if (read < 0) break
                    offset += read
                }
                offset == header.size && header.contentEquals("%PDF-".toByteArray())
            }) {
            destination.delete()
            return PdfStageResult.Failed(PdfStageError.INVALID_FORMAT)
        }
        val existing = runCatching { projectIndex.findByFingerprint(staged.sha256) }.getOrNull()
        if (existing != null) {
            destination.delete()
            return PdfStageResult.Duplicate(existing)
        }
        return PdfStageResult.Staged(
            StagedPdfSource(projectId, sourceUri, staged.sha256, destination, staged.sizeBytes),
        )
    }

    public fun publishStaged(source: StagedPdfSource): PdfPublishResult {
        val expected = runCatching {
            storage.temporaryFile("pdf-${source.projectId}", "source.pdf").canonicalFile
        }.getOrNull()
        val actual = source.sourceFile.canonicalFile
        if (expected == null || actual != expected || !actual.isFile || actual.length() != source.sizeBytes) {
            discardStaged(source)
            return PdfPublishResult.Failed(sourceChangedDiagnostic())
        }
        val destination = runCatching { storage.sourcePdf(source.projectId) }.getOrNull()
        if (destination == null || destination.exists()) {
            discardStaged(source)
            return PdfPublishResult.Failed(acceptanceDiagnostic())
        }
        return try {
            val published = artifactStore.publish(
                ownerId = "pdf-${source.projectId}",
                destination = destination,
                writer = { output -> actual.inputStream().use { it.copyTo(output) } },
                validator = { file ->
                    require(file.length() == source.sizeBytes)
                    require(artifactStore.sha256(file) == source.fingerprint)
                },
            )
            PdfPublishResult.Published(
                ImportedPdfSource(source.projectId, source.sourceUri, source.fingerprint, published.file, published.sizeBytes),
            )
        } catch (_: Exception) {
            destination.delete()
            PdfPublishResult.Failed(acceptanceDiagnostic())
        } finally {
            discardStaged(source)
        }
    }

    public fun discardStaged(source: StagedPdfSource) {
        runCatching {
            val expected = storage.temporaryFile("pdf-${source.projectId}", "source.pdf").canonicalFile
            if (source.sourceFile.canonicalFile == expected) expected.delete()
        }
    }

    public fun verifyCurrent(source: StagedPdfSource): Boolean {
        val expected = runCatching {
            storage.temporaryFile("pdf-${source.projectId}", "source.pdf").canonicalFile
        }.getOrNull() ?: return false
        val actual = source.sourceFile.canonicalFile
        return actual == expected && actual.isFile && actual.length() == source.sizeBytes &&
            runCatching { artifactStore.sha256(actual) == source.fingerprint }.getOrDefault(false)
    }

    private fun copyBounded(input: InputStream, destination: File, checkActive: () -> Unit): StagedArtifact {
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        try {
            FileOutputStream(destination).use { file ->
                BufferedOutputStream(file).use { output ->
                    while (true) {
                        checkActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        if (count > limits.maxSourceBytes) throw SourceTooLarge()
                    }
                    output.flush()
                    file.fd.sync()
                }
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
        return StagedArtifact(count, digest.digest().toHex())
    }

    private fun sourceChangedDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.SOURCE_CHANGED,
        message = "The staged PDF changed before inspection.",
        action = "Select the PDF again.",
    )

    private fun acceptanceDiagnostic() = ImportDiagnostic(
        ImportDiagnosticCode.ACCEPTANCE_FAILED,
        message = "The PDF import could not be safely completed.",
        action = "Select the PDF again and retry.",
    )

    private class SourceTooLarge : Exception(null, null, false, false)

    private data class StagedArtifact(val sizeBytes: Long, val sha256: String)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
