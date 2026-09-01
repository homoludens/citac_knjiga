package com.homoludens.citacknjiga.tts.onnx

import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile

/** Separate fail-closed package slot for the qualified Serbian VITS release asset. */
public class VitsModelPackageStore(filesDir: File) {
    private val directory = AppPrivateStorage(filesDir).modelPackagesDirectory
    private val active = File(directory, "vits-active.zip")
    private val previous = File(directory, "vits-last-valid.zip")
    private val lockFile = File(directory, ".vits-package.lock")
    private val processLock = ReentrantReadWriteLock()

    public fun importPackage(source: ModelPackageSource): InstalledModelPackage = write {
        val temporary = File.createTempFile(".vits-package-", ".tmp", directory)
        try {
            source.openStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            val metadata = validate(temporary)
            if (active.isFile) Files.move(active.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporary.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING)
            sync(active)
            metadata
        } finally {
            temporary.delete()
        }
    }

    public fun activePackage(): InstalledModelPackage? = write {
        if (active.isFile) {
            runCatching { validate(active) }.getOrNull()?.let { return@write it }
        }
        if (!previous.isFile) return@write null
        val metadata = runCatching { validate(previous) }.getOrNull() ?: return@write null
        Files.move(previous.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING)
        metadata
    }

    public fun <T> withVerifiedArtifactFile(
        packageInfo: InstalledModelPackage,
        role: String,
        block: (File) -> T,
    ): T = withVerifiedArtifactFiles(packageInfo, setOf(role)) { block(requireNotNull(it[role])) }

    public fun <T> withVerifiedArtifactFiles(
        packageInfo: InstalledModelPackage,
        requiredRoles: Set<String>,
        optionalRoles: Set<String> = emptySet(),
        block: (Map<String, File>) -> T,
    ): T = read {
        val current = validate(active)
        check(current == packageInfo) { "VITS package changed after validation" }
        val temporary = mutableMapOf<String, File>()
        try {
            ZipFile(active).use { archive ->
                val manifest = archive.getInputStream(archive.getEntry("manifest.json")).use {
                    com.google.gson.JsonParser.parseReader(it.reader(StandardCharsets.UTF_8)).asJsonObject
                }
                (requiredRoles + optionalRoles).forEach { role ->
                    val entries = manifest.getAsJsonArray("entries").filter {
                        it.asJsonObject.get("role").asString == role
                    }
                    if (entries.isEmpty() && role in optionalRoles) return@forEach
                    check(entries.size == 1) { "VITS package must declare exactly one $role entry" }
                    val declared = entries.single().asJsonObject
                    val entry = archive.getEntry(declared.get("path").asString)
                        ?: throw ModelPackageImportException(ModelPackageFailureCode.INVALID_ARCHIVE)
                    val file = File.createTempFile(".vits-artifact-", ".tmp", directory)
                    temporary[role] = file
                    archive.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    check(file.length() == declared.get("size_bytes").asLong)
                    check(sha256(file) == declared.get("sha256").asString)
                }
            }
            block(temporary)
        } finally {
            temporary.values.forEach(File::delete)
        }
    }

