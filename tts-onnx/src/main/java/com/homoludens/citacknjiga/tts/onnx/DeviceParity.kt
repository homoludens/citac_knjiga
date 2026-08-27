package com.homoludens.citacknjiga.tts.onnx

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

public data class DeviceParityDeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val apiLevel: Int,
    val abi: String,
)

public data class DeviceParityBuildIdentity(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
)

public data class DeviceParityRuntimeIdentity(
    val coordinate: String = "com.microsoft.onnxruntime:onnxruntime-android:1.29.0",
    val version: String = OnnxRuntimeContract.VERSION,
    val executionProvider: String = "cpu",
    val intraOpThreads: Int = 1,
    val interOpThreads: Int = 1,
    val executionMode: String = "sequential",
)

public data class DeviceParityModelIdentity(
    val packageId: String?,
    val packageVersion: String?,
    val packageSha256: String?,
    val modelSha256: String?,
    val voiceSha256: String?,
)

public data class DeviceParityContext(
    val device: DeviceParityDeviceIdentity,
    val build: DeviceParityBuildIdentity,
    val runtime: DeviceParityRuntimeIdentity = DeviceParityRuntimeIdentity(),
    val model: DeviceParityModelIdentity,
    val evidence: String,
)

/** A desktop ONNX waveform and its declared audio metadata; it contains no source text. */
public data class DesktopOnnxParityVector(
    val id: String,
    val pcm: FloatArray,
    val sampleRateHz: Int = OnnxRuntimeContract.SAMPLE_RATE_HZ,
    val channels: Int = OnnxRuntimeContract.CHANNELS,
    val tokenIds: List<Int> = emptyList(),
    val speed: Float = 1f,
)

public data class DeviceParityMeasurement(
    val value: Double?,
    val unit: String,
    val comparator: String,
    val threshold: Double,
    val pass: Boolean,
)

public data class DeviceParityMetric(
    val pass: Boolean,
    val measurements: Map<String, DeviceParityMeasurement>,
)

public data class DeviceParityVectorResult(
    val vector: String,
    val reference: Map<String, Any?>,
    val candidate: Map<String, Any?>,
    val metrics: Map<String, DeviceParityMetric>,
    val failures: List<String>,
    val ok: Boolean,
)

public data class DeviceParityReport(
    val kind: String = "android-device-parity-report",
    @SerializedName("report_version") val reportVersion: Int = 1,
    val task: String = "build-serbian-audiobook-mvp 4.9",
    val status: String,
    val ok: Boolean,
    val evidence: String,
    val blocker: String?,
    val device: DeviceParityDeviceIdentity,
    val build: DeviceParityBuildIdentity,
    val runtime: DeviceParityRuntimeIdentity,
    val model: DeviceParityModelIdentity,
    val thresholds: DeviceParityThresholdReport,
    @SerializedName("vectors_expected") val vectorsExpected: Int,
    @SerializedName("vectors_evaluated") val vectorsEvaluated: Int,
    val summary: Map<String, DeviceParityMetric>,
    val vectors: List<DeviceParityVectorResult>,
    @SerializedName("created_at_epoch_ms") val createdAtEpochMs: Long,
)

public data class DeviceParityThresholdReport(
    val version: String = DeviceParityThresholds.VERSION,
    @SerializedName("sample_rate_hz") val sampleRateHz: Int = OnnxRuntimeContract.SAMPLE_RATE_HZ,
    @SerializedName("all_vectors_required") val allVectorsRequired: Boolean = true,
    @SerializedName("fail_closed") val failClosed: Boolean = true,
    val metrics: Map<String, Map<String, DeviceParityDeclaration>> = DeviceParityThresholds.METRICS,
)

public data class DeviceParityDeclaration(
    val unit: String,
    val aggregation: String,
    val comparator: String,
    val threshold: Double,
)

/** The immutable v1 declaration mirrored from model-tools/parity/fp32-thresholds-v1.json. */
public object DeviceParityThresholds {
    public const val VERSION: String = "fp32-parity-v1"

    public val METRICS: Map<String, Map<String, DeviceParityDeclaration>> = linkedMapOf(
        "sample_count" to linkedMapOf(
            "absolute_difference_samples" to declaration("samples", "maximum", "==", 0.0),
        ),
        "waveform_error" to linkedMapOf(
            "mean_absolute_error" to declaration("normalized amplitude", "maximum", "<=", 0.01),
            "maximum_absolute_error" to declaration("normalized amplitude", "maximum", "<=", 0.1),
        ),
        "spectral_similarity" to linkedMapOf(
            "stft_magnitude_cosine" to declaration("unitless cosine similarity in [0, 1]", "minimum", ">=", 0.99),
        ),
        "silence" to linkedMapOf(
            "rms_amplitude" to declaration("normalized amplitude RMS", "minimum", ">", 0.001),
            "silent_sample_fraction" to declaration("fraction of samples in [0, 1]", "maximum", "<=", 0.995),
        ),
        "clipping" to linkedMapOf(
            "clipped_sample_count" to declaration("samples", "maximum", "==", 0.0),
            "absolute_peak" to declaration("normalized amplitude", "maximum", "<", 1.0),
        ),
        "invalid_values" to linkedMapOf(
            "non_finite_sample_count" to declaration("samples", "maximum", "==", 0.0),
            "invalid_output_count" to declaration("outputs", "maximum", "==", 0.0),
        ),
    )

