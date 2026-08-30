package com.homoludens.citacknjiga.playback.export

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import java.math.BigDecimal

public object ExportManifestSchema {
    public const val ID: String = "citac-knjiga-export-manifest"
    public const val VERSION: Int = 1
    public const val HASH_ALGORITHM: String = "sha-256"
    public const val HASH_ENCODING: String = "lowercase-hex"
    public const val DURATION_UNIT: String = "milliseconds"
    public const val SAMPLE_RATE_HZ: Int = 24_000
    public const val CHANNELS: Int = 1
}

public data class ExportBookMetadata(
    public val id: String,
    public val title: String,
    public val author: String? = null,
    public val language: String? = null,
    public val totalDurationMs: Long,
)

public data class ExportSourceFingerprint(
    public val algorithm: String,
    public val encoding: String,
    public val value: String,
)

public data class ExportAttributionReference(
    public val id: String,
    public val subject: String,
    public val sourceUrl: String,
    public val licenseId: String,
    public val required: Boolean,
)

public data class ExportGenerationProvenance(
    public val generationKey: String,
    public val modelPackageId: String?,
    public val modelPackageSha256: String,
    public val voiceSha256: String,
    public val preprocessingVersion: String,
    public val pronunciationVersion: String,
    public val inferenceSettingsHash: String,
    public val audioProcessingVersion: String,
) {
    public companion object {
        public fun from(provenance: GenerationProvenance): ExportGenerationProvenance = ExportGenerationProvenance(
            generationKey = provenance.generationKey,
            modelPackageId = provenance.modelPackageId,
            modelPackageSha256 = provenance.modelPackageSha256,
            voiceSha256 = provenance.voiceSha256,
            preprocessingVersion = provenance.preprocessingVersion,
            pronunciationVersion = provenance.pronunciationVersion,
            inferenceSettingsHash = provenance.inferenceSettingsHash,
            audioProcessingVersion = provenance.audioProcessingVersion,
        )

        public fun fromReadySegment(segment: AudioSegmentEntity): ExportGenerationProvenance =
            from(
                GenerationProvenance(
                    generationKey = requireNotNull(segment.generationKey) { "Ready segment generation key is missing" },
                    modelPackageId = segment.modelPackageId,
                    modelPackageSha256 = requireNotNull(segment.modelPackageSha256) {
                        "Ready segment model checksum is missing"
                    },
                    voiceSha256 = requireNotNull(segment.voiceSha256) { "Ready segment voice checksum is missing" },
                    preprocessingVersion = requireNotNull(segment.preprocessingVersion) {
                        "Ready segment preprocessing version is missing"
                    },
                    pronunciationVersion = requireNotNull(segment.pronunciationVersion) {
                        "Ready segment pronunciation version is missing"
                    },
                    inferenceSettingsHash = requireNotNull(segment.inferenceSettingsHash) {
                        "Ready segment inference settings hash is missing"
                    },
                    audioProcessingVersion = requireNotNull(segment.audioProcessingVersion) {
                        "Ready segment audio-processing version is missing"
                    },
                ),
            )
    }
}

