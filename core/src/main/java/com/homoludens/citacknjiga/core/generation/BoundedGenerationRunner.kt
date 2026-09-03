package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.core.storage.GenerationStoragePolicy
import com.homoludens.citacknjiga.core.storage.GenerationStorageRequest
import com.homoludens.citacknjiga.core.storage.PublishedArtifact
import com.homoludens.citacknjiga.core.storage.StorageCapacityCheck
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

public data class ClaimedGenerationSegment(
    public val segment: AudioSegmentEntity,
    public val block: NarrationBlockEntity,
)

public data class GenerationProvenance(
    public val generationKey: String,
    public val modelPackageId: String?,
    public val modelPackageSha256: String,
    public val voiceSha256: String,
    public val preprocessingVersion: String,
    public val pronunciationVersion: String,
    public val inferenceSettingsHash: String,
    public val audioProcessingVersion: String,
    public val engine: String? = null,
    public val modelRevision: String? = null,
    public val speakerId: Int? = null,
    public val nativeSampleRateHz: Int? = null,
    public val finalSampleRateHz: Int? = null,
    public val frontendVersion: String? = null,
    public val resamplerVersion: String? = null,
    public val runtimeId: String? = null,
    public val runtimeVersion: String? = null,
) {
    init {
        require(generationKey.isNotBlank()) { "Generation key cannot be blank" }
        require(modelPackageSha256.isNotBlank()) { "Model package checksum cannot be blank" }
        require(voiceSha256.isNotBlank()) { "Voice checksum cannot be blank" }
    }
}

/** A validated, bounded output supplied by one inference call. */
public data class GeneratedSegmentAudio(
    public val provenance: GenerationProvenance,
    public val sampleRateHz: Int,
    public val channels: Int,
    public val durationMs: Long,
    public val writer: (OutputStream) -> Unit,
    public val validator: (File) -> Unit,
    public val artifactExtension: String = "m4a",
    public val cleanup: () -> Unit = {},
) {
    init {
        require(sampleRateHz > 0) { "Audio sample rate must be positive" }
        require(channels > 0) { "Audio channel count must be positive" }
        require(durationMs > 0) { "Audio duration must be positive" }
        require(Regex("[a-z0-9]+") matches artifactExtension) { "Audio artifact extension is invalid" }
    }
}

public fun interface SegmentGenerator {
    public suspend fun generate(
        segment: AudioSegmentEntity,
        block: NarrationBlockEntity,
    ): GeneratedSegmentAudio
}

public interface GenerationStateGateway {
    public fun findGenerationRun(runId: String): GenerationRunEntity?

    public fun startGenerationRun(runId: String): GenerationRunEntity

    /** Selects and claims exactly one pending segment in the same database transaction. */
    public fun claimNextSegment(runId: String): ClaimedGenerationSegment?

    public fun completeAudioSegment(
        segmentId: String,
        published: PublishedArtifact,
        audio: GeneratedSegmentAudio,
    ): AudioSegmentEntity

    public fun failAudioSegment(segmentId: String, error: GenerationError): AudioSegmentEntity

    public fun retryAudioSegment(segmentId: String): AudioSegmentEntity

    public fun releaseAudioSegment(segmentId: String): AudioSegmentEntity

    public fun failGenerationRun(runId: String, error: GenerationError): GenerationRunEntity

    public fun finishGenerationRun(runId: String): GenerationRunEntity

    /** Publication-capable implementations serialize this with project deletion. */
    public fun <T> withProjectPublicationLock(projectId: String, action: () -> T): T? = action()
}

public enum class BoundedGenerationStatus {
    COMPLETED,
    PAUSED,
    CANCELLED,
    FAILED,
}

public data class BoundedGenerationResult(
    public val runId: String,
    public val status: BoundedGenerationStatus,
    public val generatedSegmentIds: List<String>,
    public val failedSegmentIds: List<String>,
)

