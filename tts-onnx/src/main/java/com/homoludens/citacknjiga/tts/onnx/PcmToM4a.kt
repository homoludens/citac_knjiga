package com.homoludens.citacknjiga.tts.onnx

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.generation.ClaimedGenerationSegment
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationFailureCategory
import com.homoludens.citacknjiga.core.generation.GenerationFailureException
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationStateGateway
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.core.storage.PublishedArtifact
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

public const val MVP_AAC_MIME: String = "audio/mp4a-latm"
public const val MVP_AAC_BITRATE_BPS: Int = 64_000
public const val MVP_AUDIO_SAMPLE_RATE_HZ: Int = 24_000
public const val MVP_AUDIO_CHANNELS: Int = 1

public data class PcmWavInfo(
    public val dataOffset: Long,
    public val dataSizeBytes: Long,
    public val sampleCount: Long,
    public val durationMs: Long,
)

/** Strict input gate for the generated, private PCM16 WAV contract. */
public object PcmWavValidator {
    public fun validate(file: File): PcmWavInfo {
        require(file.isFile && file.length() > 44L) { "PCM WAV is missing or empty" }
        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "PCM input is not RIFF" }
            val riffSize = readUnsignedInt(input)
            require(readAscii(input, 4) == "WAVE") { "PCM input is not WAVE" }
            require(riffSize + 8L == input.length()) { "PCM WAV RIFF size is invalid" }

            var formatFound = false
            var dataOffset = -1L
            var dataSize = -1L
            while (input.filePointer + 8L <= input.length()) {
                val chunkId = readAscii(input, 4)
                val chunkSize = readUnsignedInt(input)
                val chunkData = input.filePointer
                require(chunkSize <= input.length() - chunkData) { "PCM WAV chunk exceeds file" }
                when (chunkId) {
                    "fmt " -> {
                        require(!formatFound && chunkSize >= 16L) { "PCM WAV format chunk is invalid" }
                        require(readUnsignedShort(input) == 1) { "PCM WAV is not integer PCM" }
                        require(readUnsignedShort(input) == MVP_AUDIO_CHANNELS) { "PCM WAV is not mono" }
                        require(readUnsignedInt(input) == MVP_AUDIO_SAMPLE_RATE_HZ.toLong()) { "PCM WAV sample rate is invalid" }
                        require(readUnsignedInt(input) == MVP_AUDIO_SAMPLE_RATE_HZ.toLong() * 2L) { "PCM WAV byte rate is invalid" }
                        require(readUnsignedShort(input) == 2) { "PCM WAV block alignment is invalid" }
                        require(readUnsignedShort(input) == 16) { "PCM WAV is not PCM16" }
                        formatFound = true
                    }
                    "data" -> {
                        require(dataOffset < 0L) { "PCM WAV has duplicate data chunks" }
                        dataOffset = chunkData
                        dataSize = chunkSize
                    }
                }
                input.seek(chunkData + chunkSize + (chunkSize and 1L))
            }
            require(formatFound && dataOffset >= 0L && dataSize > 0L && dataSize % 2L == 0L) {
                "PCM WAV has no valid audio data"
            }
            val samples = dataSize / 2L
            val durationMs = samples * 1_000L / MVP_AUDIO_SAMPLE_RATE_HZ
            require(durationMs > 0L) { "PCM WAV duration is invalid" }
            return PcmWavInfo(dataOffset, dataSize, samples, durationMs)
        }
    }

    private fun readAscii(input: RandomAccessFile, count: Int): String = ByteArray(count).also(input::readFully)
        .toString(Charsets.US_ASCII)

    private fun readUnsignedShort(input: RandomAccessFile): Int =
        (input.readUnsignedByte() or (input.readUnsignedByte() shl 8))

    private fun readUnsignedInt(input: RandomAccessFile): Long =
        readUnsignedShort(input).toLong() or (readUnsignedShort(input).toLong() shl 16)
}

public data class M4aInfo(
    public val mimeType: String,
    public val sampleRateHz: Int,
    public val channels: Int,
    public val durationMs: Long,
)

public fun interface M4aValidator {
    public fun validate(file: File): M4aInfo
}