public data class ExportManifestFile(
    public val id: String,
    public val sequence: Int,
    /** A relative destination path supplied by the later export operation. */
    public val path: String,
    public val mediaType: String,
    public val sha256: String,
    public val sizeBytes: Long,
    public val durationMs: Long,
    public val sampleRateHz: Int,
    public val channels: Int,
    public val generation: ExportGenerationProvenance,
    public val sourceSegmentIds: List<String> = emptyList(),
) {
    public companion object {
        /** Adapts a verified Room segment without copying its private audio path. */
        public fun fromReadySegment(
            segment: AudioSegmentEntity,
            path: String,
            mediaType: String,
        ): ExportManifestFile {
            require(segment.status == AudioSegmentStatus.READY) { "Only a READY segment can enter an export manifest" }
            return ExportManifestFile(
                id = segment.id,
                sequence = segment.sequence,
                path = path,
                mediaType = mediaType,
                sha256 = requireNotNull(segment.audioSha256) { "Ready segment audio checksum is missing" },
                sizeBytes = requireNotNull(segment.sizeBytes) { "Ready segment size is missing" },
                durationMs = requireNotNull(segment.durationMs) { "Ready segment duration is missing" },
                sampleRateHz = segment.sampleRate,
                channels = segment.channels,
                generation = ExportGenerationProvenance.fromReadySegment(segment),
                sourceSegmentIds = listOf(segment.id),
            )
        }

        public fun fromReadySegments(
            chapterId: String,
            segments: List<AudioSegmentEntity>,
            path: String,
            mediaType: String,
            sha256: String,
            sizeBytes: Long,
            durationMs: Long,
        ): ExportManifestFile {
            require(segments.isNotEmpty()) { "A chapter file needs a ready segment" }
            require(segments.all { it.status == AudioSegmentStatus.READY }) {
                "Only READY segments can enter an export manifest"
            }
            return ExportManifestFile(
                id = "$chapterId-file",
                sequence = 0,
                path = path,
                mediaType = mediaType,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                durationMs = durationMs,
                sampleRateHz = ExportManifestSchema.SAMPLE_RATE_HZ,
                channels = ExportManifestSchema.CHANNELS,
                generation = ExportGenerationProvenance.fromReadySegment(segments.first()),
                sourceSegmentIds = segments.map { it.id },
            )
        }
    }
}

public data class ExportManifestChapter(
    public val id: String,
    public val ordinal: Int,
    public val title: String,
    public val durationMs: Long,
    public val files: List<ExportManifestFile>,
)

public data class ExportManifest(
    public val book: ExportBookMetadata,
    public val source: ExportSourceFingerprint,
    public val chapters: List<ExportManifestChapter>,
    public val attributionRefs: List<ExportAttributionReference>,
)

/** Creates a manifest projection from the current EPUB/Room book model. */
public object ExportManifestFactory {
    public fun fromRoom(
        project: BookProjectEntity,
        chapters: List<ChapterEntity>,
        filesByChapter: Map<String, List<ExportManifestFile>>,
        attributionRefs: List<ExportAttributionReference>,
    ): ExportManifest {
        val manifestChapters = chapters.sortedBy { it.ordinal }.map { chapter ->
            val files = filesByChapter[chapter.id].orEmpty().sortedWith(compareBy<ExportManifestFile> { it.sequence }.thenBy { it.id })
            ExportManifestChapter(
                id = chapter.id,
                ordinal = chapter.ordinal,
                title = chapter.title,
                durationMs = files.sumOf { it.durationMs },
                files = files,
            )
        }
        return ExportManifest(
            book = ExportBookMetadata(
                id = project.id,
                title = project.title,
                author = project.author,
                language = project.language,
                totalDurationMs = manifestChapters.sumOf { it.durationMs },
            ),
            source = ExportSourceFingerprint(
                algorithm = ExportManifestSchema.HASH_ALGORITHM,
                encoding = ExportManifestSchema.HASH_ENCODING,
                value = project.sourceFingerprint,
            ),
            chapters = manifestChapters,
            attributionRefs = attributionRefs.sortedBy { it.id },
        )
    }
}

