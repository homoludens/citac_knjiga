package com.homoludens.citacknjiga.tts.onnx

import com.homoludens.citacknjiga.core.generation.BoundedGenerationResult
import com.homoludens.citacknjiga.core.generation.GenerationKeyCalculator
import com.homoludens.citacknjiga.core.generation.GenerationKeyInput
import com.homoludens.citacknjiga.core.generation.GenerationRunExecutor
import com.homoludens.citacknjiga.core.generation.GenerationProgressStore
import java.security.MessageDigest

/** Shared VITS identity inputs used by both queue creation and generation. */
public object VitsGenerationContract {
    public const val PREPROCESSING_VERSION: String = "serbian-vits-preprocessing-v1"
    public const val RESAMPLER_VERSION: String = "serbian-vits-resampler-v1"
    public const val AUDIO_PROCESSING_VERSION: String = "pcm16-wav-v1"
    public const val INFERENCE_SETTINGS_TEXT: String =
        "execution_provider=cpu;inter_op_threads=1;intra_op_threads=1;speed=1.0;text_chunk_characters=180"
    public val INFERENCE_SETTINGS: Map<String, String> = mapOf(
        "execution_provider" to "cpu",
        "inter_op_threads" to "1",
        "intra_op_threads" to "1",
        "speed" to "1.0",
        "text_chunk_characters" to "180",
    )
    public val INFERENCE_SETTINGS_HASH: String = sha256(INFERENCE_SETTINGS_TEXT)

    public fun generationKey(packageInfo: InstalledModelPackage, tokenIds: List<Int>): String =
        GenerationKeyCalculator.generationKey(
            GenerationKeyInput(
                tokens = tokenIds,
                modelSha256 = packageInfo.modelSha256,
                voiceSha256 = packageInfo.voiceSha256,
                preprocessingVersion = PREPROCESSING_VERSION,
                pronunciationVersion = PREPROCESSING_VERSION,
                inferenceSettings = INFERENCE_SETTINGS,
                audioProcessingVersion = AUDIO_PROCESSING_VERSION,
                engine = packageInfo.engine,
                modelRevision = packageInfo.modelRevision,
                speakerId = packageInfo.speakerId,
                frontendVersion = packageInfo.frontendVersion,
                nativeSampleRateHz = packageInfo.nativeSampleRateHz,
                finalSampleRateHz = packageInfo.sampleRateHz,
                resamplerVersion = packageInfo.resamplerVersion,
                runtimeId = packageInfo.runtimeId,
                runtimeVersion = packageInfo.runtimeVersion,
            ),
        )

    public fun roomPackageId(packageInfo: InstalledModelPackage): String =
        "vits-${packageInfo.identitySha256}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Reads only the verified model vocabulary; no Sherpa text frontend is used. */
public object VitsVocabulary {
    public const val BLANK_ID: Int = 139

    public fun read(store: VitsModelPackageStore, packageInfo: InstalledModelPackage): Map<Int, Int> =
        store.withVerifiedArtifactFile(packageInfo, "tokens") { file ->
            val vocabulary = linkedMapOf<Int, Int>()
            file.readLines().filter(String::isNotBlank).forEach { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 2)
                if (fields.size == 1) {
                    vocabulary[' '.code] = fields[0].toInt()
                } else if (!fields[0].startsWith("<")) {
                    val codePoint = fields[0].codePointAt(0)
                    vocabulary[codePoint] = fields[1].toInt()
                }
            }
            require(vocabulary.isNotEmpty()) { "VITS vocabulary is empty" }
            vocabulary
        }
}

/** Opens the verified VITS package and owns one Sherpa session for one run. */
public class VitsSegmentGeneratorFactory(
    private val store: VitsModelPackageStore,
    private val sessionOpener: (VitsModelPackageStore, InstalledModelPackage) -> SherpaVitsSession =
        { packageStore, packageInfo -> SherpaVitsSession.open(packageStore, packageInfo) },
    private val progressStore: GenerationProgressStore? = null,
) {
    public fun open(modelPackageId: String? = null): VitsSegmentGenerator {
        val packageInfo = store.activePackage()
            ?: throw ModelPackageImportException(ModelPackageFailureCode.NO_VALID_PACKAGE)
        val vocabulary = VitsVocabulary.read(store, packageInfo)
        val session = sessionOpener(store, packageInfo)
        return try {
            VitsSegmentGenerator(
                frontend = VitsSerbianFrontend(vocabulary, VitsVocabulary.BLANK_ID),
                session = session,
                packageInfo = packageInfo,
                inferenceSettingsHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH,
                audioProcessingVersion = VitsGenerationContract.AUDIO_PROCESSING_VERSION,
                modelPackageId = modelPackageId ?: packageInfo.packageId,
                progressStore = progressStore,
            )
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }
}

/** Closes the run-owned Sherpa session on success, failure, and cancellation. */
public class VitsGenerationExecutor(
    private val openGenerator: (String) -> VitsSegmentGenerator,
    private val modelPackageIdForRun: (String) -> String?,
    private val executeRun: suspend (String, VitsSegmentGenerator) -> BoundedGenerationResult,
) : GenerationRunExecutor {
    override suspend fun execute(runId: String): BoundedGenerationResult {
        val generator = openGenerator(modelPackageIdForRun(runId) ?: error("VITS run has no model package"))
        return try {
            executeRun(runId, generator)
        } finally {
            generator.close()
        }
    }
}
