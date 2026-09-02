package com.homoludens.citacknjiga.generation

import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.generation.GenerationEngine
import com.homoludens.citacknjiga.core.generation.GenerationEnginePlan
import com.homoludens.citacknjiga.core.generation.GenerationEnginePlanner
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationRequest
import com.homoludens.citacknjiga.core.generation.PlannedGenerationSegment
import com.homoludens.citacknjiga.tts.onnx.KokoroGenerationContract
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor

/** Plans durable Kokoro work from verified package metadata and imported blocks. */
public class KokoroGenerationCoordinator(
    private val modelStore: ModelPackageStore,
    private val preprocessorFactory: () -> SerbianPreprocessor,
) : GenerationEnginePlanner {
    override val engine: GenerationEngine = GenerationEngine.KOKORO

    override fun plan(request: GenerationRequest): GenerationEnginePlan {
        require(request.engine == engine) { "Kokoro planner received a ${request.engine.id} request" }
        val packageInfo = modelStore.activePackage()
            ?: error("No verified Kokoro package is installed")
        require(packageInfo.engine == engine.id) { "Active Kokoro package has invalid engine provenance" }
        val roomPackageId = KokoroGenerationContract.roomPackageId(packageInfo)
        val model = ModelPackageEntity(
            id = roomPackageId,
            packageIdentity = "${packageInfo.packageId}@${packageInfo.packageVersion}",
            packageVersion = packageInfo.packageVersion,
            packageSha256 = packageInfo.identitySha256,
            modelSha256 = packageInfo.modelSha256,
            voiceSha256 = packageInfo.voiceSha256,
            preprocessingVersion = KokoroGenerationContract.PREPROCESSING_VERSION,
            pronunciationVersion = KokoroGenerationContract.PREPROCESSING_VERSION,
            packagePath = "model-packages/active.zip",
            status = ModelPackageStatus.ACTIVE,
            importedAt = System.currentTimeMillis(),
        )
        val preprocessor = preprocessorFactory()
        return GenerationEnginePlan(
            modelPackage = model,
            segments = request.narrationBlocks.map { block ->
                val prepared = preprocessor.process(block.text)
                PlannedGenerationSegment(
                    narrationBlockId = block.id,
                    chapterId = block.chapterId,
                    provenance = GenerationProvenance(
                        generationKey = KokoroGenerationContract.generationKey(packageInfo, prepared.tokenIds),
                        modelPackageId = roomPackageId,
                        modelPackageSha256 = packageInfo.identitySha256,
                        voiceSha256 = packageInfo.voiceSha256,
                        preprocessingVersion = KokoroGenerationContract.PREPROCESSING_VERSION,
                        pronunciationVersion = KokoroGenerationContract.PREPROCESSING_VERSION,
                        inferenceSettingsHash = KokoroGenerationContract.INFERENCE_SETTINGS_HASH,
                        audioProcessingVersion = KokoroGenerationContract.AUDIO_PROCESSING_VERSION,
                        engine = packageInfo.engine,
                        modelRevision = packageInfo.modelRevision,
                        speakerId = packageInfo.speakerId,
                        nativeSampleRateHz = packageInfo.nativeSampleRateHz ?: packageInfo.sampleRateHz,
                        finalSampleRateHz = packageInfo.sampleRateHz,
                        resamplerVersion = packageInfo.resamplerVersion,
                        runtimeId = packageInfo.runtimeId,
                        runtimeVersion = packageInfo.runtimeVersion,
                    ),
                )
            },
        )
    }
}
