package com.homoludens.citacknjiga.core.storage

import com.homoludens.citacknjiga.core.generation.GenerationFailureCategory
import com.homoludens.citacknjiga.core.generation.GenerationFailureException
import java.io.File
import kotlin.math.max

/** One pending segment's conservative ready-audio estimate. */
public data class GenerationStorageRequest(
    public val segmentId: String,
    public val estimatedAudioBytes: Long,
) {
    init {
        require(segmentId.isNotBlank()) { "Storage estimate segment id cannot be blank" }
        require(estimatedAudioBytes >= 0) { "Storage estimate cannot be negative" }
    }
}

public data class GenerationStorageEstimate(
    public val temporaryBytes: Long,
    public val readyAudioBytes: Long,
    public val safetyMarginBytes: Long,
) {
    public val requiredBytes: Long = temporaryBytes + readyAudioBytes + safetyMarginBytes
}

public data class StorageCapacityCheck(
    public val estimate: GenerationStorageEstimate,
    public val availableBytes: Long,
) {
    public val hasCapacity: Boolean = availableBytes >= estimate.requiredBytes
}

/** Estimates private generation growth and fails closed when capacity is insufficient. */
public class GenerationStoragePolicy(
    private val storage: AppPrivateStorage,
    private val safetyMarginPercent: Int = DEFAULT_SAFETY_MARGIN_PERCENT,
    private val minimumSafetyMarginBytes: Long = DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES,
    private val availableBytes: () -> Long = { storage.rootDirectory.usableSpace },
) {
    init {
        require(safetyMarginPercent >= 0) { "Storage safety margin cannot be negative" }
        require(minimumSafetyMarginBytes >= 0) { "Minimum storage safety margin cannot be negative" }
    }

    public fun estimate(requests: Collection<GenerationStorageRequest>): GenerationStorageEstimate {
        val ids = requests.map { it.segmentId }
        require(ids.size == ids.toSet().size) { "Storage estimates must have unique segment ids" }
        val readyBytes = requests.sumOf(GenerationStorageRequest::estimatedAudioBytes)
        val temporaryBytes = requests.maxOfOrNull(GenerationStorageRequest::estimatedAudioBytes) ?: 0L
        val baseBytes = temporaryBytes + readyBytes
        val percentageMargin = if (baseBytes == 0L || safetyMarginPercent == 0) {
            0L
        } else {
            ((baseBytes * safetyMarginPercent) + 99L) / 100L
        }
        return GenerationStorageEstimate(
            temporaryBytes = temporaryBytes,
            readyAudioBytes = readyBytes,
            safetyMarginBytes = max(minimumSafetyMarginBytes, percentageMargin),
        )
    }

    public fun check(requests: Collection<GenerationStorageRequest>): StorageCapacityCheck =
        StorageCapacityCheck(estimate(requests), availableBytes().coerceAtLeast(0L))

    public fun requireCapacity(requests: Collection<GenerationStorageRequest>): GenerationStorageEstimate {
        val check = check(requests)
        if (!check.hasCapacity) throw insufficientStorage(check)
        return check.estimate
    }

    public fun insufficientStorage(check: StorageCapacityCheck): GenerationFailureException =
        GenerationFailureException(
            category = GenerationFailureCategory.STORAGE,
            stableCode = "INSUFFICIENT_STORAGE",
            message = "Private storage has ${check.availableBytes} bytes, but generation requires " +
                "${check.estimate.requiredBytes} bytes including a ${check.estimate.safetyMarginBytes}-byte safety margin; " +
                "cleanup options are selected temporary files or an explicit review of orphan audio",
        )

    public companion object {
        public const val DEFAULT_SAFETY_MARGIN_PERCENT: Int = 10
        public const val DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES: Long = 64L * 1024L
    }
}

public enum class GenerationCleanupChoice {
    NONE,
    STALE_TEMPORARY,
    ORPHAN_READY_AUDIO,
}

public data class GenerationCleanupResult(
    public val choice: GenerationCleanupChoice,
    public val deletedFileCount: Int,
    public val availableBytesAfter: Long,
    /** Cleanup is restricted to generation artifacts and never removes project data. */
    public val preservesProjectData: Boolean = true,
)

/** Applies only an explicitly selected generation-artifact cleanup operation. */
public class GenerationStorageCleanup(
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore = AtomicArtifactStore(storage),
    private val availableBytes: () -> Long = { storage.rootDirectory.usableSpace },
) {
    public fun cleanup(
        choice: GenerationCleanupChoice,
        referencedReadyAudio: Collection<File> = emptyList(),
        maxAgeMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): GenerationCleanupResult {
        val deleted = when (choice) {
            GenerationCleanupChoice.NONE -> 0
            GenerationCleanupChoice.STALE_TEMPORARY ->
                artifactStore.cleanupStaleTemporaryFiles(maxAgeMillis, nowMillis)
            GenerationCleanupChoice.ORPHAN_READY_AUDIO ->
                artifactStore.cleanupOrphanFiles(
                    storage.readyAudioDirectory,
                    referencedReadyAudio,
                    maxAgeMillis,
                    nowMillis,
                )
        }
        return GenerationCleanupResult(choice, deleted, availableBytes().coerceAtLeast(0L))
    }
}
