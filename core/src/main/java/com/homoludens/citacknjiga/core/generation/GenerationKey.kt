package com.homoludens.citacknjiga.core.generation

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Inputs that determine one reproducible audio segment. */
public data class GenerationKeyInput(
    public val tokens: List<Int>,
    public val modelSha256: String,
    public val voiceSha256: String,
    public val preprocessingVersion: String,
    public val pronunciationVersion: String,
    public val inferenceSettings: Map<String, String>,
    public val audioProcessingVersion: String,
    public val engine: String? = null,
    public val modelRevision: String? = null,
    public val speakerId: Int? = null,
    public val frontendVersion: String? = null,
    public val nativeSampleRateHz: Int? = null,
    public val finalSampleRateHz: Int? = null,
    public val resamplerVersion: String? = null,
    public val runtimeId: String? = null,
    public val runtimeVersion: String? = null,
)

/** The shared dependency identity and the token-specific generation identity. */
public data class GenerationKeys(
    public val dependencyKey: String,
    public val generationKey: String,
)

/** Calculates content-addressed identities without depending on clocks or database state. */
public object GenerationKeyCalculator {
    public fun calculate(input: GenerationKeyInput): GenerationKeys {
        val dependencyKey = dependencyKey(input)
        return GenerationKeys(
            dependencyKey = dependencyKey,
            generationKey = digest("generation-key/v1") {
                writeField("dependency_key", dependencyKey)
                writeField("tokens")
                writeInt(input.tokens.size)
                input.tokens.forEach(::writeInt)
            },
        )
    }

    /** Returns the identity shared by all segments using the same generation configuration. */
    public fun dependencyKey(input: GenerationKeyInput): String = digest("dependency-key/v1") {
        writeField("model_sha256", canonicalHash(input.modelSha256, "modelSha256"))
        writeField("voice_sha256", canonicalHash(input.voiceSha256, "voiceSha256"))
        writeField("preprocessing_version", canonicalVersion(input.preprocessingVersion, "preprocessingVersion"))
        writeField("pronunciation_version", canonicalVersion(input.pronunciationVersion, "pronunciationVersion"))
        writeField("inference_settings")
        val settings = canonicalSettings(input.inferenceSettings)
        writeInt(settings.size)
        settings.forEach { (name, value) ->
            writeField(name)
            writeField(value)
        }
        writeField("audio_processing_version", canonicalVersion(input.audioProcessingVersion, "audioProcessingVersion"))
        listOf(
            "engine" to input.engine,
            "model_revision" to input.modelRevision,
            "speaker_id" to input.speakerId?.toString(),
            "frontend_version" to input.frontendVersion,
            "native_sample_rate_hz" to input.nativeSampleRateHz?.toString(),
            "final_sample_rate_hz" to input.finalSampleRateHz?.toString(),
            "resampler_version" to input.resamplerVersion,
            "runtime_id" to input.runtimeId,
            "runtime_version" to input.runtimeVersion,
        ).forEach { (name, value) -> if (value != null) writeField(name, value) }
    }

    public fun generationKey(input: GenerationKeyInput): String = calculate(input).generationKey

    private fun canonicalHash(value: String, name: String): String = value.trim().lowercase(Locale.ROOT).also {
        require(it.isNotEmpty()) { "$name cannot be empty" }
    }

    private fun canonicalVersion(value: String, name: String): String = value.trim().also {
        require(it.isNotEmpty()) { "$name cannot be empty" }
    }

    private fun canonicalSettings(settings: Map<String, String>): List<Pair<String, String>> {
        val canonical = settings.entries.map { entry ->
            val name = entry.key.trim()
            require(name.isNotEmpty()) { "Inference setting name cannot be empty" }
            name to entry.value
        }
        require(canonical.map { it.first }.toSet().size == canonical.size) {
            "Inference settings contain duplicate canonical names"
        }
        return canonical.sortedBy { it.first }
    }

    private fun digest(domain: String, body: DataOutputStream.() -> Unit): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeField(domain)
            it.body()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex()
    }

    private fun DataOutputStream.writeField(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeField(name: String, value: String) {
        writeField(name)
        writeField(value)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Adapts per-segment inputs into keys consumed by selective reconciliation. */
public object SelectiveRegenerationPolicy {
    public fun expectedGenerationKeys(inputs: Map<String, GenerationKeyInput>): Map<String, String> =
        inputs.mapValues { (_, input) -> GenerationKeyCalculator.generationKey(input) }
}