    private fun validate(file: File): InstalledModelPackage {
        try {
            ZipFile(file).use { archive ->
                val names = archive.entries().asSequence().toList()
                if (names.isEmpty() || names.any { it.isDirectory || !ModelPackageStore.isSafePath(it.name) }) {
                    fail(ModelPackageFailureCode.INVALID_ARCHIVE)
                }
                if (names.map { it.name }.toSet().size != names.size || names.map { it.name }.toSet() !=
                    setOf("manifest.json") + names.filter { it.name != "manifest.json" }.map { it.name }.toSet()) {
                    fail(ModelPackageFailureCode.INVALID_ARCHIVE)
                }
                val manifest = archive.getInputStream(archive.getEntry("manifest.json")).use {
                    com.google.gson.JsonParser.parseReader(it.reader(StandardCharsets.UTF_8)).asJsonObject
                }
                require(manifest.get("schema").asString == "serbian-vits-model-package:1")
                val candidate = manifest.getAsJsonObject("candidate")
                require(candidate.get("model_id").asString == MODEL_ID)
                require(candidate.get("revision").asString == REVISION)
                require(candidate.getAsJsonObject("speaker").get("label").asString == "Dragana")
                require(candidate.getAsJsonObject("speaker").get("id").asInt == 0)
                require(manifest.get("legal").asString == "ALLOWED")
                require(manifest.getAsJsonObject("qualification").get("status").asString == "PASS")
                require(manifest.getAsJsonObject("qualification").get("api").asInt == 33)
                require(manifest.getAsJsonObject("graph_contract").get("status").asString == "INSPECTED")
                require(!manifest.getAsJsonObject("graph_contract").get("external_data").asBoolean)
                require(!manifest.getAsJsonObject("graph_contract").get("network_access").asBoolean)
                require(manifest.getAsJsonObject("resampler").get("native_rate_hz").asInt == NATIVE_RATE_HZ)
                require(manifest.getAsJsonObject("resampler").get("final_rate_hz").asInt == FINAL_RATE_HZ)
                require(manifest.getAsJsonObject("resampler").get("channels").asInt == 1)
                val entries = manifest.getAsJsonArray("entries").map { it.asJsonObject }
                val declared = entries.map { it.get("path").asString }
                require(declared.toSet().size == declared.size)
                require(names.map { it.name }.toSet() == declared.toSet() + "manifest.json")
                val roles = entries.map { it.get("role").asString }
                require(roles.count { it == "onnx" } == 1)
                require(roles.count { it == "tokens" } == 1)
                require(roles.count { it == "configuration" } == 1)
                require(roles.count { it == "attribution" } == 1)
                require(roles.count { it == "notice" } == 1)
                require(roles.count { it == "lexicon" } <= 1)
                val attribution = manifest.getAsJsonObject("attribution")
                require(attribution.get("license").asString == "CC-BY-4.0")
                require(attribution.get("source_url").asString ==
                    "https://huggingface.co/$MODEL_ID/tree/$REVISION")
                require(attribution.get("modification_notice").asString.isNotBlank())
                entries.forEach { entry ->
                    val path = entry.get("path").asString
                    require(ModelPackageStore.isSafePath(path))
                    require(FORBIDDEN_SUFFIXES.none { path.lowercase().endsWith(it) })
                    require(entry.get("sha256").asString.matches(Regex("[0-9a-f]{64}")))
                    val payload = archive.getEntry(path) ?: error("missing VITS entry")
                    require(payload.size == entry.get("size_bytes").asLong)
                    require(sha256(archive.getInputStream(payload)) == entry.get("sha256").asString)
                }
                val model = entries.single { it.get("role").asString == "onnx" }
                return InstalledModelPackage(
                    packageId = candidate.get("model_id").asString,
                    packageVersion = manifest.get("version").asString,
                    identitySha256 = manifest.get("identity_sha256").asString,
                    modelSha256 = model.get("sha256").asString,
                    voiceSha256 = "speaker-0",
                    runtimeId = "sherpa-onnx",
                    runtimeVersion = SHERPA_REVISION,
                    preprocessingCompatibilityId = "serbian-vits-preprocessing-v1",
                    preprocessingContractVersion = 1,
                    minimumAndroidApi = 30,
                    requiredAbi = "arm64-v8a",
                    sampleRateHz = FINAL_RATE_HZ,
                    channels = 1,
                    engine = "vits",
                    modelRevision = REVISION,
                    speakerId = 0,
                    nativeSampleRateHz = NATIVE_RATE_HZ,
                    frontendVersion = "serbian-vits-preprocessing-v1",
                    resamplerVersion = "serbian-vits-resampler-v1",
                    qualificationStatus = "PASS",
                )
            }
        } catch (exception: ModelPackageImportException) {
            throw exception
        } catch (exception: Exception) {
            throw ModelPackageImportException(ModelPackageFailureCode.INVALID_MANIFEST, cause = exception)
        }
    }

    private fun <T> write(block: () -> T): T = operation(processLock.writeLock(), block)
    private fun <T> read(block: () -> T): T = operation(processLock.readLock(), block)
    private fun <T> operation(lock: java.util.concurrent.locks.Lock, block: () -> T): T {
        if (!directory.isDirectory && !directory.mkdirs()) fail(ModelPackageFailureCode.STORAGE)
        lock.lock()
        return try {
            FileOutputStream(lockFile, true).use { it.channel.lock().use { block() } }
        } finally {
            lock.unlock()
        }
    }

    private fun sync(file: File) = FileOutputStream(file, true).use { it.fd.sync() }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)
    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(code: ModelPackageFailureCode): Nothing = throw ModelPackageImportException(code)

    private companion object {
        const val MODEL_ID = "daremc86/sr-cv-vits"
        const val REVISION = "83dc1e1b95d85b9f5602dc94909706fc83dfbc6c"
        const val SHERPA_REVISION = "34eba5a27220026b5981b633981c53205515067d"
        const val NATIVE_RATE_HZ = 22_050
        const val FINAL_RATE_HZ = 24_000
        val FORBIDDEN_SUFFIXES = setOf(".bin", ".ckpt", ".pt", ".pth", ".py", ".pyc", ".sh", ".so", ".safetensors")
    }
}
