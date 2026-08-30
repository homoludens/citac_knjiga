package com.homoludens.citacknjiga.tts.onnx

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max

public class AndroidAacBenchmarkRunner(
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    public fun runAndPersist(
        context: android.content.Context,
        reportStore: AacBenchmarkReportStore = AacBenchmarkReportStore(
            AppPrivateStorage(context.filesDir).benchmarkReportsDirectory,
        ),
        bitratesBps: List<Int> = DEFAULT_BITRATES_BPS,
        task: String = "build-serbian-audiobook-mvp 10.1",
    ): AacBenchmarkReport {
        require(bitratesBps.isNotEmpty() && bitratesBps.all { it > 0 }) { "AAC bitrates must be positive" }
        val input = AacBenchmarkFixture.create()
        val pcm = input.pcm16
        val pcmBytes = AacBenchmarkFixture.pcm16Bytes(input)
        val wavBytes = AacBenchmarkFixture.wavBytes(input)
        val inputReport = AacBenchmarkInputReport(
            id = input.id,
            sampleRateHz = input.sampleRateHz,
            channels = input.channels,
            sampleCount = input.sampleCount,
            durationUs = input.durationUs,
            pcmSha256 = AacBenchmarkFixture.sha256(pcmBytes),
            wavSha256 = AacBenchmarkFixture.sha256(wavBytes),
            segments = input.segments.mapIndexed { index, segment ->
                AacBenchmarkSegmentReport(segment.id, segment.label, segment.pcm16.size, index * segment.pcm16.size)
            },
            consonantWindows = input.consonantWindows,
        )
        val scratch = File(context.cacheDir, "aac-benchmark-${UUID.randomUUID()}")
        check(scratch.mkdirs()) { "AAC benchmark scratch directory is unavailable" }
        val results = try {
            File(scratch, "reference.wav").writeBytes(wavBytes)
            bitratesBps.map { bitrate -> benchmarkBitrate(input, pcm, bitrate, scratch) }
        } finally {
            scratch.deleteRecursively()
        }
        val failed = results.any { it.status == "failed" }
        val completed = results.any { it.available } && !failed
        val report = AacBenchmarkReport(
            task = task,
            status = when {
                failed -> "failed"
                completed -> "completed"
                else -> "blocked"
            },
            completed = completed,
            device = deviceIdentity(context),
            build = buildIdentity(context),
            input = inputReport,
            bitrates = results,
            qualityMethod = QUALITY_METHOD,
            limitations = LIMITATIONS,
            failure = results.firstOrNull { it.status == "failed" }?.reason,
            createdAtEpochMs = nowEpochMs(),
        )
        reportStore.writeAtomic(report)
        return report
    }

    private fun benchmarkBitrate(
        input: AacBenchmarkInput,
        pcm: ShortArray,
        bitrateBps: Int,
        scratch: File,
    ): AacBenchmarkBitrateReport {
        val codec = findEncoder(bitrateBps)
            ?: return unavailable(bitrateBps, "No regular AAC-LC encoder supports 24 kHz mono at this bitrate")
        return try {
            val full = encode(
                samples = pcm,
                bitrateBps = bitrateBps,
                codecName = codec.name,
                destination = File(scratch, "full-$bitrateBps.m4a"),
            )
            val segmentDurations = input.segments.mapIndexed { index, segment ->
                encode(
                    samples = segment.pcm16,
                    bitrateBps = bitrateBps,
                    codecName = codec.name,
                    destination = File(scratch, "${bitrateBps}-segment-${index + 1}.m4a"),
                ).encodedDurationUs
            }
            val boundary = AacBenchmarkBoundaryReport(
                segmentCount = input.segments.size,
                totalGapUs = segmentDurations.zip(input.segments).sumOf { (encoded, segment) ->
                    max(0L, encoded - segment.pcm16.size.toLong() * 1_000_000L / input.sampleRateHz)
                },
                totalTrimUs = segmentDurations.zip(input.segments).sumOf { (encoded, segment) ->
                    max(0L, segment.pcm16.size.toLong() * 1_000_000L / input.sampleRateHz - encoded)
                },
                maxAbsoluteDriftUs = segmentDurations.zip(input.segments).maxOf { (encoded, segment) ->
                    kotlin.math.abs(encoded - segment.pcm16.size.toLong() * 1_000_000L / input.sampleRateHz)
                },
            )
            val decoded = decode(full.file)
            val alignment = decoded?.let { findPcmAlignment(pcm, it.samples) }
            AacBenchmarkBitrateReport(
                requestedBitrateBps = bitrateBps,
                available = true,
                codecName = codec.name,
                status = "completed",
                reason = null,
                fullOutputSizeBytes = full.file.length(),
                fullEncodedDurationUs = full.encodedDurationUs,
                fullEncodeElapsedMs = full.elapsedMs,
                boundary = boundary,
                quality = AacConsonantQualityReport(
                    status = if (decoded == null) "decode_failed" else "manual_listening_pending",
                    alignmentOffsetSamples = alignment,
                    decodedSampleCount = decoded?.samples?.size,
                    windowMeasurements = decoded?.let {
                        qualityMeasurement(
                            pcm,
                            it.samples,
                            input.consonantWindows,
                            alignment ?: 0,
                        )
                    } ?: input.consonantWindows.map { window ->
                        AacConsonantQualityMeasurement(window.id, window.label, null, null, null, null, null)
                    },
                ),
            )
        } catch (throwable: Throwable) {
            AacBenchmarkBitrateReport(
                requestedBitrateBps = bitrateBps,
                available = false,
                codecName = codec.name,
                status = "failed",
                reason = "${throwable::class.simpleName}: ${throwable.message ?: "encoding failed"}",
                fullOutputSizeBytes = null,
                fullEncodedDurationUs = null,
                fullEncodeElapsedMs = null,
                boundary = null,
                quality = null,
            )
        }
    }

    private fun findEncoder(bitrateBps: Int): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .firstOrNull { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(AAC_MIME, ignoreCase = true) }) return@firstOrNull false
            val capabilities = info.getCapabilitiesForType(AAC_MIME)
            val audio = capabilities.audioCapabilities ?: return@firstOrNull false
            audio.isSampleRateSupported(AAC_BENCHMARK_SAMPLE_RATE_HZ) &&
                audio.maxInputChannelCount >= AAC_BENCHMARK_CHANNELS &&
                audio.bitrateRange.contains(bitrateBps)
        }

    private fun encode(samples: ShortArray, bitrateBps: Int, codecName: String, destination: File): EncodedFile {
        val startedAt = SystemClock.elapsedRealtime()
        val codec = MediaCodec.createByCodecName(codecName)
        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        var inputOffset = 0
        var outputDone = false
        var inputDone = false
        val timeoutUs = 10_000L
        try {
            val format = MediaFormat.createAudioFormat(AAC_MIME, AAC_BENCHMARK_SAMPLE_RATE_HZ, AAC_BENCHMARK_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        buffer.clear()
                        val remaining = samples.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, samples.size.toLong() * 1_000_000L / AAC_BENCHMARK_SAMPLE_RATE_HZ, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val count = minOf(remaining, buffer.remaining() / 2)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            repeat(count) { index -> buffer.putShort(samples[inputOffset + index]) }
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                count * 2,
                                inputOffset.toLong() * 1_000_000L / AAC_BENCHMARK_SAMPLE_RATE_HZ,
                                0,
                            )
                            inputOffset += count
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "AAC encoder changed output format twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = checkNotNull(codec.getOutputBuffer(outputIndex))
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted) { "AAC encoder emitted data before output format" }
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, output, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(outputDone) { "AAC encoder timed out" }
            check(muxerStarted) { "AAC encoder did not provide an output format" }
            muxer.stop()
            muxerStarted = false
            return EncodedFile(destination, readDurationUs(destination), SystemClock.elapsedRealtime() - startedAt)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun decode(file: File): DecodedPcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec.configure(format, null, null, 0)
            codec.start()
            val output = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            var sampleRate = AAC_BENCHMARK_SAMPLE_RATE_HZ
            var channels = AAC_BENCHMARK_CHANNELS
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decodedFormat = codec.outputFormat
                        sampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            output.write(bytes)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (!outputDone || sampleRate != AAC_BENCHMARK_SAMPLE_RATE_HZ || channels != AAC_BENCHMARK_CHANNELS) return null
            val bytes = output.toByteArray()
            val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            DecodedPcm(ShortArray(bytes.size / 2) { pcm.getShort() })
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun readDurationUs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).first { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.getTrackFormat(index).getLong(MediaFormat.KEY_DURATION)
        } finally {
            extractor.release()
        }
    }

    private fun unavailable(bitrate: Int, reason: String) = AacBenchmarkBitrateReport(
        requestedBitrateBps = bitrate,
        available = false,
        codecName = null,
        status = "unavailable",
        reason = reason,
        fullOutputSizeBytes = null,
        fullEncodedDurationUs = null,
        fullEncodeElapsedMs = null,
        boundary = null,
        quality = null,
    )

    private fun deviceIdentity(context: android.content.Context) = DeviceParityDeviceIdentity(
        Build.MANUFACTURER,
        Build.MODEL,
        Build.DEVICE,
        Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    )

    private fun buildIdentity(context: android.content.Context): DeviceParityBuildIdentity {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val buildType = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"
        return DeviceParityBuildIdentity(context.packageName, packageInfo.versionName ?: "unknown", packageInfo.longVersionCode, buildType)
    }

    private data class EncodedFile(val file: File, val encodedDurationUs: Long, val elapsedMs: Long)
    private data class DecodedPcm(val samples: ShortArray)

    private companion object {
        const val AAC_MIME = "audio/mp4a-latm"
        val DEFAULT_BITRATES_BPS = listOf(64_000, 80_000, 96_000)
        const val QUALITY_METHOD = "aac-wav-consonant-v1: decode each M4A, align PCM by maximum normalized correlation within +/-4096 samples, compare labelled windows by RMS and zero-crossing rate, then perform a randomized 1-5 A/B listening score per window"
        val LIMITATIONS = listOf(
            "The fixture is deterministic synthetic consonant-band audio, not natural Serbian speech and cannot establish phoneme intelligibility by itself.",
            "Manual consonant quality is not automatically scored; listen to the reported WAV and decoded M4A windows and record a 1-5 score per label using the documented method.",
            "Encoded duration includes codec priming and padding as reported by MediaExtractor; boundary gap and trim are per independently encoded segment.",
            "MediaCodec availability is device/OS/vendor specific; this benchmark does not install an encoder or use a non-platform fallback.",
            "Scratch M4A/WAV data is kept in app cache only during the run and is deleted before the JSON report is published.",
        )
    }
}
