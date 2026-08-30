package com.homoludens.citacknjiga.tts.onnx

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

public const val AAC_BENCHMARK_SAMPLE_RATE_HZ: Int = 24_000
public const val AAC_BENCHMARK_CHANNELS: Int = 1

public data class AacBenchmarkSegment(
    val id: String,
    val label: String,
    val pcm16: ShortArray,
)

public data class AacBenchmarkInput(
    val id: String,
    val sampleRateHz: Int,
    val channels: Int,
    val segments: List<AacBenchmarkSegment>,
    val consonantWindows: List<AacConsonantWindow>,
) {
    public val pcm16: ShortArray
        get() = ShortArray(segments.sumOf { it.pcm16.size }).also { output ->
            var offset = 0
            segments.forEach { segment ->
                segment.pcm16.copyInto(output, offset)
                offset += segment.pcm16.size
            }
        }

    public val sampleCount: Int get() = segments.sumOf { it.pcm16.size }
    public val durationUs: Long get() = sampleCount.toLong() * 1_000_000L / sampleRateHz
}

public data class AacConsonantWindow(
    val id: String,
    val label: String,
    @SerializedName("start_sample") val startSample: Int,
    @SerializedName("sample_count") val sampleCount: Int,
)

/** Synthetic, deterministic audio stresses Serbian consonant bands without shipping audio artifacts. */
public object AacBenchmarkFixture {
    private const val SEGMENT_SAMPLES = AAC_BENCHMARK_SAMPLE_RATE_HZ / 2
    private const val WINDOW_START = SEGMENT_SAMPLES / 4
    private const val WINDOW_SAMPLES = SEGMENT_SAMPLES / 2

    private val consonants = listOf(
        "s (sibilant)",
        "z (voiced sibilant)",
        "š (postalveolar sibilant)",
        "ž (voiced postalveolar)",
        "č (affricate)",
        "ć (soft affricate)",
        "đ (voiced affricate)",
        "lj/nj/dž (digraphs)",
    )

    public fun create(): AacBenchmarkInput {
        val segments = consonants.mapIndexed { index, label ->
            AacBenchmarkSegment(
                id = "segment-${index + 1}",
                label = label,
                pcm16 = segmentSamples(index),
            )
        }
        return AacBenchmarkInput(
            id = "serbian-consonants-synthetic-v1",
            sampleRateHz = AAC_BENCHMARK_SAMPLE_RATE_HZ,
            channels = AAC_BENCHMARK_CHANNELS,
            segments = segments,
            consonantWindows = segments.mapIndexed { index, segment ->
                AacConsonantWindow(
                    id = segment.id,
                    label = segment.label,
                    startSample = index * SEGMENT_SAMPLES + WINDOW_START,
                    sampleCount = WINDOW_SAMPLES,
                )
            },
        )
    }

    public fun pcm16Bytes(input: AacBenchmarkInput = create()): ByteArray =
        input.pcm16.toLittleEndianBytes()

    public fun wavBytes(input: AacBenchmarkInput = create()): ByteArray {
        val data = pcm16Bytes(input)
        return ByteArray(44 + data.size).also { wav ->
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(wav, 0)
            wav.writeInt(4, 36 + data.size)
            "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(wav, 8)
            wav.writeInt(16, 16)
            wav.writeShort(20, 1)
            wav.writeShort(22, input.channels)
            wav.writeInt(24, input.sampleRateHz)
            wav.writeInt(28, input.sampleRateHz * input.channels * 2)
            wav.writeShort(32, input.channels * 2)
            wav.writeShort(34, 16)
            "data".toByteArray(Charsets.US_ASCII).copyInto(wav, 36)
            wav.writeInt(40, data.size)
            data.copyInto(wav, 44)
        }
    }

    public fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun segmentSamples(index: Int): ShortArray = ShortArray(SEGMENT_SAMPLES) { sample ->
        val time = sample.toDouble() / AAC_BENCHMARK_SAMPLE_RATE_HZ
        val envelope = if (sample in WINDOW_START until WINDOW_START + WINDOW_SAMPLES) 1.0 else 0.18
        val voiced = 0.22 * sin(2.0 * PI * (125 + index * 7) * time)
        val harmonic = 0.10 * sin(2.0 * PI * (250 + index * 11) * time)
        val noise = deterministicNoise(index, sample)
        val consonant = when (index) {
            0, 1 -> 0.42 * noise * 0.72
            2, 3 -> 0.50 * noise * 0.92
            4, 5, 6 -> 0.46 * noise + 0.24 * sin(2.0 * PI * (3_600 + index * 250) * time)
            else -> 0.34 * noise + 0.20 * sin(2.0 * PI * 1_800 * time)
        }
        (32767.0 * (voiced + harmonic + envelope * consonant).coerceIn(-0.92, 0.92)).toInt().toShort()
    }

