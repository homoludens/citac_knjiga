package com.homoludens.citacknjiga.tts.onnx

import android.content.ContentResolver
import android.net.Uri
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Opens a user-selected model package without retaining the provider URI. */
public fun interface ModelPackageSource {
    public fun openStream(): InputStream
}

public data class ModelPackageCompatibility(
    val runtimeVersion: String = "1.29.0",
    val minimumAndroidApi: Int = 30,
    val requiredAbi: String = "arm64-v8a",
    val preprocessingCompatibilityId: String = "kokoro-sr-ca5590d9",
    val preprocessingContractVersion: Int = 1,
)

/** Installs verified model archives below the application's private files directory. */
public class ModelPackageStore(
    filesDir: File,
    private val compatibility: ModelPackageCompatibility = ModelPackageCompatibility(),
) {
    private val privateStorage = AppPrivateStorage(filesDir)
    private val packageDir = privateStorage.modelPackagesDirectory
    private val activeFile = privateStorage.activeModelPackage
    private val previousFile = privateStorage.lastValidModelPackage
    private val transactionFile = File(packageDir, ".model-package-transaction")
    private val processLock = ReentrantReadWriteLock()

    /** Copies a SAF document into private temporary storage before inspecting it. */
    public fun importFromSaf(contentResolver: ContentResolver, uri: Uri): InstalledModelPackage {
        val source = try {
            contentResolver.openInputStream(uri)
                ?: throw ModelPackageImportException(
                    ModelPackageFailureCode.SOURCE_UNAVAILABLE,
                    cause = null,
                )
        } catch (exception: ModelPackageImportException) {
            throw exception
        } catch (exception: Exception) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.SOURCE_UNAVAILABLE,
                cause = exception,
            )
        }

        source.use { input -> return importPackage(ModelPackageSource { input }) }
    }

    /** Testable equivalent of [importFromSaf] for a provider-backed stream. */
    public fun importPackage(source: ModelPackageSource): InstalledModelPackage {
        return withWriteOperation {
            val temporary = try {
                File.createTempFile(".model-package-", ".tmp", packageDir)
            } catch (exception: Exception) {
                throw ModelPackageImportException(
                    ModelPackageFailureCode.STORAGE,
                    cause = exception,
                )
            }

            try {
                try {
                    source.openStream().use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.STORAGE,
                        cause = exception,
                    )
                }

                val metadata = validateArchive(temporary)
                publish(temporary)
                metadata
            } finally {
                temporary.delete()
            }
        }
    }

    /** Returns a redacted, typed result for asynchronous application callers. */
    public fun tryImportFromSaf(contentResolver: ContentResolver, uri: Uri): ModelPackageImportResult =
        try {
            ModelPackageImportResult.Success(importFromSaf(contentResolver, uri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ModelPackageImportResult.Failure(normalizeFailure(failure))
        }

    /** Testable equivalent of [tryImportFromSaf] for provider-backed streams. */
    public fun tryImportPackage(source: ModelPackageSource): ModelPackageImportResult =
        try {
            ModelPackageImportResult.Success(importPackage(source))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ModelPackageImportResult.Failure(normalizeFailure(failure))
        }

    /** Returns the active package, restoring the previous verified package if needed. */
    public fun activePackage(): InstalledModelPackage? {
        return withWriteOperation { activePackageLocked() }
    }

    private fun activePackageLocked(): InstalledModelPackage? {
        recoverTransaction()
        if (!activeFile.exists() && !previousFile.exists()) return null
        if (activeFile.exists()) {
            try {
                return validateArchive(activeFile)
            } catch (_: ModelPackageImportException) {
                // The previous archive is the only safe recovery candidate.
            }
        }
        if (!previousFile.exists()) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.NO_VALID_PACKAGE,
                cause = null,
            )
        }

        val metadata = validateArchive(previousFile)
        try {
            if (activeFile.exists()) {
                Files.deleteIfExists(activeFile.toPath())
            }
            moveComplete(previousFile, activeFile)
            syncFile(activeFile)
        } catch (exception: Exception) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.PUBLICATION,
                cause = exception,
            )
        }
        return metadata
    }

    /** Reads one already verified payload artifact while keeping the ZIP private. */
    public fun readArtifact(packageInfo: InstalledModelPackage, role: String): ByteArray =
        withReadOperation { withDeclaredArtifact(packageInfo, role) { archive, artifact ->
            val entry = archive.getEntry(artifact.path)
                ?: throw ModelPackageImportException(
                    ModelPackageFailureCode.INVALID_ARCHIVE,
                    "Declared artifact is missing: ${artifact.path}",
                )
            val bytes = archive.getInputStream(entry).use { it.readBytes() }
            if (bytes.size.toLong() != artifact.sizeBytes || sha256(bytes) != artifact.sha256) {
                throw ModelPackageImportException(
                    ModelPackageFailureCode.CHECKSUM_MISMATCH,
                    "Artifact checksum mismatch: ${artifact.path}",
                )
            }
            bytes
        } }

    /** Streams one verified artifact to a private file for APIs that accept a path. */
    public fun <T> withVerifiedArtifactFile(
        packageInfo: InstalledModelPackage,
        role: String,
        block: (File) -> T,
    ): T {
        return withReadOperation {
            val temporary = try {
                File.createTempFile(".model-artifact-", ".tmp", packageDir)
            } catch (exception: Exception) {
                throw ModelPackageImportException(
                    ModelPackageFailureCode.STORAGE,
                    "Could not create model artifact temporary storage",
                    exception,
                )
            }

            try {
                withDeclaredArtifact(packageInfo, role) { archive, artifact ->
                val entry = archive.getEntry(artifact.path)
                    ?: throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_ARCHIVE,
                        "Declared artifact is missing: ${artifact.path}",
                    )
                if (entry.size != artifact.sizeBytes) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.CHECKSUM_MISMATCH,
                        "Artifact size mismatch: ${artifact.path}",
                    )
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                archive.getInputStream(entry).use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            size += count
                        }
                    }
                }
                if (size != artifact.sizeBytes || digest.digest().toHex() != artifact.sha256) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.CHECKSUM_MISMATCH,
                        "Artifact checksum mismatch: ${artifact.path}",
                    )
                }
            }
                block(temporary)
            } finally {
                temporary.delete()
            }
        }
    }

    private fun <T> withDeclaredArtifact(
        packageInfo: InstalledModelPackage,
        role: String,
        block: (ZipFile, DeclaredArtifact) -> T,
    ): T {
        try {
            val verified = validateArchive(activeFile)
            if (verified != packageInfo) {
                throw ModelPackageImportException(
                    ModelPackageFailureCode.CHECKSUM_MISMATCH,
                    "The active model package changed after validation",
                )
            }
            ZipFile(activeFile).use { archive ->
                val manifestEntry = archive.getEntry("manifest.json")
                    ?: throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_MANIFEST,
                        "Model package manifest.json is missing",
                    )
                val manifest = archive.getInputStream(manifestEntry).use { input ->
                    JsonParser.parseReader(input.reader(StandardCharsets.UTF_8)).asJsonObject
                }
                val candidates = manifest.getAsJsonArray("artifacts").map { item -> item.asJsonObject }
                    .filter { artifact -> artifact.getAsJsonArray("roles").any { it.asString == role } }
                if (candidates.size != 1) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_MANIFEST,
                        "Model package must declare exactly one artifact with role $role",
                    )
                }
                val declared = candidates.single()
                return block(
                    archive,
                    DeclaredArtifact(
                        path = declared.get("path").asString,
                        sha256 = declared.get("sha256").asString,
                        sizeBytes = declared.get("size_bytes").asLong,
                    ),
                )
            }
        } catch (exception: ModelPackageImportException) {
            throw exception
        } catch (exception: Exception) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.INVALID_ARCHIVE,
                "Could not read model package artifact with role $role",
                exception,
            )
        }
    }

    private data class DeclaredArtifact(val path: String, val sha256: String, val sizeBytes: Long)

    private fun publish(temporary: File) {
        writeTransactionMarker()
        var movedPrevious = false
        try {
            if (activeFile.exists()) {
                moveComplete(activeFile, previousFile)
                movedPrevious = true
            }
            moveComplete(temporary, activeFile)
            syncFile(activeFile)
            transactionFile.delete()
            cleanupTemporaryCandidates()
        } catch (exception: Exception) {
            if (movedPrevious && !activeFile.exists() && previousFile.exists()) {
                runCatching {
                    moveComplete(previousFile, activeFile)
                }
            }
            throw ModelPackageImportException(
                ModelPackageFailureCode.PUBLICATION,
                "Could not publish the verified model package",
                exception,
            )
        }
    }

    private fun recoverTransaction() {
        if (!transactionFile.isFile) return
        val active = runCatching { validateArchive(activeFile) }.getOrNull()
        val previous = runCatching { validateArchive(previousFile) }.getOrNull()
        when {
            active != null -> {
                if (previousFile.exists() && previous == null) previousFile.delete()
                transactionFile.delete()
            }
            previous != null -> {
                if (activeFile.exists()) Files.deleteIfExists(activeFile.toPath())
                moveComplete(previousFile, activeFile)
                syncFile(activeFile)
                transactionFile.delete()
            }
            else -> throw ModelPackageImportException(ModelPackageFailureCode.NO_VALID_PACKAGE)
        }
    }

    private fun writeTransactionMarker() {
        try {
            FileOutputStream(transactionFile).use { output ->
                output.write("model-package-transaction-v1".toByteArray(StandardCharsets.US_ASCII))
                output.fd.sync()
            }
        } catch (exception: Exception) {
            throw ModelPackageImportException(ModelPackageFailureCode.PUBLICATION, cause = exception)
        }
    }

    private fun cleanupTemporaryCandidates() {
        packageDir.listFiles().orEmpty()
            .filter { it.name.startsWith(".model-package-") }
            .forEach { it.delete() }
    }

    private fun <T> withWriteOperation(block: () -> T): T = withOperation(processLock.writeLock(), block)

    private fun <T> withReadOperation(block: () -> T): T = withOperation(processLock.readLock(), block)

    private fun <T> withOperation(lock: java.util.concurrent.locks.Lock, block: () -> T): T {
        prepareDirectory()
        lock.lock()
        return try {
            FileChannel.open(
                File(packageDir, ".model-package.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel -> channel.lock().use { block() } }
        } finally {
            lock.unlock()
        }
    }

    private fun moveComplete(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun prepareDirectory() {
        if (!packageDir.isDirectory && !packageDir.mkdirs()) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.STORAGE,
                "Could not create private model-package storage",
            )
        }
    }

    private fun validateArchive(archiveFile: File): InstalledModelPackage {
        if (!archiveFile.isFile) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.INVALID_ARCHIVE,
                "Model package archive does not exist",
            )
        }

        try {
            ZipFile(archiveFile).use { archive ->
                val entries = archive.entries().asSequence().toList()
                if (entries.isEmpty() || entries.any { it.isDirectory || !isSafePath(it.name) }) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_ARCHIVE,
                        "Model package contains an unsafe or empty ZIP entry",
                    )
                }
                if (entries.map { it.name }.toSet().size != entries.size) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_ARCHIVE,
                        "Model package contains duplicate ZIP entries",
                    )
                }

                val manifests = entries.mapNotNull { entry ->
                    if (entry.size !in 0..MAX_MANIFEST_BYTES) return@mapNotNull null
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    try {
                        val json = JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
                        if (json.has("schema") && json.has("manifest") && json.has("artifacts")) {
                            entry.name to json
                        } else {
                            null
                        }
                } catch (_: Exception) {
                        null
                    }
                }
                if (manifests.size != 1) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_MANIFEST,
                        "Model package must contain exactly one manifest",
                    )
                }

                val (manifestPath, manifest) = manifests.single()
                val metadata = try {
                    validateManifest(manifest, manifestPath)
                } catch (exception: ModelPackageImportException) {
                    throw exception
                } catch (exception: Exception) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_MANIFEST,
                        "Model package manifest is malformed",
                        exception,
                    )
                }
                val declared = declaredArtifacts(manifest)
                val expectedNames = declared.keys + manifestPath
                if (entries.map { it.name }.toSet() != expectedNames.toSet()) {
                    throw ModelPackageImportException(
                        ModelPackageFailureCode.INVALID_ARCHIVE,
                        "Model package contains undeclared or missing files",
                    )
                }

                for ((path, artifact) in declared) {
                    val entry = archive.getEntry(path)
                    val actualSize = entry.size
                    if (actualSize != artifact.sizeBytes) {
                        throw ModelPackageImportException(
                            ModelPackageFailureCode.CHECKSUM_MISMATCH,
                            "${artifact.artifactId}: size mismatch",
                        )
                    }
                    val actualHash = sha256(archive.getInputStream(entry))
                    if (actualHash != artifact.sha256) {
                        throw ModelPackageImportException(
                            ModelPackageFailureCode.CHECKSUM_MISMATCH,
                            "${artifact.artifactId}: checksum mismatch",
                        )
                    }
                }
                return metadata
            }
        } catch (exception: ModelPackageImportException) {
            throw exception
        } catch (exception: ZipException) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.INVALID_ARCHIVE,
                "Model package is not a readable ZIP archive",
                exception,
            )
        } catch (exception: Exception) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.INVALID_ARCHIVE,
                "Model package could not be validated",
                exception,
            )
        }
    }

    private fun validateManifest(manifest: JsonObject, manifestPath: String): InstalledModelPackage {
        ensureKeys(
            manifest,
            path = "manifest",
            required = ROOT_KEYS,
            allowed = ROOT_KEYS + "extensions",
        )
        val schema = manifest.getAsJsonObject("schema")
        ensureKeys(schema, "manifest.schema", setOf("id", "version"), setOf("id", "version"))
        if (schema.get("id").asString != "serbian-model-package" || schema.get("version").asInt != 1) {
            failManifest("Unsupported model-package schema")
        }

        val manifestObject = manifest.getAsJsonObject("manifest")
        ensureKeys(
            manifestObject,
            "manifest.manifest",
            setOf("package_id", "package_version", "manifest_path", "created_at", "canonicalization", "identity"),
            setOf("package_id", "package_version", "manifest_path", "created_at", "canonicalization", "identity", "publisher"),
        )
        val packageId = manifestObject.get("package_id").asString
        val packageVersion = manifestObject.get("package_version").asString
        if (!ID_PATTERN.matches(packageId) || !VERSION_PATTERN.matches(packageVersion)) {
            failManifest("Invalid package identity")
        }
        if (manifestObject.get("manifest_path").asString != manifestPath ||
            manifestObject.get("canonicalization").asString != "json-sorted-keys-utf8-v1"
        ) {
            failManifest("Manifest path or canonicalization does not match the archive")
        }
        if (manifest.getAsJsonObject("model").get("model_id").asString != packageId) {
            failManifest("Model identity does not match the package identity")
        }

        val identity = manifestObject.getAsJsonObject("identity")
        ensureKeys(identity, "manifest.identity", setOf("algorithm", "value", "input"), setOf("algorithm", "value", "input"))
        if (identity.get("algorithm").asString != "sha-256" ||
            identity.get("input").asString != "package_id, package_version, and sorted artifact path+sha256 pairs" ||
            identity.get("value").asString != expectedIdentity(packageId, packageVersion, manifest.getAsJsonArray("artifacts"))
        ) {
            throw ModelPackageImportException(
                ModelPackageFailureCode.CHECKSUM_MISMATCH,
                "Manifest identity checksum mismatch",
            )
        }

        val artifacts = manifest.getAsJsonArray("artifacts")
        if (artifacts.size() < 8) failManifest("Model package declares too few artifacts")
        val artifactIds = mutableSetOf<String>()
        val artifactPaths = mutableSetOf<String>()
        val roles = mutableSetOf<String>()
        val artifactById = mutableMapOf<String, JsonObject>()
        for (index in 0 until artifacts.size()) {
            val artifact = artifacts[index].asJsonObject
            ensureKeys(
                artifact,
                "artifacts[$index]",
                ARTIFACT_KEYS,
                ARTIFACT_KEYS + "description",
            )
            val artifactId = artifact.get("artifact_id").asString
            val path = artifact.get("path").asString
            if (!ID_PATTERN.matches(artifactId) || !isSafePath(path) ||
                !artifactIds.add(artifactId) || !artifactPaths.add(path)
            ) {
                failManifest("Artifact identifiers and paths must be unique and safe")
            }
            val hash = artifact.get("sha256").asString
            if (!SHA256_PATTERN.matches(hash) || artifact.get("size_bytes").asLong < 0) {
                failManifest("Invalid artifact checksum or size")
            }
            val artifactRoles = artifact.getAsJsonArray("roles")
            if (artifactRoles.size() == 0 ||
                (0 until artifactRoles.size()).map { artifactRoles[it].asString }.toSet().size != artifactRoles.size() ||
                (0 until artifactRoles.size()).any { artifactRoles[it].asString !in ARTIFACT_ROLES }
            ) {
                failManifest("Artifact roles are invalid")
            }
            for (roleIndex in 0 until artifactRoles.size()) roles += artifactRoles[roleIndex].asString
            artifactById[artifactId] = artifact
        }
        if (manifestPath in artifactPaths || !REQUIRED_ROLES.all { it in roles }) {
            failManifest("Manifest artifacts do not cover the required package roles")
        }

        requireArtifact(manifest.getAsJsonObject("model"), "artifact_id", "model", artifactById)
        requireArtifact(
            manifest.getAsJsonObject("model").getAsJsonObject("architecture"),
            "config_artifact_id",
            "configuration",
            artifactById,
        )
        requireArtifact(manifest.getAsJsonObject("voice_style"), "artifact_id", "voice_style", artifactById)
        requireArtifact(manifest.getAsJsonObject("vocabulary"), "artifact_id", "vocabulary", artifactById)
        requireArtifact(manifest.getAsJsonObject("configuration"), "artifact_id", "configuration", artifactById)
        requireArtifact(
            manifest.getAsJsonObject("test_vectors"),
            "manifest_artifact_id",
            "test_vector",
            artifactById,
        )

        validateCompatibility(manifest)
        val modelArtifact = artifactById[manifest.getAsJsonObject("model").get("artifact_id").asString]
            ?: failManifest("Model artifact is not declared")
        val voiceArtifact = artifactById[manifest.getAsJsonObject("voice_style").get("artifact_id").asString]
            ?: failManifest("Voice artifact is not declared")
        if (!modelArtifact.get("path").asString.endsWith(".onnx")) {
            failManifest("The model role must reference an ONNX artifact")
        }
        return InstalledModelPackage(
            packageId = packageId,
            packageVersion = packageVersion,
            identitySha256 = identity.get("value").asString,
            modelSha256 = modelArtifact.get("sha256").asString,
            voiceSha256 = voiceArtifact.get("sha256").asString,
            runtimeId = manifest.getAsJsonObject("runtime").get("runtime_id").asString,
            runtimeVersion = manifest.getAsJsonObject("runtime").get("version").asString,
            preprocessingCompatibilityId = manifest.getAsJsonObject("preprocessing")
                .get("compatibility_id").asString,
            preprocessingContractVersion = manifest.getAsJsonObject("preprocessing")
                .get("contract_version").asInt,
            minimumAndroidApi = manifest.getAsJsonObject("runtime").get("min_android_api").asInt,
            requiredAbi = manifest.getAsJsonObject("runtime").getAsJsonArray("abis")[0].asString,
            sampleRateHz = manifest.getAsJsonObject("configuration").get("sample_rate_hz").asInt,
            channels = manifest.getAsJsonObject("model").getAsJsonObject("output_contract")
                .getAsJsonObject("waveform").get("channels").asInt,
        )
    }

    private fun validateCompatibility(manifest: JsonObject) {
        try {
            val model = manifest.getAsJsonObject("model")
            val input = model.getAsJsonObject("input_contract")
            val output = model.getAsJsonObject("output_contract")
            val waveform = output.getAsJsonObject("waveform")
            val predDur = output.getAsJsonObject("pred_dur")
            val limits = model.getAsJsonObject("limits")
            if (model.get("format").asString != "onnx" ||
                model.getAsJsonObject("architecture").get("family").asString != "kokoro-82m" ||
                model.getAsJsonObject("opset").get("ai_onnx").asInt != 18 ||
                input.getAsJsonObject("input_ids").get("dtype").asString != "int64" ||
                input.getAsJsonObject("input_ids").getAsJsonArray("shape").toString() != "[1,\"seq_len\"]" ||
                input.getAsJsonObject("input_ids").get("min_seq_len").asInt != 2 ||
                input.getAsJsonObject("input_ids").get("max_seq_len").asInt != 512 ||
                input.getAsJsonObject("ref_s").get("dtype").asString != "float32" ||
                input.getAsJsonObject("ref_s").getAsJsonArray("shape").toString() != "[1,256]" ||
                input.getAsJsonObject("speed").get("dtype").asString != "float32" ||
                input.getAsJsonObject("speed").getAsJsonArray("shape").size() != 0 ||
                waveform.get("dtype").asString != "float32" ||
                waveform.getAsJsonArray("shape").toString() != "[\"waveform_len\"]" ||
                waveform.get("channels").asInt != 1 ||
                waveform.get("sample_rate_hz").asInt != 24000 ||
                waveform.get("amplitude_domain").asString != "strictly_inside_minus_one_to_one" ||
                predDur.get("dtype").asString != "int64" ||
                predDur.get("relationship").asString != "pred_dur_len_equals_input_seq_len" ||
                limits.get("hard_phoneme_symbols").asInt != 510 ||
                limits.get("operational_phoneme_symbols").asInt != 507 ||
                limits.get("vocab_size").asInt != 178 ||
                manifest.getAsJsonObject("configuration").get("sample_rate_hz").asInt != 24000
            ) {
                failCompatibility("Model format or audio contract is not supported")
            }
            val voice = manifest.getAsJsonObject("voice_style")
            if (voice.get("locale").asString != "sr" || voice.get("dtype").asString != "float32" ||
                voice.getAsJsonArray("shape").toString() != "[510,1,256]" ||
                voice.getAsJsonObject("row_selection").get("clamp_max").asInt != 509
            ) {
                failCompatibility("Voice/style contract is not compatible")
            }
            val preprocessing = manifest.getAsJsonObject("preprocessing")
            if (preprocessing.get("compatibility_id").asString != compatibility.preprocessingCompatibilityId ||
                preprocessing.get("contract_version").asInt != compatibility.preprocessingContractVersion ||
                preprocessing.get("locale").asString != "sr" ||
                preprocessing.getAsJsonObject("phonemizer").get("engine").asString != "espeak-ng" ||
                preprocessing.getAsJsonObject("phonemizer").get("version").asString != "1.52.0" ||
                preprocessing.getAsJsonObject("phonemizer").get("voice").asString != "sr" ||
                preprocessing.getAsJsonObject("output_contract").get("unknown_symbol_policy").asString != "reject"
            ) {
                failCompatibility("Serbian preprocessing contract is not compatible")
            }
            val runtime = manifest.getAsJsonObject("runtime")
            val declaredAbis = runtime.getAsJsonArray("abis").let { array ->
                (0 until array.size()).map { array[it].asString }
            }
            if (runtime.get("version").asString != compatibility.runtimeVersion ||
                runtime.get("runtime_id").asString != "onnxruntime-android" ||
                runtime.get("platform").asString != "android" ||
                runtime.get("min_android_api").asInt != compatibility.minimumAndroidApi ||
                declaredAbis != listOf(compatibility.requiredAbi) ||
                runtime.get("execution_provider").asString != "cpu" ||
                runtime.getAsJsonObject("threading").get("intra_op_threads").asInt != 1 ||
                runtime.getAsJsonObject("threading").get("inter_op_threads").asInt != 1
            ) {
                failCompatibility("Android runtime contract is not compatible")
            }
        } catch (exception: ModelPackageImportException) {
            throw exception
        } catch (exception: Exception) {
            failCompatibility("Required compatibility declaration is missing")
        }
    }

    private fun declaredArtifacts(manifest: JsonObject): Map<String, Artifact> {
        val result = linkedMapOf<String, Artifact>()
        val artifacts = manifest.getAsJsonArray("artifacts")
        for (index in 0 until artifacts.size()) {
            val artifact = artifacts[index].asJsonObject
            result[artifact.get("path").asString] = Artifact(
                artifactId = artifact.get("artifact_id").asString,
                sha256 = artifact.get("sha256").asString,
                sizeBytes = artifact.get("size_bytes").asLong,
            )
        }
        return result
    }

    private fun expectedIdentity(packageId: String, packageVersion: String, artifacts: JsonArray): String {
        val pairs = (0 until artifacts.size()).map { index ->
            val artifact = artifacts[index].asJsonObject
            artifact.get("path").asString to artifact.get("sha256").asString
        }.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        val canonical = buildString {
            append("{\"artifacts\":[")
            pairs.joinTo(this, separator = ",") { (path, hash) ->
                "{\"path\":${quote(path)},\"sha256\":${quote(hash)}}"
            }
            append("],\"package_id\":${quote(packageId)},\"package_version\":${quote(packageVersion)}}")
        }
        return sha256(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ensureKeys(
        objectValue: JsonObject,
        path: String,
        required: Set<String>,
        allowed: Set<String>,
    ) {
        val keys = objectValue.keySet()
        if (!required.all { it in keys } || keys.any { it !in allowed }) {
            failManifest("Invalid fields at $path")
        }
    }

    private fun requireArtifact(
        objectValue: JsonObject,
        key: String,
        role: String,
        artifacts: Map<String, JsonObject>,
    ) {
        val artifactId = objectValue.get(key).asString
        if (role !in artifacts[artifactId]?.getAsJsonArray("roles")?.map { it.asString }.orEmpty()) {
            failManifest("$key must reference a $role artifact")
        }
    }

    private fun failManifest(message: String): Nothing = throw ModelPackageImportException(
        ModelPackageFailureCode.INVALID_MANIFEST,
        message,
    )

    private fun failCompatibility(message: String): Nothing = throw ModelPackageImportException(
        ModelPackageFailureCode.INCOMPATIBLE,
        message,
    )

    private data class Artifact(val artifactId: String, val sha256: String, val sizeBytes: Long)

    public companion object {
        const val MAX_MANIFEST_BYTES = 16L * 1024L * 1024L
        val ID_PATTERN = Regex("^[a-z][a-z0-9_.-]*$")
        val VERSION_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val ARTIFACT_ROLES = setOf("model", "voice_style", "vocabulary", "configuration", "test_vector", "test_audio", "notice")
        val REQUIRED_ROLES = setOf("model", "voice_style", "configuration", "test_vector", "vocabulary")
        val ROOT_KEYS = setOf(
            "schema", "manifest", "artifacts", "model", "voice_style", "vocabulary", "configuration",
            "preprocessing", "runtime", "test_vectors", "licenses", "attribution", "legal",
        )
        val ARTIFACT_KEYS = setOf(
            "artifact_id", "path", "roles", "media_type", "sha256", "size_bytes", "required",
            "distribution_status", "license_refs", "attribution_refs",
        )

        fun isSafePath(path: String): Boolean {
            val parts = path.split('/')
            return path.isNotEmpty() && '\u0000' !in path && !path.startsWith('/') && '\\' !in path &&
                parts.none { it.isEmpty() || it == "." || it == ".." }
        }

        fun quote(value: String): String = buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    in '\u0000'..'\u001f' -> append("\\u%04x".format(character.code))
                    else -> append(character)
                }
            }
            append('"')
        }

        fun sha256(input: InputStream): String {
            input.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                return digest.digest().toHex()
            }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()

        fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

        public fun normalizeFailure(failure: Throwable): ModelPackageFailure = ModelPackageFailure(
            when (failure) {
                is ModelPackageImportException -> failure.code
                else -> ModelPackageFailureCode.ERROR
            },
        )
    }
}