    private fun declaration(
        unit: String,
        aggregation: String,
        comparator: String,
        threshold: Double,
    ): DeviceParityDeclaration = DeviceParityDeclaration(unit, aggregation, comparator, threshold)
}

public object DeviceParityEvaluator {
    public fun compare(
        vector: DesktopOnnxParityVector,
        candidate: OnnxTtsOutput,
    ): DeviceParityVectorResult {
        val referenceInfo = outputInfo(vector.pcm, vector.sampleRateHz, vector.channels)
        val candidateInfo = outputInfo(candidate.pcm, candidate.sampleRateHz, candidate.channels)
        val sameShape = referenceInfo.contractOk && candidateInfo.contractOk &&
            vector.pcm.size == candidate.pcm.size
        val waveformMean = if (sameShape) {
            vector.pcm.indices.sumOf { index -> abs(vector.pcm[index].toDouble() - candidate.pcm[index]) } /
                vector.pcm.size
        } else {
            null
        }
        val waveformMax = if (sameShape) {
            vector.pcm.indices.maxOf { index -> abs(vector.pcm[index].toDouble() - candidate.pcm[index]) }
        } else {
            null
        }
        val candidateValues = if (candidateInfo.contractOk && candidateInfo.nonFinite == 0) {
            candidate.pcm
        } else {
            null
        }
        val rms = candidateValues?.let { values -> sqrt(values.map { it.toDouble() * it }.average()) }
        val silentFraction = candidateValues?.let { values ->
            values.count { abs(it.toDouble()) <= SILENCE_LEVEL }.toDouble() / values.size
        }
        val clippedCount = candidateValues?.count { abs(it.toDouble()) >= 1.0 }?.toDouble()
        val absolutePeak = candidateValues?.maxOf { abs(it.toDouble()) }
        val values = mapOf(
            "absolute_difference_samples" to abs(candidate.pcm.size - vector.pcm.size).toDouble(),
            "mean_absolute_error" to waveformMean,
            "maximum_absolute_error" to waveformMax,
            "stft_magnitude_cosine" to if (sameShape) stftMagnitudeCosine(vector.pcm, candidate.pcm) else null,
            "rms_amplitude" to rms,
            "silent_sample_fraction" to silentFraction,
            "clipped_sample_count" to clippedCount,
            "absolute_peak" to absolutePeak,
            "non_finite_sample_count" to candidateInfo.nonFinite.toDouble(),
            "invalid_output_count" to if (candidateInfo.contractOk) 0.0 else 1.0,
        )
        val metrics = linkedMapOf<String, DeviceParityMetric>()
        val failures = mutableListOf<String>()
        for ((metricName, declarations) in DeviceParityThresholds.METRICS) {
            val measurements = linkedMapOf<String, DeviceParityMeasurement>()
            for ((measurementName, declaration) in declarations) {
                val value = values.getValue(measurementName)
                val pass = compareValue(value, declaration.comparator, declaration.threshold)
                measurements[measurementName] = DeviceParityMeasurement(
                    value = value,
                    unit = declaration.unit,
                    comparator = declaration.comparator,
                    threshold = declaration.threshold,
                    pass = pass,
                )
                if (!pass) failures += "${vector.id}: $metricName.$measurementName value=$value " +
                    "${declaration.comparator} threshold=${declaration.threshold}"
            }
            metrics[metricName] = DeviceParityMetric(
                pass = measurements.values.all { it.pass },
                measurements = measurements,
            )
        }
        return DeviceParityVectorResult(
            vector = vector.id,
            reference = referenceInfo.asMap(),
            candidate = candidateInfo.asMap(),
            metrics = metrics,
            failures = failures,
            ok = failures.isEmpty(),
        )
    }