/** JVM-safe MP4/M4A box gate used before Android MediaExtractor validation. */
public object StructuralM4aValidator : M4aValidator {
    override fun validate(file: File): M4aInfo {
        require(file.isFile && file.length() > 0L) { "M4A is missing or empty" }
        RandomAccessFile(file, "r").use { input ->
            var offset = 0L
            var ftyp = false
            var moov = false
            var mdat = false
            while (offset + 8L <= input.length()) {
                input.seek(offset)
                val size = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val boxSize = when (size) {
                    0L -> input.length() - offset
                    1L -> {
                        require(input.readLong() >= 16L) { "M4A extended box size is invalid" }
                        input.seek(offset + 8L)
                        input.readLong()
                    }
                    else -> size
                }
                require(boxSize >= 8L && boxSize <= input.length() - offset) { "M4A box exceeds file" }
                when (type) {
                    "ftyp" -> {
                        val payload = ByteArray((boxSize - 8L).toInt().coerceAtMost(512))
                        input.readFully(payload)
                        val brands = payload.toString(Charsets.US_ASCII)
                        require(brands.contains("M4A ") || brands.contains("isom") || brands.contains("mp4")) {
                            "M4A file type brand is unsupported"
                        }
                        ftyp = true
                    }
                    "moov" -> moov = true
                    "mdat" -> mdat = true
                }
                offset += boxSize
            }
            require(offset == input.length() && ftyp && moov && mdat) { "M4A container is incomplete" }
        }
        return M4aInfo(MVP_AAC_MIME, MVP_AUDIO_SAMPLE_RATE_HZ, MVP_AUDIO_CHANNELS, 1L)
    }
}

/** Adds platform decoder/readability and exact track metadata to the structural gate. */
public class AndroidM4aValidator(
    private val structural: M4aValidator = StructuralM4aValidator,
) : M4aValidator {
    override fun validate(file: File): M4aInfo {
        structural.validate(file)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == MVP_AAC_MIME
            } ?: throw IllegalStateException(
                "M4A has no AAC-LC audio track: " +
                    (0 until extractor.trackCount).joinToString { index -> extractor.getTrackFormat(index).toString() },
            )
            val format = extractor.getTrackFormat(track)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            if (format.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
                require(format.getInteger(MediaFormat.KEY_AAC_PROFILE) == MediaCodecInfo.CodecProfileLevel.AACObjectLC) {
                    "M4A track is not AAC-LC"
                }
            }
            require(sampleRate == MVP_AUDIO_SAMPLE_RATE_HZ && channels == MVP_AUDIO_CHANNELS) {
                "M4A track is not 24 kHz mono"
            }
            require(durationUs > 0L) { "M4A duration is invalid" }
            M4aInfo(MVP_AAC_MIME, sampleRate, channels, durationUs / 1_000L)
        } finally {
            extractor.release()
        }
    }
}

public data class EncodedM4a(public val durationMs: Long)

public fun interface PcmToM4aEncoder {
    public fun encode(inputWav: File, outputM4a: File): EncodedM4a
}

public class AacEncodingException(
    public val failureCode: String,
    message: String,
    cause: Throwable? = null,
) : GenerationFailureException(GenerationFailureCategory.WRITE, failureCode, message, cause)

