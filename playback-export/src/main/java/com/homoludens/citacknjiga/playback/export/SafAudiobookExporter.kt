package com.homoludens.citacknjiga.playback.export

import android.net.Uri
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID

public val DEFAULT_ATTRIBUTION_REFS: List<ExportAttributionReference> = listOf(
    ExportAttributionReference(
        id = "dragana-dataset",
        subject = "Serbian Common Voice Style TTS Dataset; speaker Dragana; creator Darko Milosevic",
        sourceUrl = "https://huggingface.co/datasets/daremc86/serbian_common_voice",
        licenseId = "cc-by-4.0",
        required = true,
    ),
    ExportAttributionReference(
        id = "juzne-vesti-corpus",
        subject = "JuzneVesti-SR corpus; Peter Rupnik and Nikola Ljubesic; CLARIN.SI",
        sourceUrl = "https://www.clarin.si/repository/xmlui/handle/11356/1679",
        licenseId = "cc-by-sa-4.0",
        required = true,
    ),
)

public data class ExportChapterInput(
    public val chapter: ChapterEntity,
    public val segments: List<AudioSegmentEntity>,
)

public data class ExportRequest(
    public val project: BookProjectEntity,
    public val chapters: List<ExportChapterInput>,
    public val attributionRefs: List<ExportAttributionReference> = DEFAULT_ATTRIBUTION_REFS,
    public val audioFormat: ExportAudioFormat = ExportAudioFormat.AUTO,
)

public data class PlannedExportFile(
    public val name: String,
    public val baseName: String,
    public val mimeType: String,
    public val sourceFiles: List<File>,
    public val sourceSegments: List<AudioSegmentEntity> = emptyList(),
    public val assembledDurationMs: Long = 0L,
)

public class ExportPlan internal constructor(
    public val request: ExportRequest,
    public val files: List<PlannedExportFile>,
    public val manifest: ExportManifest,
    public val collisions: List<String>,
    public val overwriteExisting: Boolean,
    internal val destination: SafDocumentTree,
    private val exporter: SafAudiobookExporter,
    public val jobId: String? = null,
) {
    public val hasCollisions: Boolean get() = collisions.isNotEmpty()

    /** Rebuilds the same plan with explicit replacement enabled. */
    public fun withOverwriteConfirmation(): ExportPlan {
        val rebuilt = exporter.plan(destination, request, overwriteExisting = true)
        return jobId?.let(rebuilt::withJobId) ?: rebuilt
    }

    internal fun withJobId(value: String): ExportPlan = ExportPlan(
        request,
        files,
        manifest,
        collisions,
        overwriteExisting,
        destination,
        exporter,
        value,
    )

    internal fun withPersistedNames(
        chapterNames: Map<String, String>,
        manifestName: String?,
        coverName: String?,
        overwriteExisting: Boolean,
    ): ExportPlan {
        val renamed = files.map { planned ->
            val chapterId = planned.sourceSegments.firstOrNull()?.chapterId
            val name = chapterId?.let(chapterNames::get) ?:
                if (planned.mimeType == "application/json") manifestName
                else if (planned.mimeType.startsWith("image/")) coverName else null
            if (name == null) planned else planned.copy(name = name)
        }
        val paths = chapterNames
        val renamedManifest = manifest.copy(
            chapters = manifest.chapters.map { chapter ->
                chapter.copy(files = chapter.files.map { file ->
                    file.copy(path = paths[file.id.removeSuffix("-file")] ?: file.path)
                })
            },
        )
        return ExportPlan(request, renamed, renamedManifest, collisions, overwriteExisting, destination, exporter, jobId)
    }
}

public data class ExportedAudiobook(
    public val manifestUri: Uri,
    public val writtenNames: List<String>,
)

public data class ExportedFileVerification(
    public val uri: Uri,
    public val sizeBytes: Long,
    public val sha256: String,
)

public interface ExportProgressListener {
    public fun onTemporaryFile(planned: PlannedExportFile, uri: Uri) {}

    public fun onVerifiedFile(planned: PlannedExportFile, verification: ExportedFileVerification) {}
}

public class DestinationUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

public class ExportCancelledException : IllegalStateException("Export was cancelled")

public class IncompleteExportException(
    public val missingChapterIds: List<String>,
    public val missingSegmentIds: List<String>,
) : IllegalArgumentException(
    "Export requires every selected chapter and segment to be ready; " +
        "missing chapters=${missingChapterIds.joinToString()} segments=${missingSegmentIds.joinToString()}",
)