    public fun summarize(results: List<DeviceParityVectorResult>): Map<String, DeviceParityMetric> {
        val summary = linkedMapOf<String, DeviceParityMetric>()
        for ((metricName, declarations) in DeviceParityThresholds.METRICS) {
            val measurements = linkedMapOf<String, DeviceParityMeasurement>()
            for ((measurementName, declaration) in declarations) {
                val values = results.mapNotNull {
                    it.metrics[metricName]?.measurements?.get(measurementName)?.value
                }
                val aggregate = if (values.isEmpty()) null else if (declaration.aggregation == "minimum") {
                    values.minOrNull()
                } else {
                    values.maxOrNull()
                }
                val everyVectorPassed = results.isNotEmpty() && results.all {
                    it.metrics[metricName]?.measurements?.get(measurementName)?.pass == true
                }
                measurements[measurementName] = DeviceParityMeasurement(
                    value = aggregate,
                    unit = declaration.unit,
                    comparator = declaration.comparator,
                    threshold = declaration.threshold,
                    pass = everyVectorPassed && compareValue(aggregate, declaration.comparator, declaration.threshold),
                )
            }
            summary[metricName] = DeviceParityMetric(
                pass = measurements.values.all { it.pass },
                measurements = measurements,
            )
        }
        return summary
    }

    private const val SILENCE_LEVEL: Double = 0.0001
    private const val FFT_SIZE: Int = 1024
    private const val HOP_LENGTH: Int = 256

    private data class OutputInfo(
        val dtype: String,
        val shape: List<Int>,
        val sampleCount: Int,
        val sampleRateHz: Int,
        val channels: Int,
        val nonFinite: Int,
        val contractOk: Boolean,
    ) {
        fun asMap(): Map<String, Any?> = linkedMapOf(
            "dtype" to dtype,
            "shape" to shape,
            "sample_count" to sampleCount,
            "sample_rate_hz" to sampleRateHz,
            "channels" to channels,
            "non_finite_sample_count" to nonFinite,
            "invalid_output_count" to if (contractOk) 0 else 1,
            "contract_ok" to contractOk,
        )
    }

    private fun outputInfo(pcm: FloatArray, sampleRateHz: Int, channels: Int): OutputInfo {
        val nonFinite = pcm.count { !it.isFinite() }
        return OutputInfo(
            dtype = "float32",
            shape = listOf(pcm.size),
            sampleCount = pcm.size,
            sampleRateHz = sampleRateHz,
            channels = channels,
            nonFinite = nonFinite,
            contractOk = pcm.isNotEmpty() && sampleRateHz == OnnxRuntimeContract.SAMPLE_RATE_HZ &&
                channels == OnnxRuntimeContract.CHANNELS,
        )
    }

    private fun compareValue(value: Double?, comparator: String, threshold: Double): Boolean {
        if (value == null || !value.isFinite()) return false
        return when (comparator) {
            "==" -> value == threshold
            "<" -> value < threshold
            "<=" -> value <= threshold
            ">" -> value > threshold
            ">=" -> value >= threshold
            else -> error("unsupported comparator $comparator")
        }
    }

    private fun stftMagnitudeCosine(reference: FloatArray, candidate: FloatArray): Double? {
        if (reference.size != candidate.size || reference.size < FFT_SIZE) return null
        val frameCount = 1 + (reference.size - FFT_SIZE) / HOP_LENGTH
        var dot = 0.0
        var referenceNorm = 0.0
        var candidateNorm = 0.0
        val window = DoubleArray(FFT_SIZE) { index ->
            0.5 - 0.5 * cos(2.0 * Math.PI * index / FFT_SIZE)
        }
        for (frame in 0 until frameCount) {
            val referenceMagnitude = fftMagnitude(reference, frame * HOP_LENGTH, window)
            val candidateMagnitude = fftMagnitude(candidate, frame * HOP_LENGTH, window)
            for (index in referenceMagnitude.indices) {
                dot += referenceMagnitude[index] * candidateMagnitude[index]
                referenceNorm += referenceMagnitude[index] * referenceMagnitude[index]
                candidateNorm += candidateMagnitude[index] * candidateMagnitude[index]
            }
        }
        val denominator = sqrt(referenceNorm) * sqrt(candidateNorm)
        return if (denominator == 0.0) 0.0 else min(1.0, max(0.0, dot / denominator))
    }