    private fun deterministicNoise(segment: Int, sample: Int): Double {
        var state = (0x13579BDF + segment * 0x1020304 + sample * 0x45D9F3B).toInt()
        state = state xor (state ushr 16)
        state *= 0x7FEB352D
        state = state xor (state ushr 15)
        return ((state ushr 8) and 0xFFFF) / 32767.5 - 1.0
    }

    private fun ShortArray.toLittleEndianBytes(): ByteArray = ByteArray(size * 2).also { bytes ->
        forEachIndexed { index, value ->
            bytes[index * 2] = value.toInt().toByte()
            bytes[index * 2 + 1] = (value.toInt() ushr 8).toByte()
        }
    }

    private fun ByteArray.writeShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        writeShort(offset, value)
        writeShort(offset + 2, value ushr 16)
    }
}

public data class AacBenchmarkInputReport(
    val id: String,
    @SerializedName("sample_rate_hz") val sampleRateHz: Int,
    val channels: Int,
    @SerializedName("sample_count") val sampleCount: Int,
    @SerializedName("duration_us") val durationUs: Long,
    @SerializedName("pcm_sha256") val pcmSha256: String,
    @SerializedName("wav_sha256") val wavSha256: String,
    val segments: List<AacBenchmarkSegmentReport>,
    @SerializedName("consonant_windows") val consonantWindows: List<AacConsonantWindow>,
)

public data class AacBenchmarkSegmentReport(
    val id: String,
    val label: String,
    @SerializedName("sample_count") val sampleCount: Int,
    @SerializedName("start_sample") val startSample: Int,
)

public data class AacBenchmarkBoundaryReport(
    @SerializedName("segment_count") val segmentCount: Int,
    @SerializedName("total_gap_us") val totalGapUs: Long,
    @SerializedName("total_trim_us") val totalTrimUs: Long,
    @SerializedName("max_absolute_drift_us") val maxAbsoluteDriftUs: Long,
)

public data class AacConsonantQualityMeasurement(
    val id: String,
    val label: String,
    @SerializedName("reference_rms") val referenceRms: Double?,
    @SerializedName("decoded_rms") val decodedRms: Double?,
    @SerializedName("rms_ratio") val rmsRatio: Double?,
    @SerializedName("reference_zero_crossing_rate") val referenceZeroCrossingRate: Double?,
    @SerializedName("decoded_zero_crossing_rate") val decodedZeroCrossingRate: Double?,
)

public data class AacConsonantQualityReport(
    val method: String = "aac-wav-consonant-v1",
    val status: String = "manual_listening_pending",
    @SerializedName("alignment_offset_samples") val alignmentOffsetSamples: Int?,
    @SerializedName("decoded_sample_count") val decodedSampleCount: Int?,
    @SerializedName("window_measurements") val windowMeasurements: List<AacConsonantQualityMeasurement>,
)

public data class AacBenchmarkBitrateReport(
    @SerializedName("requested_bitrate_bps") val requestedBitrateBps: Int,
    val available: Boolean,
    @SerializedName("codec_name") val codecName: String?,
    val status: String,
    val reason: String?,
    @SerializedName("full_output_size_bytes") val fullOutputSizeBytes: Long?,
    @SerializedName("full_encoded_duration_us") val fullEncodedDurationUs: Long?,
    @SerializedName("full_encode_elapsed_ms") val fullEncodeElapsedMs: Long?,
    val boundary: AacBenchmarkBoundaryReport?,
    val quality: AacConsonantQualityReport?,
)

public data class AacBenchmarkReport(
    val kind: String = "android-aac-m4a-benchmark",
    @SerializedName("report_version") val reportVersion: Int = 1,
    val task: String = "build-serbian-audiobook-mvp 10.1",
    val status: String,
    val completed: Boolean,
    val device: DeviceParityDeviceIdentity,
    val build: DeviceParityBuildIdentity,
    val input: AacBenchmarkInputReport,
    val bitrates: List<AacBenchmarkBitrateReport>,
    @SerializedName("quality_method") val qualityMethod: String,
    val limitations: List<String>,
    val failure: String?,
    @SerializedName("created_at_epoch_ms") val createdAtEpochMs: Long,
)