/** Synchronous, bounded MediaCodec adapter. Call from a worker/IO dispatcher. */
public class AndroidMediaCodecAacEncoder(
    private val bitrateBps: Int = MVP_AAC_BITRATE_BPS,
    private val timeoutUs: Long = 10_000L,
    private val deadlineMs: Long = 60_000L,
) : PcmToM4aEncoder {
    init {
        require(bitrateBps > 0) { "AAC bitrate must be positive" }
        require(timeoutUs > 0L && deadlineMs > 0L) { "AAC timeouts must be positive" }
    }

    override fun encode(inputWav: File, outputM4a: File): EncodedM4a {
        val wav = try {
            PcmWavValidator.validate(inputWav)
        } catch (failure: Throwable) {
            throw AacEncodingException("AAC_INPUT_INVALID", failure.message ?: "PCM WAV input is invalid", failure)
        }
        val codecName = findEncoder()?.name
            ?: throw AacEncodingException("AAC_UNAVAILABLE", "No regular AAC-LC encoder supports 24 kHz mono at 64 kbps")
        require(outputM4a.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "AAC temporary output directory is unavailable"
        }
        outputM4a.delete()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var inputDone = false
        var outputDone = false
        var inputBytes = 0L
        try {
            codec = MediaCodec.createByCodecName(codecName)
            muxer = MediaMuxer(outputM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createAudioFormat(MVP_AAC_MIME, MVP_AUDIO_SAMPLE_RATE_HZ, MVP_AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(inputWav, "r").use { source ->
                source.seek(wav.dataOffset)
                val deadline = SystemClock.elapsedRealtime() + deadlineMs
                while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                            buffer.clear()
                            val remaining = wav.dataSizeBytes - inputBytes
                            if (remaining <= 0L) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    wav.sampleCount * 1_000_000L / MVP_AUDIO_SAMPLE_RATE_HZ,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val count = minOf(remaining, buffer.remaining().toLong()).toInt()
                                require(count > 0) { "AAC encoder returned an empty input buffer" }
                                val inputBytesBuffer = ByteArray(count)
                                source.readFully(inputBytesBuffer)
                                buffer.put(inputBytesBuffer)
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    count,
                                    inputBytes / 2L * 1_000_000L / MVP_AUDIO_SAMPLE_RATE_HZ,
                                    0,
                                )
                                inputBytes += count
                            }
                        }
                    }
                    val info = MediaCodec.BufferInfo()
                    when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC encoder changed output format twice" }
                            val outputFormat = codec.outputFormat
                            check(outputFormat.getString(MediaFormat.KEY_MIME) == MVP_AAC_MIME) {
                                "AAC encoder returned an unexpected MIME type"
                            }
                            trackIndex = muxer.addTrack(outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val output = checkNotNull(codec.getOutputBuffer(outputIndex))
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted) { "AAC encoder emitted data before its format" }
                                require(info.offset >= 0 && info.size <= output.capacity() - info.offset) {
                                    "AAC encoder returned an invalid output buffer"
                                }
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
            }
            check(muxerStarted) { "AAC encoder did not produce an output track" }
            muxer.stop()
            muxerStarted = false
            return EncodedM4a(wav.durationMs)
        } catch (failure: AacEncodingException) {
            throw failure
        } catch (failure: Throwable) {
            throw AacEncodingException("AAC_ENCODING_FAILURE", failure.message ?: "AAC encoding failed", failure)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
        }
    }

    private fun findEncoder(): MediaCodecInfo? = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .codecInfos
        .firstOrNull { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(MVP_AAC_MIME, ignoreCase = true) }) return@firstOrNull false
            val capabilities = info.getCapabilitiesForType(MVP_AAC_MIME)
            val audio = capabilities.audioCapabilities ?: return@firstOrNull false
            audio.isSampleRateSupported(MVP_AUDIO_SAMPLE_RATE_HZ) &&
                audio.maxInputChannelCount >= MVP_AUDIO_CHANNELS &&
                audio.bitrateRange.contains(bitrateBps) &&
                capabilities.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AACObjectLC }
        }

}

public enum class PublishedAudioFormat { AAC_M4A, PCM_WAV }

public data class PublishedSegmentAudio(
    public val artifact: PublishedArtifact,
    public val format: PublishedAudioFormat,
    public val durationMs: Long,
)

