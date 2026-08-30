package com.homoludens.citacknjiga.playback.export

import kotlin.math.max

/** Read-only source facts used to calculate the export peak before staging starts. */
public data class ExportStorageChapterInput(
    public val sourceFileSizes: List<Long>,
    public val segmentDurationsMs: List<Long>,
    public val format: ExportAudioFormat,
) {
    init {
        require(sourceFileSizes.isNotEmpty()) { "An export chapter needs a source file" }
        require(sourceFileSizes.size == segmentDurationsMs.size) {
            "Export source sizes and durations must have the same length"
        }
        require(sourceFileSizes.all { it > 0L }) { "Export source sizes must be positive" }
        require(segmentDurationsMs.all { it > 0L }) { "Export segment durations must be positive" }
    }
}

public data class ExportStorageEstimate(
    public val targetBytes: Long,
    public val providerTemporaryBytes: Long,
    public val privateTemporaryBytes: Long,
    public val privateDecodeScratchBytes: Long,
    public val metadataBytes: Long,
    public val coverBytes: Long,
    public val manifestBytes: Long,
    public val safetyMarginBytes: Long,
) {
    public val providerRequiredBytes: Long = targetBytes + providerTemporaryBytes + safetyMarginBytes
    public val privateRequiredBytes: Long = privateTemporaryBytes + safetyMarginBytes

    /** The capacity required from the selected SAF provider. */
    public val requiredBytes: Long get() = providerRequiredBytes
}

public enum class ExportStorageScope { PROVIDER, PRIVATE }

public open class ExportStorageException(message: String) : IllegalStateException(message)

public class ExportProviderCapacityUnknownException(
    public val requiredBytes: Long,
) : ExportStorageException(
    "The selected export provider did not report available capacity; it must provide at least " +
        "$requiredBytes bytes including the export and its temporary file. Choose a provider that " +
        "reports capacity or select another destination before export starts.",
)

public class InsufficientExportStorageException(
    public val scope: ExportStorageScope,
    public val availableBytes: Long,
    public val requiredBytes: Long,
    public val estimate: ExportStorageEstimate,
) : ExportStorageException(
    "Insufficient ${scope.name.lowercase()} storage: $availableBytes bytes available, " +
        "$requiredBytes required (target=${estimate.targetBytes}, temporary=" +
        "${if (scope == ExportStorageScope.PROVIDER) estimate.providerTemporaryBytes else estimate.privateTemporaryBytes}, " +
        "margin=${estimate.safetyMarginBytes}). Free space or choose another destination; " +
        "the internal audiobook was not modified.",
)

/** Deterministic, conservative export size model. It never inspects or changes a destination. */
public object ExportStorageEstimator {
    public const val AAC_BITRATE_BPS: Long = 64_000L
    public const val WAV_HEADER_BYTES: Long = 44L
    public const val M4A_CONTAINER_OVERHEAD_BYTES: Long = 4L * 1024L
    public const val METADATA_OVERHEAD_BYTES: Long = 4L * 1024L
    public const val MANIFEST_BASE_BYTES: Long = 4L * 1024L
    public const val MANIFEST_BYTES_PER_CHAPTER: Long = 1L * 1024L
    public const val MANIFEST_BYTES_PER_SEGMENT: Long = 512L
    public const val MANIFEST_BYTES_PER_ATTRIBUTION: Long = 256L
    public const val SAFETY_MARGIN_PERCENT: Int = 10
    public const val MINIMUM_SAFETY_MARGIN_BYTES: Long = 64L * 1024L

