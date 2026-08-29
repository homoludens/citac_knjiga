package com.homoludens.citacknjiga.tts.onnx.preprocessing

import android.content.res.AssetManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.Normalizer

public enum class PreprocessingStage {
    CLEANUP_TEXT,
    NORMALIZED_TEXT,
    PHONEMES,
    TOKEN_IDS,
    PROTECTED_SPANS,
    CHUNK_BOUNDARIES,
}

public enum class PreprocessingFailureCode {
    NATIVE_PHONEMIZER_UNAVAILABLE,
    PHONEMIZER_FAILED,
    UNKNOWN_IPA_SYMBOL,
}

public class SerbianPreprocessingException(
    public val stage: PreprocessingStage,
    public val code: PreprocessingFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

public fun interface SerbianPhonemizer {
    public fun phonemize(text: String): String
}

/** Exact Serbian pronunciation is unavailable until the pinned native stage is packaged. */
public object UnavailableSerbianPhonemizer : SerbianPhonemizer {
    override fun phonemize(text: String): String = throw SerbianPreprocessingException(
        stage = PreprocessingStage.PHONEMES,
        code = PreprocessingFailureCode.NATIVE_PHONEMIZER_UNAVAILABLE,
        message = "Exact Serbian phonemization requires the unavailable native eSpeak-NG stage; no approximation is used",
    )
}

public data class TextRange(val start: Int, val end: Int)

/** The limits verified for the pinned Kokoro Serbian model input. */
public data class SerbianModelLimits(
    public val operationalPhonemeSymbols: Int = 507,
    public val hardPhonemeSymbols: Int = 510,
    public val modelMaxSequenceLength: Int = 512,
) {
    init {
        require(operationalPhonemeSymbols > 0)
        require(hardPhonemeSymbols >= operationalPhonemeSymbols)
        require(modelMaxSequenceLength >= hardPhonemeSymbols + 2)
    }
}

public data class SerbianPreprocessingOutput(
    val cleanupText: String,
    val normalizedText: String,
    val phonemes: String,
    val tokenIds: List<Int>,
    val protectedSpans: List<TextRange>,
    val chunkBoundaries: List<TextRange>,
    val voiceRowIndex: Int,
) {
    /** Converts a phoneme range into a complete model input with boundary tokens. */
    public fun tokenIdsForChunk(boundary: TextRange): List<Int> {
        val phonemeCount = phonemes.codePointCount(0, phonemes.length)
        require(boundary.start >= 0 && boundary.end <= phonemeCount) {
            "Chunk boundary is outside the phoneme sequence"
        }
        require(boundary.start < boundary.end) { "Chunk boundary must contain phonemes" }
        return buildList(boundary.end - boundary.start + 2) {
            add(tokenIds.first())
            addAll(tokenIds.subList(boundary.start + 1, boundary.end + 1))
            add(tokenIds.last())
        }
    }
}

public data class GoldenVector(
    val id: String,
    val text: String,
    val cleanupText: String,
    val normalizedText: String,
    val phonemes: String,
    val tokenIds: List<Int>,
    val protectedSpans: List<TextRange>,
    val chunkBoundaries: List<TextRange>,
)

public data class GoldenCorpus(
    val version: String,
    val vectors: List<GoldenVector>,
)

/** Loads the checked-in machine-readable corpus without bundling it into the app. */
public object GoldenVectorFixtures {
    public fun load(input: InputStream): GoldenCorpus {
        val root = input.use { stream ->
            JsonParser.parseReader(InputStreamReader(stream, StandardCharsets.UTF_8)).asJsonObject
        }
        val contract = root.getAsJsonObject("corpus").getAsJsonObject("vector_contract")
        val stageOrder = contract.getAsJsonArray("stage_order").map { it.asString }
        require(stageOrder == EXPECTED_STAGE_ORDER) { "Unsupported golden vector stage order: $stageOrder" }
        val vectors = root.getAsJsonArray("vectors").map { parseVector(it.asJsonObject) }
        require(vectors.isNotEmpty()) { "Golden vector corpus is empty" }
        return GoldenCorpus(
            version = root.getAsJsonObject("corpus").get("version").asString,
            vectors = vectors,
        )
    }

    private fun parseVector(vector: JsonObject): GoldenVector = GoldenVector(
        id = vector.get("id").asString,
        text = vector.get("text").asString,
        cleanupText = vector.get("cleanup_text").asString,
        normalizedText = vector.get("normalized_text").asString,
        phonemes = vector.get("phonemes").asString,
        tokenIds = vector.getAsJsonArray("token_ids").map { it.asInt },
        protectedSpans = parseRanges(vector.getAsJsonArray("protected_spans")),
        chunkBoundaries = parseRanges(vector.getAsJsonArray("chunk_boundaries")),
    )

    private fun parseRanges(values: com.google.gson.JsonArray): List<TextRange> = values.map {
        val range = it.asJsonObject
        TextRange(range.get("start").asInt, range.get("end").asInt)
    }

    private val EXPECTED_STAGE_ORDER = listOf(
        "cleanup_text",
        "normalized_text",
        "phonemes",
        "token_ids",
        "protected_spans",
        "chunk_boundaries",
        "reference_audio",
    )
}

public class SerbianPreprocessingResources private constructor(
    internal val ipaOperations: List<IpaOperation>,
    internal val vocabulary: Map<Int, Int>,
    internal val boundaryTokenId: Int,
    internal val operationalLimit: Int,
    internal val hardLimit: Int,
    internal val modelSequenceLength: Int,
    internal val fallbackWidth: Int,
) {
    public val modelLimits: SerbianModelLimits
        get() = SerbianModelLimits(operationalLimit, hardLimit, modelSequenceLength)

    public companion object {
        public fun fromAssets(assetManager: AssetManager): SerbianPreprocessingResources =
            assetManager.open("normalization-v1.json").use { normalization ->
                assetManager.open("vocabulary-v1.json").use { vocabulary ->
                    assetManager.open("chunking-v1.json").use { chunking ->
                        fromJson(normalization, vocabulary, chunking)
                    }
                }
            }

        public fun fromJson(
            normalization: InputStream,
            vocabulary: InputStream,
            chunking: InputStream,
        ): SerbianPreprocessingResources {
            val normalizationObject = normalization.readJson()
            val vocabularyObject = vocabulary.readJson()
            val chunkingObject = chunking.readJson()
            val textStages = normalizationObject.getAsJsonObject("text_stages")
            require(textStages.getAsJsonObject("cleanup").get("operation").asString == "identity")
            require(textStages.getAsJsonObject("text_normalization").get("operation").asString == "identity")

            val operations = normalizationObject.getAsJsonArray("ipa_operations").map { value ->
                val operation = value.asJsonObject
                IpaOperation(
                    operation.get("operation").asString,
                    operation.getAsJsonArray("code_points")?.map { parseCodePoint(it.asString) }?.toSet().orEmpty(),
                    operation.getAsJsonObject("replacements")?.entrySet()
                        ?.associate { it.key to it.value.asString }.orEmpty(),
                    operation.get("pattern")?.asString,
                    operation.get("replacement")?.asString,
                    operation.get("trim")?.asBoolean == true,
                )
            }
            val vocabularyEntries = vocabularyObject.getAsJsonObject("entries").entrySet().associate { entry ->
                val codePoints = entry.key.codePoints().toArray()
                require(codePoints.size == 1) { "Vocabulary key is not one code point: ${entry.key}" }
                codePoints.single() to entry.value.asInt
            }
            val tokenization = vocabularyObject.getAsJsonObject("tokenization")
            val limits = chunkingObject.getAsJsonObject("limits")
            val fallback = chunkingObject.getAsJsonObject("reference_behavior")
                .getAsJsonArray("oversized_fallback_example")
                .first().asJsonObject
            val fallbackWidth = fallback.get("end").asInt - fallback.get("start").asInt
            val operationalLimit = limits.get("operational_phoneme_symbols").asInt
            require(fallbackWidth in 1..operationalLimit)
            return SerbianPreprocessingResources(
                ipaOperations = operations,
                vocabulary = vocabularyEntries,
                boundaryTokenId = tokenization.get("boundary_token_id").asInt,
                operationalLimit = operationalLimit,
                hardLimit = limits.get("hard_phoneme_symbols").asInt,
                modelSequenceLength = limits.get("model_max_sequence_length").asInt,
                fallbackWidth = fallbackWidth,
            )
        }

        private fun InputStream.readJson(): JsonObject = use { stream ->
            JsonParser.parseReader(InputStreamReader(stream, StandardCharsets.UTF_8)).asJsonObject
        }

        private fun parseCodePoint(value: String): Int = value.removePrefix("U+").toInt(16)
    }
}

public class SerbianPreprocessor(
    private val resources: SerbianPreprocessingResources,
    private val phonemizer: SerbianPhonemizer = UnavailableSerbianPhonemizer,
) {
    public val modelLimits: SerbianModelLimits
        get() = resources.modelLimits

    public companion object {
        /** Android production wiring for the exact native pronunciation stage. */
        public fun fromAssets(assetManager: AssetManager, filesDir: java.io.File): SerbianPreprocessor =
            SerbianPreprocessor(
                resources = SerbianPreprocessingResources.fromAssets(assetManager),
                phonemizer = NativeSerbianPhonemizer.fromAssets(assetManager, filesDir),
            )
    }

    public fun process(text: String): SerbianPreprocessingOutput {
        val cleanupText = text
        val normalizedText = cleanupText
        val rawPhonemes = try {
            phonemizer.phonemize(normalizedText)
        } catch (exception: SerbianPreprocessingException) {
            throw exception
        } catch (exception: Exception) {
            throw SerbianPreprocessingException(
                stage = PreprocessingStage.PHONEMES,
                code = PreprocessingFailureCode.PHONEMIZER_FAILED,
                message = "Serbian phonemizer failed",
                cause = exception,
            )
        }
        val phonemes = normalizeIpa(rawPhonemes)
        val codePoints = phonemes.codePoints().toArray()
        if (codePoints.isEmpty()) {
            throw SerbianPreprocessingException(
                stage = PreprocessingStage.PHONEMES,
                code = PreprocessingFailureCode.PHONEMIZER_FAILED,
                message = "Serbian phonemizer returned no IPA symbols",
            )
        }
        val tokenIds = ArrayList<Int>(codePoints.size + 2)
        tokenIds += resources.boundaryTokenId
        codePoints.forEach { codePoint ->
            val token = resources.vocabulary[codePoint]
                ?: throw SerbianPreprocessingException(
                    stage = PreprocessingStage.TOKEN_IDS,
                    code = PreprocessingFailureCode.UNKNOWN_IPA_SYMBOL,
                    message = "Unknown IPA symbol U+${codePoint.toString(16).uppercase()}",
                )
            tokenIds += token
        }
        tokenIds += resources.boundaryTokenId
        return SerbianPreprocessingOutput(
            cleanupText = cleanupText,
            normalizedText = normalizedText,
            phonemes = phonemes,
            tokenIds = tokenIds,
            protectedSpans = emptyList(),
            chunkBoundaries = chunkBoundaries(codePoints.size),
            voiceRowIndex = minOf(codePoints.size, 509),
        )
    }

    private fun normalizeIpa(input: String): String = resources.ipaOperations.fold(input) { value, operation ->
        when (operation.operation) {
            "unicode_normalize" -> Normalizer.normalize(value, Normalizer.Form.NFC)
            "remove_code_points" -> value.filterCodePoints { it !in operation.codePoints }
            "replace_sequences" -> operation.replacements.entries.fold(value) { current, replacement ->
                current.replace(replacement.key, replacement.value)
            }
            "replace_regex" -> Regex(operation.pattern!!).replace(value, operation.replacement!!).let {
                if (operation.trim) it.trim() else it
            }
            else -> error("Unsupported IPA operation: ${operation.operation}")
        }
    }

    private fun chunkBoundaries(phonemeCount: Int): List<TextRange> {
        if (phonemeCount <= resources.hardLimit) return listOf(TextRange(0, phonemeCount))
        val boundaries = mutableListOf<TextRange>()
        var start = 0
        while (phonemeCount - start > resources.hardLimit) {
            val end = minOf(start + resources.fallbackWidth, start + resources.operationalLimit)
            boundaries += TextRange(start, end)
            start = end
        }
        boundaries += TextRange(start, phonemeCount)
        return boundaries
    }

    private fun String.filterCodePoints(keep: (Int) -> Boolean): String = buildString {
        this@filterCodePoints.codePoints().forEach { codePoint ->
            if (keep(codePoint)) appendCodePoint(codePoint)
        }
    }
}

public fun firstDivergentStage(
    expected: GoldenVector,
    actual: SerbianPreprocessingOutput,
): PreprocessingStage? = when {
    expected.cleanupText != actual.cleanupText -> PreprocessingStage.CLEANUP_TEXT
    expected.normalizedText != actual.normalizedText -> PreprocessingStage.NORMALIZED_TEXT
    expected.phonemes != actual.phonemes -> PreprocessingStage.PHONEMES
    expected.tokenIds != actual.tokenIds -> PreprocessingStage.TOKEN_IDS
    expected.protectedSpans != actual.protectedSpans -> PreprocessingStage.PROTECTED_SPANS
    expected.chunkBoundaries != actual.chunkBoundaries -> PreprocessingStage.CHUNK_BOUNDARIES
    else -> null
}

internal data class IpaOperation(
    val operation: String,
    val codePoints: Set<Int>,
    val replacements: Map<String, String>,
    val pattern: String?,
    val replacement: String?,
    val trim: Boolean,
)