public object ExportManifestValidator {
    private val hashPattern = Regex("^[0-9a-f]{64}$")
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val versionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:+/-]*$")
    private val httpsUrlPattern = Regex("^https://[^\\s]+$")

    public fun validate(manifest: ExportManifest) {
        validateBook(manifest.book)
        require(manifest.source.algorithm == ExportManifestSchema.HASH_ALGORITHM) { "Unsupported source hash algorithm" }
        require(manifest.source.encoding == ExportManifestSchema.HASH_ENCODING) { "Unsupported source hash encoding" }
        requireHash(manifest.source.value, "source fingerprint")
        require(manifest.chapters.isNotEmpty()) { "Manifest must contain a chapter" }
        require(manifest.chapters.map { it.ordinal } == manifest.chapters.indices.toList()) {
            "Chapter ordinals must be contiguous and ordered"
        }
        val fileIds = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        val sourceSegmentIds = mutableSetOf<String>()
        manifest.chapters.forEach { chapter ->
            requireId(chapter.id, "chapter id")
            require(chapter.title.isNotBlank()) { "Chapter title cannot be blank" }
            require(chapter.durationMs > 0L) { "Chapter duration must be positive" }
            require(chapter.files.isNotEmpty()) { "Chapter must contain a file" }
            require(chapter.files.map { it.sequence } == chapter.files.indices.toList()) {
                "File sequences must be contiguous and ordered in chapter ${chapter.id}"
            }
            chapter.files.forEach { file ->
                require(fileIds.add(file.id)) { "File identifiers must be unique" }
                require(paths.add(file.path)) { "Manifest file paths must be unique" }
                require(file.sourceSegmentIds.distinct().size == file.sourceSegmentIds.size) {
                    "Source segment identifiers must be unique"
                }
                file.sourceSegmentIds.forEach {
                    requireId(it, "source segment id")
                    require(sourceSegmentIds.add(it)) { "Source segment identifiers must be unique" }
                }
                validateFile(file)
            }
            require(chapter.durationMs == chapter.files.sumOf { it.durationMs }) {
                "Chapter duration does not equal its file durations"
            }
        }
        require(manifest.book.totalDurationMs == manifest.chapters.sumOf { it.durationMs }) {
            "Book duration does not equal its chapter durations"
        }
        require(manifest.attributionRefs.isNotEmpty()) { "Manifest must contain attribution references" }
        require(manifest.attributionRefs.map { it.id } == manifest.attributionRefs.map { it.id }.sorted()) {
            "Attribution references must be ordered by id"
        }
        val attributionIds = mutableSetOf<String>()
        manifest.attributionRefs.forEach { reference ->
            requireId(reference.id, "attribution id")
            require(attributionIds.add(reference.id)) { "Attribution identifiers must be unique" }
            require(reference.subject.isNotBlank()) { "Attribution subject cannot be blank" }
            require(httpsUrlPattern.matches(reference.sourceUrl)) { "Attribution source URL must use HTTPS" }
            requireId(reference.licenseId, "license id")
        }
    }

    private fun validateBook(book: ExportBookMetadata) {
        requireId(book.id, "book id")
        require(book.title.isNotBlank()) { "Book title cannot be blank" }
        book.author?.let { require(it.isNotBlank()) { "Book author cannot be blank" } }
        book.language?.let { require(it.isNotBlank() && !it.contains(Regex("\\s"))) { "Book language is invalid" } }
        require(book.totalDurationMs > 0L) { "Book duration must be positive" }
    }

    private fun validateFile(file: ExportManifestFile) {
        requireId(file.id, "file id")
        requireRelativePath(file.path)
        require(file.mediaType in setOf("audio/mp4", "audio/mp4a-latm", "audio/wav")) { "Unsupported audio media type" }
        val extension = file.path.substringAfterLast('.', "").lowercase()
        require((file.mediaType == "audio/wav" && extension == "wav") ||
            (file.mediaType != "audio/wav" && extension in setOf("m4a", "mp4"))) {
            "Audio media type and path extension disagree"
        }
        requireHash(file.sha256, "audio file checksum")
        require(file.sizeBytes > 0L) { "Audio file size must be positive" }
        require(file.durationMs > 0L) { "Audio file duration must be positive" }
        require(file.sampleRateHz == ExportManifestSchema.SAMPLE_RATE_HZ) { "Audio sample rate must be 24 kHz" }
        require(file.channels == ExportManifestSchema.CHANNELS) { "Audio must be mono" }
        validateGeneration(file.generation)
    }

    private fun validateGeneration(generation: ExportGenerationProvenance) {
        requireHash(generation.generationKey, "generation key")
        generation.modelPackageId?.let { requireId(it, "model package id") }
        requireHash(generation.modelPackageSha256, "model package checksum")
        requireHash(generation.voiceSha256, "voice checksum")
        requireVersion(generation.preprocessingVersion, "preprocessing version")
        requireVersion(generation.pronunciationVersion, "pronunciation version")
        requireHash(generation.inferenceSettingsHash, "inference settings hash")
        requireVersion(generation.audioProcessingVersion, "audio-processing version")
    }

    private fun requireHash(value: String, name: String) {
        require(hashPattern.matches(value)) { "$name must be lowercase SHA-256 hex" }
    }

    private fun requireId(value: String, name: String) {
        require(idPattern.matches(value)) { "$name is invalid" }
    }

    private fun requireVersion(value: String, name: String) {
        require(versionPattern.matches(value)) { "$name is invalid" }
    }

    private fun requireRelativePath(value: String) {
        val parts = value.split('/')
        require(value.isNotEmpty() && !value.startsWith('/') && '\\' !in value && ':' !in value &&
            '?' !in value && '#' !in value && '\u0000' !in value && parts.none { it.isEmpty() || it == "." || it == ".." }) {
            "Manifest file path must be a safe relative path"
        }
    }
}