public object AacBenchmarkReportValidator {
    public fun validate(report: AacBenchmarkReport) {
        require(report.kind == "android-aac-m4a-benchmark") { "Unexpected AAC benchmark kind" }
        require(report.reportVersion == 1) { "Unsupported AAC benchmark report version" }
        require(report.task.endsWith("10.1")) { "AAC report is not for task 10.1" }
        require(report.status in setOf("completed", "blocked", "failed")) { "Invalid AAC benchmark status" }
        require(report.input.sampleRateHz == AAC_BENCHMARK_SAMPLE_RATE_HZ)
        require(report.input.channels == AAC_BENCHMARK_CHANNELS)
        require(report.input.sampleCount > 0 && report.input.durationUs > 0)
        require(report.input.pcmSha256.matches(SHA256))
        require(report.input.wavSha256.matches(SHA256))
        val expectedInput = AacBenchmarkFixture.create()
        require(report.input.id == expectedInput.id)
        require(report.input.sampleCount == expectedInput.sampleCount)
        require(report.input.durationUs == expectedInput.durationUs)
        require(report.input.segments == expectedInput.segments.mapIndexed { index, segment ->
            AacBenchmarkSegmentReport(segment.id, segment.label, segment.pcm16.size, index * segment.pcm16.size)
        })
        require(report.input.consonantWindows == expectedInput.consonantWindows)
        require(report.input.pcmSha256 == AacBenchmarkFixture.sha256(AacBenchmarkFixture.pcm16Bytes(expectedInput)))
        require(report.input.wavSha256 == AacBenchmarkFixture.sha256(AacBenchmarkFixture.wavBytes(expectedInput)))
        require(report.bitrates.isNotEmpty()) { "No bitrate results" }
        report.bitrates.forEach { bitrate ->
            require(bitrate.requestedBitrateBps > 0)
            if (bitrate.available) {
                require(bitrate.status == "completed")
                require((bitrate.fullOutputSizeBytes ?: 0) > 0)
                require((bitrate.fullEncodedDurationUs ?: 0) > 0)
                require((bitrate.fullEncodeElapsedMs ?: -1) >= 0)
                require(bitrate.boundary?.segmentCount == report.input.segments.size)
                require(bitrate.quality?.method == "aac-wav-consonant-v1")
                require(bitrate.quality.windowMeasurements.size == report.input.consonantWindows.size)
                require(bitrate.quality.status in setOf("manual_listening_pending", "decode_failed"))
            } else {
                require(bitrate.status in setOf("unavailable", "failed"))
                require(bitrate.fullOutputSizeBytes == null)
            }
        }
        if (report.completed) require(report.status == "completed")
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}

public class AacBenchmarkReportStore(
    private val directory: File,
    public val reportFile: File = File(directory, "android-aac-benchmark-report.json"),
) {
    public fun writeAtomic(report: AacBenchmarkReport): File {
        AacBenchmarkReportValidator.validate(report)
        require(reportFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "AAC benchmark report directory is unavailable"
        }
        val temporary = File(reportFile.parentFile, ".${reportFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(GSON.toJson(report).toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), reportFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), reportFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return reportFile
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        val GSON = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    }
}

internal fun qualityMeasurement(
    input: ShortArray,
    decoded: ShortArray,
    windows: List<AacConsonantWindow>,
    alignmentOffset: Int,
): List<AacConsonantQualityMeasurement> = windows.map { window ->
    val reference = input.copyOfRange(window.startSample, window.startSample + window.sampleCount)
    val decodedStart = window.startSample + alignmentOffset
    if (decodedStart < 0 || decodedStart + window.sampleCount > decoded.size) {
        return@map AacConsonantQualityMeasurement(window.id, window.label, null, null, null, null, null)
    }
    val candidate = decoded.copyOfRange(decodedStart, decodedStart + window.sampleCount)
    val referenceRms = rms(reference)
    val decodedRms = rms(candidate)
    AacConsonantQualityMeasurement(
        id = window.id,
        label = window.label,
        referenceRms = referenceRms,
        decodedRms = decodedRms,
        rmsRatio = if (referenceRms == 0.0) null else decodedRms / referenceRms,
        referenceZeroCrossingRate = zeroCrossingRate(reference),
        decodedZeroCrossingRate = zeroCrossingRate(candidate),
    )
}

internal fun findPcmAlignment(reference: ShortArray, decoded: ShortArray): Int? {
    if (reference.isEmpty() || decoded.isEmpty()) return null
    val search = 4_096
    val comparison = minOf(reference.size, 12_000)
    var bestOffset: Int? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (offset in -search..search) {
        var referenceEnergy = 0.0
        var decodedEnergy = 0.0
        var cross = 0.0
        var count = 0
        var index = 0
        while (index < comparison) {
            val decodedIndex = index + offset
            if (decodedIndex in decoded.indices) {
                val referenceSample = reference[index].toDouble()
                val decodedSample = decoded[decodedIndex].toDouble()
                referenceEnergy += referenceSample * referenceSample
                decodedEnergy += decodedSample * decodedSample
                cross += referenceSample * decodedSample
                count++
            }
            index += 16
        }
        if (count > 0 && referenceEnergy > 0.0 && decodedEnergy > 0.0) {
            val score = cross / sqrt(referenceEnergy * decodedEnergy)
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
        }
    }
    return bestOffset
}

private fun rms(samples: ShortArray): Double = sqrt(samples.map { value -> value.toDouble() * value }.average())

private fun zeroCrossingRate(samples: ShortArray): Double {
    if (samples.size < 2) return 0.0
    var crossings = 0
    for (index in 1 until samples.size) {
        if ((samples[index - 1] < 0) != (samples[index] < 0)) crossings++
    }
    return crossings.toDouble() / (samples.size - 1)
}
