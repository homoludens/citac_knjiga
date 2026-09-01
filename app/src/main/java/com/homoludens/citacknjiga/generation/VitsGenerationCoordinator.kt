package com.homoludens.citacknjiga.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.generation.GenerationWorkScheduler
import com.homoludens.citacknjiga.tts.onnx.VitsGenerationContract
import com.homoludens.citacknjiga.tts.onnx.VitsModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.VitsSerbianFrontend
import java.util.UUID

public data class QueuedVitsGeneration(
    public val runId: String,
    public val segmentId: String,
    public val generationKey: String,
)

/** Creates one durable VITS segment from already projected Room content. */
public class VitsGenerationCoordinator(
    private val database: AudiobookDatabase,
    private val vitsStore: VitsModelPackageStore,
    private val schedulerProvider: () -> GenerationWorkScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    public fun queueBlock(
        projectId: String,
        chapterId: String,
        blockId: String,
    ): QueuedVitsGeneration {
        val dao = database.audiobookDao()
        val packageInfo = vitsStore.activePackage()
            ?: error("No qualified Serbian VITS package is installed")
        val project = dao.findProjectById(projectId) ?: error("Project does not exist")
        val chapter = dao.findChapterById(chapterId) ?: error("Chapter does not exist")
        require(chapter.bookProjectId == project.id) { "Chapter belongs to another project" }
        val block = dao.findNarrationBlockById(blockId) ?: error("Narration block does not exist")
        require(block.chapterId == chapter.id) { "Narration block belongs to another chapter" }
        require(block.blockType != NarrationBlockType.SKIPPED && block.sourceText.isNotBlank()) {
            "Narration block has no supported text"
        }
        require(dao.findAllAudioSegments().none {
            it.narrationBlockId == block.id && it.status != AudioSegmentStatus.STALE
        }) { "Narration block already has generation work or audio" }

        val vocabulary = com.homoludens.citacknjiga.tts.onnx.VitsVocabulary.read(vitsStore, packageInfo)
        val prepared = VitsSerbianFrontend(
            vocabulary,
            com.homoludens.citacknjiga.tts.onnx.VitsVocabulary.BLANK_ID,
        ).process(block.sourceText)
        val generationKey = VitsGenerationContract.generationKey(packageInfo, prepared.tokenIds)
        val now = clock()
        val roomPackageId = VitsGenerationContract.roomPackageId(packageInfo)
        val runId = "vits-${UUID.randomUUID()}"
        val segmentId = "$runId-segment"
        val inferenceHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH
        val preprocessing = VitsGenerationContract.PREPROCESSING_VERSION
        val model = ModelPackageEntity(
            id = roomPackageId,
            packageIdentity = "${packageInfo.packageId}@${packageInfo.packageVersion}",
            packageVersion = packageInfo.packageVersion,
            packageSha256 = packageInfo.identitySha256,
            modelSha256 = packageInfo.modelSha256,
            voiceSha256 = packageInfo.voiceSha256,
            preprocessingVersion = preprocessing,
            pronunciationVersion = preprocessing,
            packagePath = "model-packages/vits-active.zip",
            status = ModelPackageStatus.INSTALLED,
            importedAt = now,
        )
        val run = GenerationRunEntity(
            id = runId,
            bookProjectId = project.id,
            modelPackageId = roomPackageId,
            preprocessingVersion = preprocessing,
            pronunciationVersion = preprocessing,
            inferenceSettingsHash = inferenceHash,
            audioProcessingVersion = VitsGenerationContract.AUDIO_PROCESSING_VERSION,
            requestedAt = now,
            engine = packageInfo.engine,
            modelRevision = packageInfo.modelRevision,
            speakerId = packageInfo.speakerId,
            frontendVersion = packageInfo.frontendVersion,
            nativeSampleRate = packageInfo.nativeSampleRateHz,
            finalSampleRate = packageInfo.sampleRateHz,
            resamplerVersion = packageInfo.resamplerVersion,
            runtimeId = packageInfo.runtimeId,
            runtimeVersion = packageInfo.runtimeVersion,
        )
        val segment = AudioSegmentEntity(
            id = segmentId,
            chapterId = chapter.id,
            narrationBlockId = block.id,
            sequence = dao.findAllAudioSegments().filter { it.chapterId == chapter.id }
                .maxOfOrNull { it.sequence }?.plus(1) ?: 0,
            chunkOrdinal = 0,
            generationKey = generationKey,
            generationRunId = runId,
            modelPackageId = roomPackageId,
            modelPackageSha256 = packageInfo.identitySha256,
            voiceSha256 = packageInfo.voiceSha256,
            preprocessingVersion = preprocessing,
            pronunciationVersion = preprocessing,
            inferenceSettingsHash = inferenceHash,
            audioProcessingVersion = VitsGenerationContract.AUDIO_PROCESSING_VERSION,
            createdAt = now,
            updatedAt = now,
            engine = packageInfo.engine,
            modelRevision = packageInfo.modelRevision,
            speakerId = packageInfo.speakerId,
            frontendVersion = packageInfo.frontendVersion,
            nativeSampleRate = packageInfo.nativeSampleRateHz,
            finalSampleRate = packageInfo.sampleRateHz,
            resamplerVersion = packageInfo.resamplerVersion,
            runtimeId = packageInfo.runtimeId,
            runtimeVersion = packageInfo.runtimeVersion,
        )
        database.runInTransaction {
            val existing = dao.findModelPackageById(roomPackageId)
            if (existing == null) {
                dao.insertModelPackage(model)
            } else {
                require(existing.packageIdentity == model.packageIdentity &&
                    existing.packageSha256 == model.packageSha256 &&
                    existing.modelSha256 == model.modelSha256 &&
                    existing.voiceSha256 == model.voiceSha256
                ) { "Registered VITS package identity changed" }
            }
            dao.insertGenerationRun(run)
            dao.insertAudioSegment(segment)
        }
        schedulerProvider().enqueue(runId)
        return QueuedVitsGeneration(runId, segmentId, generationKey)
    }
}
