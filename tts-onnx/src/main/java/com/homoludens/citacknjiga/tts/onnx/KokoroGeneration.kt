package com.homoludens.citacknjiga.tts.onnx

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationKeyCalculator
import com.homoludens.citacknjiga.core.generation.GenerationKeyInput
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.SegmentGenerator
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor
import java.security.MessageDigest

public object KokoroGenerationContract {
    public const val PREPROCESSING_VERSION: String = "kokoro-sr-ca5590d9/contract-1"
    public const val AUDIO_PROCESSING_VERSION: String = "pcm16-wav-v1"
    public const val INFERENCE_SETTINGS_TEXT: String =
        "execution_provider=cpu;inter_op_threads=1;intra_op_threads=1;speed=1.0"
    public val INFERENCE_SETTINGS: Map<String, String> = mapOf(
        "execution_provider" to "cpu",
        "intra_op_threads" to "1",
        "inter_op_threads" to "1",
        "speed" to "1.0",
    )
    public val INFERENCE_SETTINGS_HASH: String = sha256(INFERENCE_SETTINGS_TEXT)

    public fun frontendVersion(packageInfo: InstalledModelPackage): String =
        packageInfo.frontendVersion ?: PREPROCESSING_VERSION

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
                frontendVersion = frontendVersion(packageInfo),
                nativeSampleRateHz = packageInfo.nativeSampleRateHz,
                finalSampleRateHz = packageInfo.sampleRateHz,
                resamplerVersion = packageInfo.resamplerVersion,
                runtimeId = packageInfo.runtimeId,
                runtimeVersion = packageInfo.runtimeVersion,
            ),
        )

    public fun roomPackageId(packageInfo: InstalledModelPackage): String =
        "kokoro-${packageInfo.identitySha256}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Opens one verified Kokoro package for one durable generation run. */
public class KokoroSegmentGeneratorFactory(
    private val store: ModelPackageStore,
    private val preprocessorFactory: () -> SerbianPreprocessor,
    private val sessionOpener: (ModelPackageStore, InstalledModelPackage) -> OnnxTtsSession =
        { modelStore, packageInfo -> OnnxTtsSession.open(modelStore, packageInfo) },
) {
    public fun open(modelPackageId: String? = null): KokoroSegmentGenerator {
        val packageInfo = store.activePackage()
            ?: throw ModelPackageImportException(ModelPackageFailureCode.NO_VALID_PACKAGE)
        require(packageInfo.engine == "kokoro") { "Active package is not a Kokoro package" }
        val session = sessionOpener(store, packageInfo)
        return try {
            KokoroSegmentGenerator(
                preprocessor = preprocessorFactory(),
                session = session,
                packageInfo = packageInfo,
                modelPackageId = modelPackageId ?: KokoroGenerationContract.roomPackageId(packageInfo),
            )
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }
}

/** Adapts the verified ONNX boundary to one bounded durable audio segment. */
public class KokoroSegmentGenerator(
    private val preprocessor: SerbianPreprocessor,
    private val session: OnnxTtsSession,
    private val packageInfo: InstalledModelPackage,
    private val modelPackageId: String,
) : SegmentGenerator, AutoCloseable {
    override suspend fun generate(segment: AudioSegmentEntity, block: NarrationBlockEntity): GeneratedSegmentAudio {
        val prepared = preprocessor.process(block.sourceText)
        val chunks = prepared.chunkBoundaries.map { boundary ->
            session.generate(prepared.tokenIdsForChunk(boundary), speed = 1f)
        }
        val output = OnnxTtsOutput(
            pcm = chunks.flatMap { it.pcm.asIterable() }.toFloatArray(),
            predDur = chunks.flatMap { it.predDur.asIterable() }.toLongArray(),
        )
        OnnxAudioOutputValidator.validate(output, output.predDur.size)
        val expectedKey = KokoroGenerationContract.generationKey(packageInfo, prepared.tokenIds)
        check(segment.generationKey == expectedKey) { "Kokoro generation key does not match the pending segment" }
        val durationMs = (output.pcm.size * 1_000L / output.sampleRateHz).coerceAtLeast(1)
        return GeneratedSegmentAudio(
            provenance = GenerationProvenance(
                generationKey = expectedKey,
                modelPackageId = modelPackageId,
                modelPackageSha256 = packageInfo.identitySha256,
                voiceSha256 = packageInfo.voiceSha256,
                preprocessingVersion = KokoroGenerationContract.PREPROCESSING_VERSION,
                pronunciationVersion = KokoroGenerationContract.PREPROCESSING_VERSION,
                inferenceSettingsHash = KokoroGenerationContract.INFERENCE_SETTINGS_HASH,
                audioProcessingVersion = KokoroGenerationContract.AUDIO_PROCESSING_VERSION,
                engine = "kokoro",
                modelRevision = packageInfo.modelRevision,
                speakerId = packageInfo.speakerId,
                nativeSampleRateHz = packageInfo.nativeSampleRateHz ?: output.sampleRateHz,
                finalSampleRateHz = packageInfo.sampleRateHz,
                frontendVersion = KokoroGenerationContract.frontendVersion(packageInfo),
                resamplerVersion = packageInfo.resamplerVersion,
                runtimeId = packageInfo.runtimeId,
                runtimeVersion = packageInfo.runtimeVersion,
            ),
            sampleRateHz = output.sampleRateHz,
            channels = output.channels,
            durationMs = durationMs,
            writer = { stream -> PcmWavWriter.write(stream, output, output.predDur.size) },
            validator = { file ->
                val info = PcmWavValidator.validate(file)
                require(info.sampleCount == output.pcm.size.toLong()) { "Kokoro WAV sample count is invalid" }
            },
            artifactExtension = "wav",
        )
    }

    override fun close() {
        session.close()
    }
}

/** Closes the run-owned ONNX session on success, failure, and cancellation. */
public class KokoroGenerationExecutor(
    private val openGenerator: (String) -> KokoroSegmentGenerator,
    private val modelPackageIdForRun: (String) -> String?,
    private val executeRun: suspend (String, KokoroSegmentGenerator) -> com.homoludens.citacknjiga.core.generation.BoundedGenerationResult,
) : com.homoludens.citacknjiga.core.generation.GenerationRunExecutor {
    override suspend fun execute(runId: String): com.homoludens.citacknjiga.core.generation.BoundedGenerationResult {
        val generator = openGenerator(modelPackageIdForRun(runId) ?: error("Kokoro run has no model package"))
        return try {
            executeRun(runId, generator)
        } finally {
            generator.close()
        }
    }
}