    private fun fftMagnitude(values: FloatArray, offset: Int, window: DoubleArray): DoubleArray {
        val real = DoubleArray(FFT_SIZE) { index -> values[offset + index] * window[index] }
        val imaginary = DoubleArray(FFT_SIZE)
        var j = 0
        for (index in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (index < j) {
                val realValue = real[index]
                real[index] = real[j]
                real[j] = realValue
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * Math.PI / length
            val stepReal = cos(angle)
            val stepImaginary = kotlin.math.sin(angle)
            for (start in 0 until FFT_SIZE step length) {
                var currentReal = 1.0
                var currentImaginary = 0.0
                for (index in 0 until length / 2) {
                    val even = start + index
                    val odd = even + length / 2
                    val oddReal = real[odd] * currentReal - imaginary[odd] * currentImaginary
                    val oddImaginary = real[odd] * currentImaginary + imaginary[odd] * currentReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = currentReal * stepReal - currentImaginary * stepImaginary
                    currentImaginary = currentReal * stepImaginary + currentImaginary * stepReal
                    currentReal = nextReal
                }
            }
            length = length shl 1
        }
        return DoubleArray(FFT_SIZE / 2 + 1) { index ->
            sqrt(real[index] * real[index] + imaginary[index] * imaginary[index])
        }
    }
}

public class AndroidDeviceParityRunner(
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    public fun run(
        vectors: List<DesktopOnnxParityVector>,
        context: DeviceParityContext,
        generate: (DesktopOnnxParityVector) -> OnnxTtsOutput,
    ): DeviceParityReport {
        val results = vectors.map { vector ->
            try {
                DeviceParityEvaluator.compare(vector, generate(vector))
            } catch (failure: Throwable) {
                val message = "${vector.id}: runtime error: ${failure::class.simpleName}"
                DeviceParityVectorResult(
                    vector = vector.id,
                    reference = emptyMap(),
                    candidate = emptyMap(),
                    metrics = emptyMap(),
                    failures = listOf(message),
                    ok = false,
                )
            }
        }
        val summary = DeviceParityEvaluator.summarize(results)
        val passed = vectors.isNotEmpty() && results.size == vectors.size && results.all { it.ok }
        return DeviceParityReport(
            status = if (passed) "passed" else "failed",
            ok = passed,
            evidence = context.evidence,
            blocker = null,
            device = context.device,
            build = context.build,
            runtime = context.runtime,
            model = context.model,
            thresholds = DeviceParityThresholdReport(),
            vectorsExpected = vectors.size,
            vectorsEvaluated = results.size,
            summary = summary,
            vectors = results,
            createdAtEpochMs = nowEpochMs(),
        )
    }

    public fun runAndPersist(
        vectors: List<DesktopOnnxParityVector>,
        context: DeviceParityContext,
        reportStore: DeviceParityReportStore,
        generate: (DesktopOnnxParityVector) -> OnnxTtsOutput,
    ): DeviceParityReport = run(vectors, context, generate).also(reportStore::writeAtomic)

    /** Produces a persisted, non-passing report when the legal-blocked package is absent. */
    public fun blocked(
        context: DeviceParityContext,
        blocker: String,
        reportStore: DeviceParityReportStore? = null,
    ): DeviceParityReport = DeviceParityReport(
        status = "blocked",
        ok = false,
        evidence = context.evidence,
        blocker = blocker,
        device = context.device,
        build = context.build,
        runtime = context.runtime,
        model = context.model,
        thresholds = DeviceParityThresholdReport(),
        vectorsExpected = 0,
        vectorsEvaluated = 0,
        summary = emptyMap(),
        vectors = emptyList(),
        createdAtEpochMs = nowEpochMs(),
    ).also { reportStore?.writeAtomic(it) }

    public fun runInstalledAndPersist(
        store: ModelPackageStore,
        vectors: List<DesktopOnnxParityVector>,
        context: DeviceParityContext,
        reportStore: DeviceParityReportStore,
    ): DeviceParityReport {
        val installed = try {
            store.activePackage()
        } catch (failure: Throwable) {
            return blocked(
                context = context,
                blocker = "Installed model package is unavailable (${failure::class.simpleName}).",
                reportStore = reportStore,
            )
        } ?: return blocked(
            context = context,
            blocker = "No verified model package is installed; legal-blocked production package is unavailable.",
            reportStore = reportStore,
        )
        val packageContext = context.copy(
            model = context.model.copy(
                packageId = installed.packageId,
                packageVersion = installed.packageVersion,
                packageSha256 = installed.identitySha256,
                modelSha256 = installed.modelSha256,
                voiceSha256 = installed.voiceSha256,
            ),
        )
        return try {
            OnnxTtsSession.open(store, installed).use { session ->
                runAndPersist(vectors, packageContext, reportStore) { vector ->
                    session.generate(vector.tokenIds, vector.speed)
                }
            }
        } catch (failure: Throwable) {
            runAndPersist(vectors, packageContext, reportStore) {
                throw failure
            }
        }
    }
}

public class DeviceParityReportStore(
    private val directory: File,
    public val reportFile: File = File(directory, "device-parity-report.json"),
) {
    public fun writeAtomic(report: DeviceParityReport): File {
        require(report.directorySafe()) { "device parity report contains an unsupported document field" }
        require(reportFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Device parity report directory is unavailable"
        }
        val temporary = File(reportFile.parentFile, ".${reportFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(
                    GsonBuilder().serializeNulls().setPrettyPrinting().create()
                        .toJson(report).toByteArray(Charsets.UTF_8),
                )
                stream.write('\n'.code)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), reportFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), reportFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return reportFile
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun DeviceParityReport.directorySafe(): Boolean =
        !GsonBuilder().serializeNulls().create().toJson(this).contains("\"text\"")
}