/** Encodes and checkpoints one segment without replacing an existing ready path. */
public class AudioArtifactPublisher(
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val state: GenerationStateGateway,
    private val encoder: PcmToM4aEncoder = AndroidMediaCodecAacEncoder(),
    private val m4aValidator: M4aValidator = AndroidM4aValidator(),
) {
    public fun publish(
        run: GenerationRunEntity,
        claimed: ClaimedGenerationSegment,
        stagingWav: File,
        provenance: GenerationProvenance,
        portable: Boolean = false,
    ): PublishedSegmentAudio {
        val wav = PcmWavValidator.validate(stagingWav)
        val temporaryRoot = storage.temporaryDirectory.canonicalFile.toPath()
        require(stagingWav.canonicalFile.toPath().startsWith(temporaryRoot)) {
            "Raw PCM must be staged in private temporary storage"
        }
        require(claimed.segment.chapterId.isNotBlank()) { "Audio segment chapter is blank" }
        require(run.modelPackageId == provenance.modelPackageId) { "Audio provenance model package does not match the run" }
        require(run.preprocessingVersion == provenance.preprocessingVersion) { "Audio provenance preprocessing does not match the run" }
        require(run.pronunciationVersion == provenance.pronunciationVersion) { "Audio provenance pronunciation does not match the run" }
        require(run.inferenceSettingsHash == provenance.inferenceSettingsHash) { "Audio provenance settings do not match the run" }
        require(run.audioProcessingVersion == provenance.audioProcessingVersion) { "Audio provenance processing does not match the run" }
        val existingReady = existingReadyFile(claimed.segment.audioPath)
        val candidate = try {
            publishAac(run, claimed, stagingWav)
        } catch (failure: Throwable) {
            val aacFailure = failure as? AacEncodingException ?: AacEncodingException(
                "AAC_ENCODING_FAILURE",
                failure.message ?: "AAC encoding failed",
                failure,
            )
            if (portable || existingReady != null) throw aacFailure
            publishWav(run, claimed, stagingWav, wav)
        }
        try {
            state.completeAudioSegment(
                claimed.segment.id,
                candidate.artifact,
                GeneratedSegmentAudio(
                    provenance = provenance,
                    sampleRateHz = MVP_AUDIO_SAMPLE_RATE_HZ,
                    channels = MVP_AUDIO_CHANNELS,
                    durationMs = candidate.durationMs,
                    writer = {},
                    validator = {},
                ),
            )
        } catch (failure: Throwable) {
            // The old Room path remains valid; a candidate can be cleaned or reconciled as an orphan.
            candidate.artifact.file.delete()
            throw failure
        }
        // A deletion failure is safe to recover later; reporting READY as failed would not be.
        stagingWav.delete()
        return candidate
    }

    private fun publishAac(
        run: GenerationRunEntity,
        claimed: ClaimedGenerationSegment,
        stagingWav: File,
    ): PublishedSegmentAudio {
        val temporary = storage.temporaryFile(
            "aac-${claimed.segment.id}",
            ".${claimed.segment.id}-${UUID.randomUUID()}.m4a",
        ).apply {
            check(parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "AAC temporary directory is unavailable" }
        }
        return try {
            encoder.encode(stagingWav, temporary)
            val info = m4aValidator.validate(temporary)
            require(info.mimeType == MVP_AAC_MIME && info.sampleRateHz == MVP_AUDIO_SAMPLE_RATE_HZ && info.channels == MVP_AUDIO_CHANNELS) {
                "Encoded AAC metadata is not 24 kHz mono"
            }
            require(info.durationMs > 0L && temporary.length() > 0L) { "Encoded AAC output has invalid size or duration" }
            val destination = m4aDestination(run, claimed)
            val artifact = artifactStore.publish(
                ownerId = "aac-${run.id}-${claimed.segment.id}",
                destination = destination,
                writer = { output -> temporary.inputStream().use { it.copyTo(output) } },
                validator = { file -> m4aValidator.validate(file) },
            )
            PublishedSegmentAudio(artifact, PublishedAudioFormat.AAC_M4A, info.durationMs)
        } finally {
            temporary.delete()
        }
    }

    private fun publishWav(
        run: GenerationRunEntity,
        claimed: ClaimedGenerationSegment,
        stagingWav: File,
        wav: PcmWavInfo,
    ): PublishedSegmentAudio {
        val destination = wavDestination(run, claimed)
        val artifact = artifactStore.publish(
            ownerId = "wav-${run.id}-${claimed.segment.id}",
            destination = destination,
            writer = { output -> stagingWav.inputStream().use { it.copyTo(output) } },
            validator = { file -> PcmWavValidator.validate(file) },
        )
        return PublishedSegmentAudio(artifact, PublishedAudioFormat.PCM_WAV, wav.durationMs)
    }

    private fun m4aDestination(run: GenerationRunEntity, claimed: ClaimedGenerationSegment): File {
        val base = storage.readySegmentAudio(run.bookProjectId, claimed.segment.chapterId, claimed.segment.id)
        return if (!base.exists() && claimed.segment.audioPath.isNullOrBlank()) base else uniqueDestination(run, claimed, "m4a")
    }

    private fun wavDestination(run: GenerationRunEntity, claimed: ClaimedGenerationSegment): File {
        val base = storage.readySegmentWav(run.bookProjectId, claimed.segment.chapterId, claimed.segment.id)
        return if (!base.exists() && claimed.segment.audioPath.isNullOrBlank()) base else uniqueDestination(run, claimed, "wav")
    }

    private fun uniqueDestination(run: GenerationRunEntity, claimed: ClaimedGenerationSegment, extension: String): File =
        storage.readySegmentAudio(
            run.bookProjectId,
            claimed.segment.chapterId,
            claimed.segment.id,
            "${claimed.segment.id}-${UUID.randomUUID()}.$extension",
        )

    private fun existingReadyFile(path: String?): File? = path?.let { candidate ->
        runCatching {
            val file = File(candidate).canonicalFile
            val root = storage.readyAudioDirectory.canonicalFile.toPath()
            file.takeIf { it.isFile && it.toPath().startsWith(root) }
        }.getOrNull()
    }
}