/** Writes verified private artifacts to a user-selected SAF tree without assuming paths or rename. */
public class SafAudiobookExporter(
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore = AtomicArtifactStore(storage),
    private val chapterAssembler: ChapterAudioAssembler = AndroidChapterAudioAssembler(),
    private val sourceValidator: (File, AudioSegmentEntity) -> Unit = { file, segment ->
        require(file.isFile) { "Verified audio file is missing" }
        require(segment.sizeBytes != null && file.length() == segment.sizeBytes) { "Verified audio size does not match Room" }
        require(!segment.audioSha256.isNullOrBlank() && artifactStore.sha256(file) == segment.audioSha256) {
            "Verified audio checksum does not match Room"
        }
    },
) {
    public fun plan(
        destination: SafDocumentTree,
        request: ExportRequest,
        overwriteExisting: Boolean = false,
    ): ExportPlan {
        require(request.chapters.isNotEmpty()) { "At least one chapter must be selected" }
        val chapters = request.chapters.sortedBy { it.chapter.ordinal }.map { input ->
            input.copy(chapter = input.chapter.copy(title = input.chapter.title.ifBlank { "Chapter ${input.chapter.ordinal + 1}" }))
        }
        val exportRequest = request.copy(
            project = request.project.copy(
                title = request.project.title.ifBlank { "Untitled audiobook" },
                author = request.project.author?.takeIf(String::isNotBlank),
                language = request.project.language.ifBlank { "sr" },
                coverPath = request.project.coverPath?.takeIf(String::isNotBlank),
            ),
            chapters = chapters,
        )
        val expectedOrdinals = chapters.indices.toList()
        require(chapters.map { it.chapter.ordinal } == expectedOrdinals) {
            "Exported chapter ordinals must be contiguous and start at zero"
        }
        val existing = destination.listChildren()
            .associateBy { it.name.lowercase() }
        val occupied = existing.keys.toMutableSet()
        val collisions = mutableListOf<String>()
        val files = mutableListOf<PlannedExportFile>()
        val chapterFiles = chapters.map { input ->
            val segments = input.segments.sortedWith(compareBy<AudioSegmentEntity> { it.sequence }.thenBy { it.id })
            val missing = segments.filter { it.status != AudioSegmentStatus.READY || it.audioPath.isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw IncompleteExportException(listOf(input.chapter.id), missing.map { it.id })
            }
            if (segments.isEmpty()) throw IncompleteExportException(listOf(input.chapter.id), emptyList())
            val sources = segments.map(::verifiedSource)
            require(sources.all { it.extension.lowercase() in setOf("m4a", "mp4", "wav") }) {
                "Unsupported ready audio extension"
            }
            val temporary = storage.temporaryFile("export", "chapter-${UUID.randomUUID()}.audio")
            val assembled = if (chapterAssembler is DurationAwareChapterAudioAssembler) {
                chapterAssembler.assemble(
                    sources,
                    temporary,
                    exportRequest.audioFormat,
                    segments.map { it.durationMs ?: error("Ready segment duration is missing") },
                )
            } else {
                chapterAssembler.assemble(sources, temporary, exportRequest.audioFormat)
            }
            val extension = when (assembled.format) {
                ExportAudioFormat.WAV -> "wav"
                ExportAudioFormat.M4A -> "m4a"
                ExportAudioFormat.AUTO -> error("Assembler must resolve the export format")
            }
            val baseName = ExportFileNaming.chapterFileName(input.chapter.ordinal, input.chapter.title, extension)
            val name = if (overwriteExisting) baseName else {
                val safe = ExportFileNaming.collisionSafeName(baseName, occupied)
                if (safe != baseName) collisions += baseName
                safe
            }
            if (overwriteExisting && baseName.lowercase() in occupied) collisions += baseName
            occupied += name.lowercase()
            PlannedExportFile(
                name = name,
                baseName = baseName,
                mimeType = if (extension == "wav") "audio/wav" else "audio/mp4",
                sourceFiles = listOf(assembled.file),
                sourceSegments = segments,
                assembledDurationMs = assembled.durationMs,
            )
        }
        files += chapterFiles
        exportRequest.project.coverPath?.let { coverPath ->
            val cover = verifiedCover(exportRequest.project.id, coverPath)
            val baseName = "cover.${coverExtension(cover)}"
            val name = if (overwriteExisting) baseName else {
                val safe = ExportFileNaming.collisionSafeName(baseName, occupied)
                if (safe != baseName) collisions += baseName
                safe
            }
            if (overwriteExisting && baseName.lowercase() in occupied) collisions += baseName
            occupied += name.lowercase()
            files += PlannedExportFile(name, baseName, coverMime(cover), listOf(cover))
        }
        val manifestBaseName = "manifest.json"
        val manifestName = if (overwriteExisting) manifestBaseName else {
            val safe = ExportFileNaming.collisionSafeName(manifestBaseName, occupied)
            if (safe != manifestBaseName) collisions += manifestBaseName
            safe
        }
        if (overwriteExisting && manifestBaseName.lowercase() in occupied) collisions += manifestBaseName
        val manifest = ExportManifestFactory.fromRoom(
            project = exportRequest.project,
            chapters = chapters.map { it.chapter },
            filesByChapter = chapters.zip(chapterFiles).associate { (input, planned) ->
                val assembled = planned.sourceFiles.single()
                input.chapter.id to listOf(
                    ExportManifestFile.fromReadySegments(
                        chapterId = input.chapter.id,
                        segments = planned.sourceSegments,
                        path = planned.name,
                        mediaType = planned.mimeType,
                        sha256 = artifactStore.sha256(assembled),
                        sizeBytes = assembled.length(),
                        durationMs = planned.assembledDurationMs,
                    ),
                )
            },
            attributionRefs = exportRequest.attributionRefs.ifEmpty { DEFAULT_ATTRIBUTION_REFS },
        )
        ExportManifestValidator.validate(manifest)
        val finalFiles = files + PlannedExportFile(manifestName, manifestBaseName, "application/json", emptyList())
        return ExportPlan(exportRequest, finalFiles, manifest, collisions.distinct(), overwriteExisting, destination, this)
    }

    public fun export(
        plan: ExportPlan,
        overwriteConfirmed: Boolean = false,
        skipNames: Set<String> = emptySet(),
        temporaryUris: Map<String, Uri> = emptyMap(),
        listener: ExportProgressListener? = null,
    ): ExportedAudiobook {
        require(!plan.overwriteExisting || overwriteConfirmed) {
            "Replacing an existing export requires explicit confirmation"
        }
        val written = mutableListOf<String>()
        var manifestUri: Uri? = null
        plan.files.forEach { planned ->
            check(!Thread.currentThread().isInterrupted) { throw ExportCancelledException() }
            val verification = if (planned.name in skipNames) {
                val target = findExisting(plan.destination, planned.name)
                val expected = expectedBytes(plan, planned)
                val existing = target?.let { runCatching { verifyProvider(plan.destination, it.uri, expected, planned.name) }.getOrNull() }
                existing ?: writeProviderSafe(plan, planned, temporaryUris[planned.name], listener)
            } else {
                writeProviderSafe(plan, planned, temporaryUris[planned.name], listener)
            }
            written += planned.name
            if (planned.mimeType == "application/json") manifestUri = verification.uri
            if (planned.sourceSegments.isNotEmpty()) planned.sourceFiles.forEach(File::delete)
        }
        return ExportedAudiobook(requireNotNull(manifestUri), written)
    }

    private fun verifiedSource(segment: AudioSegmentEntity): File {
        require(segment.status == AudioSegmentStatus.READY) { "Only READY audio can be exported" }
        val file = File(requireNotNull(segment.audioPath)).canonicalFile
        require(file.toPath().startsWith(storage.readyAudioDirectory.canonicalFile.toPath())) {
            "Export source must remain below private ready audio storage"
        }
        sourceValidator(file, segment)
        return file
    }

    private fun verifiedCover(projectId: String, path: String): File {
        val file = File(path).canonicalFile
        val expected = storage.coverImage(projectId).canonicalFile
        require(file == expected && file.isFile) { "Export cover is not the project's private cover" }
        return file
    }

    private fun writeProviderSafe(
        plan: ExportPlan,
        planned: PlannedExportFile,
        existingTemporaryUri: Uri?,
        listener: ExportProgressListener?,
    ): ExportedFileVerification {
        var temporaryUri = existingTemporaryUri ?: createTemporary(plan, planned)
        listener?.onTemporaryFile(planned, temporaryUri)
        try {
            val output = try {
                plan.destination.openForWrite(temporaryUri)
                    ?: throw DestinationUnavailableException("Could not open temporary export file for ${planned.name}")
            } catch (failure: Throwable) {
                if (existingTemporaryUri == null) throw failure
                temporaryUri = createTemporary(plan, planned)
                listener?.onTemporaryFile(planned, temporaryUri)
                plan.destination.openForWrite(temporaryUri)
                    ?: throw DestinationUnavailableException("Could not open replacement temporary export file for ${planned.name}")
            }
            output.use { stream ->
                if (planned.mimeType == "application/json") {
                    stream.write(ExportManifestCodec.encode(plan.manifest).toByteArray(Charsets.UTF_8))
                } else {
                    planned.sourceFiles.single().inputStream().use { input -> input.copyTo(stream) }
                }
                stream.flush()
            }
            val expected = expectedBytes(plan, planned)
            verifyProvider(plan.destination, temporaryUri, expected, planned.name)
            val current = findExisting(plan.destination, planned.name)
            if (current != null) {
                require(plan.overwriteExisting && !current.isDirectory) {
                    "Export filename collision requires a new name or explicit overwrite"
                }
                if (!plan.destination.delete(current.uri)) {
                    throw DestinationUnavailableException("Could not replace existing export file ${planned.name}")
                }
            }
            if (!plan.destination.capabilities.supportsDocumentRename) {
                throw DestinationUnavailableException(
                    "Provider cannot safely finalize ${planned.name}; choose a destination that supports document rename",
                )
            }
            val finalUri = plan.destination.rename(temporaryUri, planned.name)
                ?: throw DestinationUnavailableException("Provider could not finalize ${planned.name}")
            val verified = verifyProvider(plan.destination, finalUri, expected, planned.name)
                .copy(uri = finalUri)
            listener?.onVerifiedFile(planned, verified)
            return verified
        } catch (failure: DestinationUnavailableException) {
            throw failure
        } catch (failure: Throwable) {
            throw DestinationUnavailableException("Export failed for ${planned.name}: ${failure.message}", failure)
        }
    }

    private fun createTemporary(plan: ExportPlan, planned: PlannedExportFile): Uri {
        return try {
            plan.destination.createFile(".${planned.name}.${UUID.randomUUID()}.incomplete", planned.mimeType)
        } catch (failure: Throwable) {
            throw DestinationUnavailableException("Could not create temporary export file for ${planned.name}", failure)
        } ?: throw DestinationUnavailableException("Could not create temporary export file for ${planned.name}")
    }

    private fun expectedBytes(plan: ExportPlan, planned: PlannedExportFile): ExpectedProviderBytes {
        if (planned.sourceSegments.isNotEmpty()) {
            val chapterId = planned.sourceSegments.first().chapterId
            val manifestFile = plan.manifest.chapters.first { it.id == chapterId }.files.single()
            return ExpectedProviderBytes(manifestFile.sizeBytes, manifestFile.sha256)
        }
        val bytes = if (planned.mimeType == "application/json") {
            ExportManifestCodec.encode(plan.manifest).toByteArray(Charsets.UTF_8)
        } else {
            planned.sourceFiles.single().readBytes()
        }
        return ExpectedProviderBytes(bytes.size.toLong(), sha256(bytes))
    }

    private fun verifyProvider(
        destination: SafDocumentTree,
        uri: Uri,
        expected: ExpectedProviderBytes,
        name: String,
    ): ExportedFileVerification {
        val input = try {
            destination.openForRead(uri)
        } catch (failure: Throwable) {
            throw DestinationUnavailableException("Could not read exported ${name}", failure)
        } ?: throw DestinationUnavailableException("Provider cannot read exported ${name}")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        input.use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                size += count
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(size == expected.sizeBytes && hash == expected.sha256) {
            "Provider output verification failed for ${name}"
        }
        return ExportedFileVerification(uri, size, hash)
    }

    private fun findExisting(destination: SafDocumentTree, name: String): SafDocument? =
        try {
            destination.listChildren().firstOrNull { it.name.equals(name, ignoreCase = true) }
        } catch (failure: Throwable) {
            throw DestinationUnavailableException("Export destination is unavailable while looking for ${name}", failure)
        }

    private data class ExpectedProviderBytes(val sizeBytes: Long, val sha256: String)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun coverExtension(file: File): String {
        val bytes = file.inputStream().use { input ->
            val prefix = ByteArray(16)
            var size = 0
            while (size < prefix.size) {
                val count = input.read(prefix, size, prefix.size - size)
                if (count <= 0) break
                size += count
            }
            prefix.copyOf(size)
        }
        return when {
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "jpg"
            bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> "png"
            bytes.startsWith("GIF8".toByteArray()) -> "gif"
            bytes.startsWith("RIFF".toByteArray()) && bytes.size >= 12 && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "webp"
            bytes.toString(Charsets.UTF_8).trimStart().startsWith("<") -> "svg"
            else -> "bin"
        }
    }

    private fun coverMime(file: File): String = when (coverExtension(file)) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)

}