/** Runs one persisted segment at a time and never owns scheduling or notifications. */
public class BoundedGenerationRunner(
    private val state: GenerationStateGateway,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val generator: SegmentGenerator,
    private val retryPolicy: GenerationRetryPolicy = GenerationRetryPolicy(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val storagePolicy: GenerationStoragePolicy? = null,
    private val storageRequests: List<GenerationStorageRequest> = emptyList(),
) {
    public suspend fun run(runId: String): BoundedGenerationResult = withContext(ioDispatcher) {
        storagePolicy?.requireCapacity(storageRequests)
        val started = state.startGenerationRun(runId)
        val generated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val remainingStorage = storageRequests.associateBy { it.segmentId }.toMutableMap()
        if (started.status != GenerationRunStatus.RUNNING) {
            return@withContext result(started, generated, failed)
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val current = state.findGenerationRun(runId) ?: error("Missing generation run $runId")
            when (current.status) {
                GenerationRunStatus.PAUSED,
                GenerationRunStatus.CANCELLED,
                -> return@withContext result(current, generated, failed)
                GenerationRunStatus.RUNNING -> Unit
                else -> return@withContext result(current, generated, failed)
            }

            val capacityFailure = storagePolicy?.let { policy ->
                policy.check(remainingStorage.values)
                    .takeUnless(StorageCapacityCheck::hasCapacity)
                    ?.let(policy::insufficientStorage)
            }
            if (capacityFailure != null) {
                return@withContext failStorageRun(runId, capacityFailure, generated, failed)
            }
            val claimed = state.claimNextSegment(runId)
                ?: return@withContext result(state.finishGenerationRun(runId), generated, failed)
            var failurePhase = GenerationFailurePhase.INFERENCE
            var generatedAudio: GeneratedSegmentAudio? = null
            try {
                currentCoroutineContext().ensureActive()
                val audio = generator.generate(claimed.segment, claimed.block).also { generatedAudio = it }
                requireCompatibleProvenance(current, audio)
                claimed.segment.generationKey?.let { expected ->
                    checkProvenance(audio.provenance.generationKey == expected, "different generation key")
                }
                currentCoroutineContext().ensureActive()
                failurePhase = GenerationFailurePhase.PUBLICATION
                val published = state.withProjectPublicationLock(current.bookProjectId) {
                    val artifact = artifactStore.publish(
                        ownerId = "generation-$runId-${claimed.segment.id}",
                        destination = publicationDestination(current, claimed.segment, audio.artifactExtension),
                        writer = audio.writer,
                        validator = audio.validator,
                    )
                    // Publication and its database checkpoint are one atomic segment from the runner's view.
                    state.completeAudioSegment(claimed.segment.id, artifact, audio)
                    artifact
                } ?: throw CancellationException("Project ${current.bookProjectId} is being deleted")
                generated += claimed.segment.id
                remainingStorage.remove(claimed.segment.id)
                val afterFailure = storagePolicy?.let { policy ->
                    policy.check(remainingStorage.values)
                        .takeUnless(StorageCapacityCheck::hasCapacity)
                        ?.let(policy::insufficientStorage)
                }
                if (afterFailure != null) {
                    return@withContext failStorageRun(runId, afterFailure, generated, failed)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    state.releaseAudioSegment(claimed.segment.id)
                }
                throw cancelled
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    state.failAudioSegment(
                        claimed.segment.id,
                        GenerationFailurePolicy.classify(failure, failurePhase).error,
                    )
                }
                val classified = GenerationFailurePolicy.classify(failure, failurePhase)
                if (retryPolicy.shouldRetry(classified, claimed.segment.attemptCount)) {
                    withContext(NonCancellable) {
                        state.retryAudioSegment(claimed.segment.id)
                    }
                } else {
                    failed += claimed.segment.id
                }
            } finally {
                runCatching { generatedAudio?.cleanup?.invoke() }
            }
        }
        error("Generation runner loop terminated unexpectedly")
    }

    private suspend fun failStorageRun(
        runId: String,
        failure: GenerationFailureException,
        generated: List<String>,
        failed: List<String>,
    ): BoundedGenerationResult = withContext(NonCancellable) {
        result(
            state.failGenerationRun(
                runId,
                GenerationFailurePolicy.classify(failure, GenerationFailurePhase.PUBLICATION).error,
            ),
            generated,
            failed,
        )
    }

    private fun requireCompatibleProvenance(
        run: GenerationRunEntity,
        audio: GeneratedSegmentAudio,
    ) {
        val provenance = audio.provenance
        checkProvenance(run.modelPackageId == provenance.modelPackageId, "different model package")
        checkProvenance(run.preprocessingVersion == provenance.preprocessingVersion, "different preprocessing version")
        checkProvenance(run.pronunciationVersion == provenance.pronunciationVersion, "different pronunciation version")
        checkProvenance(run.inferenceSettingsHash == provenance.inferenceSettingsHash, "different inference settings")
        checkProvenance(run.audioProcessingVersion == provenance.audioProcessingVersion, "different audio-processing version")
        checkProvenance(audio.sampleRateHz == 24_000 && audio.channels == 1, "audio is not 24 kHz mono")
        checkProvenance(run.engine == provenance.engine, "different TTS engine")
        checkProvenance(run.modelRevision == provenance.modelRevision, "different model revision")
        checkProvenance(run.speakerId == provenance.speakerId, "different speaker")
        checkProvenance(run.frontendVersion == provenance.frontendVersion, "different frontend")
        checkProvenance(run.nativeSampleRate == provenance.nativeSampleRateHz, "different native sample rate")
        checkProvenance(run.finalSampleRate == provenance.finalSampleRateHz, "different final sample rate")
        checkProvenance(run.resamplerVersion == provenance.resamplerVersion, "different resampler")
        checkProvenance(run.runtimeId == provenance.runtimeId, "different runtime")
        checkProvenance(run.runtimeVersion == provenance.runtimeVersion, "different runtime version")
    }

    /** Never overwrite a file retained by a previous verified Room checkpoint. */
    private fun publicationDestination(
        run: GenerationRunEntity,
        segment: AudioSegmentEntity,
        artifactExtension: String,
    ): File {
        val preferred = storage.readySegmentAudio(
            run.bookProjectId,
            segment.chapterId,
            segment.id,
            "${segment.id}.$artifactExtension",
        )
        return if (segment.audioPath.isNullOrBlank() && !preferred.isFile) {
            preferred
        } else {
            storage.readySegmentAudio(
                run.bookProjectId,
                segment.chapterId,
                segment.id,
                "${segment.id}-${UUID.randomUUID()}.$artifactExtension",
            )
        }
    }

    private fun checkProvenance(condition: Boolean, detail: String) {
        if (!condition) {
            throw GenerationFailureException(
                category = GenerationFailureCategory.PROVENANCE,
                stableCode = "PROVENANCE_MISMATCH",
                message = "Generated segment uses $detail",
            )
        }
    }

    private fun result(
        run: GenerationRunEntity,
        generated: List<String>,
        failed: List<String>,
    ): BoundedGenerationResult = BoundedGenerationResult(
        runId = run.id,
        status = when (run.status) {
            GenerationRunStatus.COMPLETED -> BoundedGenerationStatus.COMPLETED
            GenerationRunStatus.PAUSED -> BoundedGenerationStatus.PAUSED
            GenerationRunStatus.CANCELLED -> BoundedGenerationStatus.CANCELLED
            GenerationRunStatus.FAILED -> BoundedGenerationStatus.FAILED
            else -> BoundedGenerationStatus.FAILED
        },
        generatedSegmentIds = generated.toList(),
        failedSegmentIds = failed.toList(),
    )
}