    public fun estimate(
        chapters: List<ExportStorageChapterInput>,
        coverBytes: Long = 0L,
        attributionCount: Int = DEFAULT_ATTRIBUTION_COUNT,
    ): ExportStorageEstimate {
        require(chapters.isNotEmpty()) { "An export needs at least one chapter" }
        require(coverBytes >= 0L) { "Cover size cannot be negative" }
        require(attributionCount >= 0) { "Attribution count cannot be negative" }

        val chapterBytes = chapters.map(::chapterBytes)
        val audioBytes = chapterBytes.sumChecked()
        val metadataBytes = checkedMultiply(chapters.size.toLong() + 1L, METADATA_OVERHEAD_BYTES)
        val manifestBytes = MANIFEST_BASE_BYTES +
            checkedMultiply(chapters.size.toLong(), MANIFEST_BYTES_PER_CHAPTER) +
            checkedMultiply(chapters.sumOf { it.sourceFileSizes.size }.toLong(), MANIFEST_BYTES_PER_SEGMENT) +
            checkedMultiply(attributionCount.toLong(), MANIFEST_BYTES_PER_ATTRIBUTION)
        val targetBytes = checkedSum(audioBytes, coverBytes, metadataBytes, manifestBytes)
        val providerTemporaryBytes = max(
            chapterBytes.maxOrNull() ?: 0L,
            max(coverBytes, manifestBytes),
        )
        val privateDecodeScratchBytes = chapters
            .filter { it.format != ExportAudioFormat.WAV }
            .maxOfOrNull { chapter -> pcmBytes(chapter.segmentDurationsMs.sumChecked()) }
            ?: 0L
        val privateTemporaryBytes = checkedSum(audioBytes, privateDecodeScratchBytes)
        val baseBytes = checkedSum(targetBytes, providerTemporaryBytes)
        val percentageMargin = ((baseBytes * SAFETY_MARGIN_PERCENT) + 99L) / 100L

        return ExportStorageEstimate(
            targetBytes = targetBytes,
            providerTemporaryBytes = providerTemporaryBytes,
            privateTemporaryBytes = privateTemporaryBytes,
            privateDecodeScratchBytes = privateDecodeScratchBytes,
            metadataBytes = metadataBytes,
            coverBytes = coverBytes,
            manifestBytes = manifestBytes,
            safetyMarginBytes = max(MINIMUM_SAFETY_MARGIN_BYTES, percentageMargin),
        )
    }

    private fun chapterBytes(chapter: ExportStorageChapterInput): Long {
        val durationMs = chapter.segmentDurationsMs.sumChecked()
        val sourceBytes = chapter.sourceFileSizes.sumChecked()
        return when (chapter.format) {
            ExportAudioFormat.WAV -> checkedSum(max(sourceBytes, pcmBytes(durationMs)), WAV_HEADER_BYTES)
            ExportAudioFormat.M4A, ExportAudioFormat.AUTO ->
                checkedSum(max(sourceBytes, encodedBytes(durationMs)), M4A_CONTAINER_OVERHEAD_BYTES)
        }
    }

    private fun pcmBytes(durationMs: Long): Long = checkedSum(
        ceilDivide(checkedMultiply(checkedMultiply(durationMs, 24_000L), 2L), 1_000L),
        WAV_HEADER_BYTES,
    )

    private fun encodedBytes(durationMs: Long): Long = ceilDivide(
        checkedMultiply(durationMs, AAC_BITRATE_BPS),
        8_000L,
    )

    private fun List<Long>.sumChecked(): Long = fold(0L) { total, value -> checkedSum(total, value) }

    private fun checkedSum(vararg values: Long): Long = values.fold(0L, Math::addExact)

    private fun checkedMultiply(left: Long, right: Long): Long = Math.multiplyExact(left, right)

    private fun ceilDivide(numerator: Long, denominator: Long): Long =
        numerator / denominator + if (numerator % denominator == 0L) 0L else 1L

    public const val DEFAULT_ATTRIBUTION_COUNT: Int = 2
}

/** Applies both private-scratch and selected-provider checks before any export staging. */
public class ExportStoragePreflight(
    private val privateAvailableBytes: () -> Long,
) {
    public fun requireCapacity(
        destination: SafDocumentTree,
        estimate: ExportStorageEstimate,
    ) {
        val providerAvailable = destination.capabilities.availableBytes
        if (providerAvailable == null || providerAvailable < 0L) {
            throw ExportProviderCapacityUnknownException(estimate.providerRequiredBytes)
        }
        if (providerAvailable < estimate.providerRequiredBytes) {
            throw InsufficientExportStorageException(
                scope = ExportStorageScope.PROVIDER,
                availableBytes = providerAvailable.coerceAtLeast(0L),
                requiredBytes = estimate.providerRequiredBytes,
                estimate = estimate,
            )
        }
        val privateAvailable = privateAvailableBytes().coerceAtLeast(0L)
        if (privateAvailable < estimate.privateRequiredBytes) {
            throw InsufficientExportStorageException(
                scope = ExportStorageScope.PRIVATE,
                availableBytes = privateAvailable,
                requiredBytes = estimate.privateRequiredBytes,
                estimate = estimate,
            )
        }
    }
}