/** Explicit field order makes the UTF-8 JSON representation reproducible. */
public object ExportManifestCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    public fun encode(manifest: ExportManifest): String {
        ExportManifestValidator.validate(manifest)
        return gson.toJson(toJson(manifest)) + "\n"
    }

    public fun decode(json: String): ExportManifest {
        return try {
            val root = JsonParser.parseString(json).asObjectStrict()
            val schema = root.requiredObject("schema")
            schema.requireKeys("schema", setOf("id", "version"))
            require(schema.requiredString("id") == ExportManifestSchema.ID &&
                schema.requiredInt("version") == ExportManifestSchema.VERSION) { "Unsupported export manifest schema" }
            root.requireKeys("manifest", setOf("schema", "book", "source", "duration_unit", "chapters", "attribution_refs"))
            require(root.requiredString("duration_unit") == ExportManifestSchema.DURATION_UNIT) {
                "Unsupported duration unit"
            }
            val book = parseBook(root.requiredObject("book"))
            val source = parseSource(root.requiredObject("source"))
            val chapters = root.requiredArray("chapters").map(::parseChapter)
            val references = root.requiredArray("attribution_refs").map(::parseAttribution)
            ExportManifest(book, source, chapters, references).also(ExportManifestValidator::validate)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid export manifest: ${failure.message}", failure)
        }
    }

    private fun toJson(manifest: ExportManifest): JsonObject = JsonObject().apply {
        add("schema", JsonObject().apply {
            addProperty("id", ExportManifestSchema.ID)
            addProperty("version", ExportManifestSchema.VERSION)
        })
        add("book", JsonObject().apply {
            addProperty("id", manifest.book.id)
            addProperty("title", manifest.book.title)
            manifest.book.author?.let { addProperty("author", it) }
            manifest.book.language?.let { addProperty("language", it) }
            addProperty("total_duration_ms", manifest.book.totalDurationMs)
        })
        add("source", JsonObject().apply {
            addProperty("algorithm", manifest.source.algorithm)
            addProperty("encoding", manifest.source.encoding)
            addProperty("value", manifest.source.value)
        })
        addProperty("duration_unit", ExportManifestSchema.DURATION_UNIT)
        add("chapters", JsonArray().apply { manifest.chapters.forEach { add(chapterJson(it)) } })
        add("attribution_refs", JsonArray().apply { manifest.attributionRefs.forEach { add(attributionJson(it)) } })
    }

    private fun chapterJson(chapter: ExportManifestChapter): JsonObject = JsonObject().apply {
        addProperty("id", chapter.id)
        addProperty("ordinal", chapter.ordinal)
        addProperty("title", chapter.title)
        addProperty("duration_ms", chapter.durationMs)
        add("files", JsonArray().apply { chapter.files.forEach { add(fileJson(it)) } })
    }

    private fun fileJson(file: ExportManifestFile): JsonObject = JsonObject().apply {
        addProperty("id", file.id)
        addProperty("sequence", file.sequence)
        addProperty("path", file.path)
        addProperty("media_type", file.mediaType)
        addProperty("sha256", file.sha256)
        addProperty("size_bytes", file.sizeBytes)
        addProperty("duration_ms", file.durationMs)
        addProperty("sample_rate_hz", file.sampleRateHz)
        addProperty("channels", file.channels)
        add("generation", generationJson(file.generation))
        if (file.sourceSegmentIds.isNotEmpty()) {
            add("source_segment_ids", JsonArray().apply { file.sourceSegmentIds.forEach { add(it) } })
        }
    }

    private fun generationJson(generation: ExportGenerationProvenance): JsonObject = JsonObject().apply {
        addProperty("generation_key", generation.generationKey)
        generation.modelPackageId?.let { addProperty("model_package_id", it) }
        addProperty("model_package_sha256", generation.modelPackageSha256)
        addProperty("voice_sha256", generation.voiceSha256)
        addProperty("preprocessing_version", generation.preprocessingVersion)
        addProperty("pronunciation_version", generation.pronunciationVersion)
        addProperty("inference_settings_hash", generation.inferenceSettingsHash)
        addProperty("audio_processing_version", generation.audioProcessingVersion)
    }

    private fun attributionJson(reference: ExportAttributionReference): JsonObject = JsonObject().apply {
        addProperty("id", reference.id)
        addProperty("subject", reference.subject)
        addProperty("source_url", reference.sourceUrl)
        addProperty("license_id", reference.licenseId)
        addProperty("required", reference.required)
    }

    private fun parseBook(value: JsonObject): ExportBookMetadata {
        value.requireKeys("book", setOf("id", "title", "total_duration_ms", "author", "language"))
        return ExportBookMetadata(
            id = value.requiredString("id"),
            title = value.requiredString("title"),
            author = value.optionalString("author"),
            language = value.optionalString("language"),
            totalDurationMs = value.requiredLong("total_duration_ms"),
        )
    }

    private fun parseSource(value: JsonObject): ExportSourceFingerprint {
        value.requireKeys("source", setOf("algorithm", "encoding", "value"))
        return ExportSourceFingerprint(value.requiredString("algorithm"), value.requiredString("encoding"), value.requiredString("value"))
    }

    private fun parseChapter(value: JsonElement): ExportManifestChapter {
        val objectValue = value.asObjectStrict()
        objectValue.requireKeys("chapter", setOf("id", "ordinal", "title", "duration_ms", "files"))
        return ExportManifestChapter(
            id = objectValue.requiredString("id"),
            ordinal = objectValue.requiredInt("ordinal"),
            title = objectValue.requiredString("title"),
            durationMs = objectValue.requiredLong("duration_ms"),
            files = objectValue.requiredArray("files").map(::parseFile),
        )
    }

    private fun parseFile(value: JsonElement): ExportManifestFile {
        val objectValue = value.asObjectStrict()
        objectValue.requireKeys("file", setOf("id", "sequence", "path", "media_type", "sha256", "size_bytes", "duration_ms", "sample_rate_hz", "channels", "generation", "source_segment_ids"))
        return ExportManifestFile(
            id = objectValue.requiredString("id"),
            sequence = objectValue.requiredInt("sequence"),
            path = objectValue.requiredString("path"),
            mediaType = objectValue.requiredString("media_type"),
            sha256 = objectValue.requiredString("sha256"),
            sizeBytes = objectValue.requiredLong("size_bytes"),
            durationMs = objectValue.requiredLong("duration_ms"),
            sampleRateHz = objectValue.requiredInt("sample_rate_hz"),
            channels = objectValue.requiredInt("channels"),
            generation = parseGeneration(objectValue.requiredObject("generation")),
            sourceSegmentIds = objectValue.optionalStringArray("source_segment_ids"),
        )
    }

    private fun parseGeneration(value: JsonObject): ExportGenerationProvenance {
        value.requireKeys("generation", setOf("generation_key", "model_package_sha256", "voice_sha256", "preprocessing_version", "pronunciation_version", "inference_settings_hash", "audio_processing_version", "model_package_id"))
        return ExportGenerationProvenance(
            generationKey = value.requiredString("generation_key"),
            modelPackageId = value.optionalString("model_package_id"),
            modelPackageSha256 = value.requiredString("model_package_sha256"),
            voiceSha256 = value.requiredString("voice_sha256"),
            preprocessingVersion = value.requiredString("preprocessing_version"),
            pronunciationVersion = value.requiredString("pronunciation_version"),
            inferenceSettingsHash = value.requiredString("inference_settings_hash"),
            audioProcessingVersion = value.requiredString("audio_processing_version"),
        )
    }

    private fun parseAttribution(value: JsonElement): ExportAttributionReference {
        val objectValue = value.asObjectStrict()
        objectValue.requireKeys("attribution", setOf("id", "subject", "source_url", "license_id", "required"))
        return ExportAttributionReference(
            id = objectValue.requiredString("id"),
            subject = objectValue.requiredString("subject"),
            sourceUrl = objectValue.requiredString("source_url"),
            licenseId = objectValue.requiredString("license_id"),
            required = objectValue.requiredBoolean("required"),
        )
    }

    private fun JsonElement.asObjectStrict(): JsonObject {
        require(isJsonObject) { "Expected JSON object" }
        return asJsonObject
    }

    private fun JsonObject.requireKeys(path: String, optionalKeys: Set<String>) {
        require(keySet().all { it in optionalKeys }) { "Unexpected field at $path" }
        require(optionalKeys.all { it in keySet() || it in OPTIONAL_FIELDS }) { "Missing field at $path" }
    }

    private fun JsonObject.requiredString(name: String): String = get(name).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Missing or invalid field $name" }
        it.asString
    }

    private fun JsonObject.optionalString(name: String): String? = get(name)?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid field $name" }
        it.asString
    }

    private fun JsonObject.requiredLong(name: String): Long = get(name).let {
        require(it != null && it.isJsonPrimitive && !it.asJsonPrimitive.isString && !it.asJsonPrimitive.isBoolean) {
            "Missing or invalid field $name"
        }
        runCatching { BigDecimal(it.asString).longValueExact() }
            .getOrElse { throw IllegalArgumentException("Field $name must be an integer") }
    }

    private fun JsonObject.requiredInt(name: String): Int = requiredLong(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Field $name is out of range" } }.toInt()

    private fun JsonObject.requiredBoolean(name: String): Boolean = get(name).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Missing or invalid field $name" }
        it.asBoolean
    }

    private fun JsonObject.requiredObject(name: String): JsonObject = get(name).let {
        require(it != null && it.isJsonObject) { "Missing or invalid object $name" }
        it.asJsonObject
    }

    private fun JsonObject.requiredArray(name: String): JsonArray = get(name).let {
        require(it != null && it.isJsonArray) { "Missing or invalid array $name" }
        it.asJsonArray
    }

    private fun JsonObject.optionalStringArray(name: String): List<String> = get(name)?.let {
        require(it.isJsonArray) { "Invalid field $name" }
        it.asJsonArray.map { value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid field $name" }
            value.asString
        }
    }.orEmpty()

    private val OPTIONAL_FIELDS = setOf("author", "language", "model_package_id", "source_segment_ids")
}
