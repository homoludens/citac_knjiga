package com.homoludens.citacknjiga.core.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

public data class PublishedArtifact(
    public val file: File,
    public val sizeBytes: Long,
    public val sha256: String,
)

/**
 * Publishes private bulk artifacts only after the temporary file is durable and valid.
 *
 * The temporary file lives below [AppPrivateStorage.temporaryDirectory]. Publication
 * first requests an atomic move. Some Android file-system providers do not support
 * [StandardCopyOption.ATOMIC_MOVE], so the fallback is a regular replacing move; that
 * fallback is not power-loss atomic and callers must rely on the temporary-file cleanup
 * path during reconciliation.
 */
public class AtomicArtifactStore(
    private val storage: AppPrivateStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Writes, syncs, validates, hashes, and publishes one artifact. */
    public fun publish(
        ownerId: String,
        destination: File,
        writer: (OutputStream) -> Unit,
        validator: (File) -> Unit = {},
    ): PublishedArtifact {
        val target = contained(destination)
        val parent = target.parentFile
        require(parent != null && (parent.isDirectory || parent.mkdirs())) {
            "Artifact destination directory is unavailable"
        }

        val temporary = createTemporary(ownerId, target.name)
        try {
            FileOutputStream(temporary).use { fileStream ->
                BufferedOutputStream(fileStream).use { bufferedStream ->
                    writer(bufferedStream)
                    bufferedStream.flush()
                    fileStream.fd.sync()
                }
            }
            validator(temporary)
            val artifact = PublishedArtifact(target, temporary.length(), sha256(temporary))
            moveIntoPlace(temporary, target)
            return artifact
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Returns the SHA-256 digest of a file as lowercase hexadecimal. */
    public fun sha256(file: File): String {
        val input = contained(file).inputStream().buffered()
        return input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        }
    }

    /** Deletes stale files below the private temporary area and leaves recent files alone. */
    public fun cleanupStaleTemporaryFiles(
        maxAgeMillis: Long,
        nowMillis: Long = clock(),
    ): Int {
        require(maxAgeMillis >= 0) { "Temporary file age cannot be negative" }
        val temporaryRoot = storage.temporaryDirectory
        if (!temporaryRoot.exists()) return 0
        return deleteMatchingFiles(temporaryRoot, nowMillis - maxAgeMillis) { true }
    }

    /**
     * Deletes old files below an app-private artifact area that are not referenced by
     * the caller's durable state. The reference set is the boundary to Room or another
     * owner; this helper does not inspect or mutate any database.
     */
    public fun cleanupOrphanFiles(
        directory: File,
        referencedFiles: Collection<File>,
        maxAgeMillis: Long,
        nowMillis: Long = clock(),
    ): Int {
        require(maxAgeMillis >= 0) { "Orphan file age cannot be negative" }
        val root = contained(directory)
        val referenced = referencedFiles.map(::contained).toSet()
        if (!root.exists()) return 0
        return deleteMatchingFiles(root, nowMillis - maxAgeMillis) { file ->
            file.canonicalFile !in referenced
        }
    }

    private fun createTemporary(ownerId: String, destinationName: String): File {
        val temporary = storage.temporaryFile(
            ownerId,
            ".${destinationName}.${UUID.randomUUID()}.tmp",
        )
        require(temporary.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Artifact temporary directory is unavailable"
        }
        require(temporary.createNewFile()) { "Could not create artifact temporary file" }
        return temporary
    }

    private fun moveIntoPlace(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun contained(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(storage.rootDirectory.toPath())) {
            "Artifact path escapes the app-private root"
        }
        require(canonical != storage.rootDirectory) { "Artifact path must be below the app-private root" }
        return canonical
    }

    private fun deleteMatchingFiles(
        root: File,
        cutoffMillis: Long,
        predicate: (File) -> Boolean,
    ): Int {
        var deleted = 0
        root.walkTopDown()
            .filter { it.isFile && it.lastModified() <= cutoffMillis && predicate(it) }
            .forEach { if (it.delete()) deleted++ }
        root.walkBottomUp()
            .filter { it != root && it.isDirectory && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }
        return deleted
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
