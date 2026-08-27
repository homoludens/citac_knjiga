package com.homoludens.citacknjiga.tts.onnx.preprocessing

import android.content.res.AssetManager
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

public object EspeakDataInstaller {
    private const val MANIFEST_ASSET = "espeak-data-manifest-v1.json"
    private const val DATA_ROOT = "espeak-ng-data"

    @Synchronized
    public fun install(assetManager: AssetManager, filesDir: File): File {
        val manifest = assetManager.open(MANIFEST_ASSET).use { input ->
            JsonParser.parseReader(input.reader(StandardCharsets.UTF_8)).asJsonObject
        }
        require(manifest.getAsJsonObject("schema").get("version").asInt == 1)
        require(manifest.get("data_root").asString == DATA_ROOT)
        val files = manifest.getAsJsonArray("files").map { value ->
            val entry = value.asJsonObject
            DataFile(
                path = entry.get("path").asString,
                sizeBytes = entry.get("size_bytes").asLong,
                sha256 = entry.get("sha256").asString,
            )
        }
        require(files.isNotEmpty()) { "eSpeak-NG data manifest is empty" }
        require(files.map { it.path }.toSet().size == files.size) {
            "eSpeak-NG data manifest contains duplicate paths"
        }
        require(filesDir.isDirectory || filesDir.mkdirs()) { "App-private files directory is unavailable" }

        val destination = File(filesDir, "espeak-ng")
        if (destination.isDirectory && files.all { it.matches(destination) }) return destination

        val temporary = File(filesDir, ".espeak-ng-${UUID.randomUUID()}")
        try {
            files.forEach { file ->
                val target = file.resolve(temporary)
                require(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                assetManager.open("$DATA_ROOT/${file.path}").use { input -> target.outputStream().use(input::copyTo) }
            }
            require(files.all { it.matches(temporary) }) { "eSpeak-NG data checksum mismatch" }
            publish(temporary, destination, filesDir)
            return destination
        } finally {
            if (temporary.exists()) temporary.deleteRecursively()
        }
    }

    private data class DataFile(val path: String, val sizeBytes: Long, val sha256: String) {
        init {
            require(isSafeRelativePath(path)) { "Unsafe eSpeak-NG data path: $path" }
            require(sizeBytes >= 0) { "Negative eSpeak-NG data size: $path" }
            require(SHA256_PATTERN.matches(sha256)) { "Invalid eSpeak-NG data checksum: $path" }
        }

        fun resolve(root: File): File = safeChild(File(root, DATA_ROOT), path)

        fun matches(root: File): Boolean {
            if (Files.isSymbolicLink(root.toPath())) return false
            val dataDirectory = File(root, DATA_ROOT)
            if (Files.isSymbolicLink(dataDirectory.toPath())) return false
            val file = runCatching { resolve(root) }.getOrNull() ?: return false
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != sizeBytes) {
                return false
            }
            return sha256(file.inputStream()) == sha256
        }
    }

    private fun publish(temporary: File, destination: File, filesDir: File) {
        val backup = File(filesDir, ".espeak-ng-backup-${UUID.randomUUID()}")
        var previousMoved = false
        try {
            if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
                Files.move(destination.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                previousMoved = true
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            backup.deleteRecursively()
        } catch (failure: Exception) {
            if (previousMoved && !destination.exists() && backup.exists()) {
                runCatching {
                    Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    private fun safeChild(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile.toPath()
        val canonicalChild = File(root, relativePath).canonicalFile
        require(canonicalChild.toPath().startsWith(canonicalRoot)) {
            "eSpeak-NG data path escapes its private data root: $relativePath"
        }
        return canonicalChild
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val parts = path.split('/')
        return path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
            parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    private fun sha256(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
