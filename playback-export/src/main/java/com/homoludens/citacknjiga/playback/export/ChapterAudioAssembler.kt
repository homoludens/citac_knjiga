package com.homoludens.citacknjiga.playback.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.homoludens.citacknjiga.tts.onnx.AndroidMediaCodecAacEncoder
import com.homoludens.citacknjiga.tts.onnx.M4aValidator
import com.homoludens.citacknjiga.tts.onnx.MVP_AAC_MIME
import com.homoludens.citacknjiga.tts.onnx.MVP_AUDIO_CHANNELS
import com.homoludens.citacknjiga.tts.onnx.MVP_AUDIO_SAMPLE_RATE_HZ
import com.homoludens.citacknjiga.tts.onnx.PcmToM4aEncoder
import com.homoludens.citacknjiga.tts.onnx.PcmWavValidator
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

public enum class ExportAudioFormat { AUTO, M4A, WAV }

public data class AssembledChapterAudio(
    public val file: File,
    public val format: ExportAudioFormat,
    public val durationMs: Long,
)

public fun interface ChapterAudioAssembler {
    public fun assemble(sourceFiles: List<File>, outputFile: File, format: ExportAudioFormat): AssembledChapterAudio
}

public interface DurationAwareChapterAudioAssembler {
    public fun assemble(
        sourceFiles: List<File>,
        outputFile: File,
        format: ExportAudioFormat,
        expectedDurationsMs: List<Long>,
    ): AssembledChapterAudio
}

/** Streams PCM chapter concatenation and re-encodes compressed inputs instead of joining containers. */
public class AndroidChapterAudioAssembler(
    private val encoder: PcmToM4aEncoder? = null,
    private val m4aValidator: M4aValidator? = null,
) : ChapterAudioAssembler, DurationAwareChapterAudioAssembler {
    override fun assemble(
        sourceFiles: List<File>,
        outputFile: File,
        format: ExportAudioFormat,
    ): AssembledChapterAudio = assemble(sourceFiles, outputFile, format, emptyList())

    override fun assemble(
        sourceFiles: List<File>,
        outputFile: File,
        format: ExportAudioFormat,
        expectedDurationsMs: List<Long>,
    ): AssembledChapterAudio {
        require(sourceFiles.isNotEmpty()) { "A chapter must contain audio" }
        require(expectedDurationsMs.isEmpty() || expectedDurationsMs.size == sourceFiles.size) {
            "Expected chapter durations must match source files"
        }
        val selected = when (format) {
            ExportAudioFormat.AUTO -> if (sourceFiles.all { it.extension.equals("wav", ignoreCase = true) }) {
                ExportAudioFormat.WAV
            } else {
                ExportAudioFormat.M4A
            }
            else -> format
        }
        require(outputFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Chapter export temporary directory is unavailable"
        }
        val decodedWav = if (selected == ExportAudioFormat.M4A) {
            File(outputFile.parentFile, "decoded-${UUID.randomUUID()}.wav")
        } else {
            outputFile
        }
        try {
            val durationMs = PcmWavAppender(decodedWav).use { pcm ->
                sourceFiles.forEachIndexed { index, source ->
                    if (source.extension.equals("wav", ignoreCase = true)) {
                        val info = PcmWavValidator.validate(source)
                        copyPcm(source, info, pcm)
                    } else {
                        decodeM4a(source, pcm, expectedDurationsMs.getOrNull(index))
                    }
                }
                pcm.durationMs()
            }
            PcmWavValidator.validate(decodedWav)
            if (selected == ExportAudioFormat.WAV) {
                return AssembledChapterAudio(decodedWav, selected, durationMs)
            }

            outputFile.delete()
            val encoded = (encoder ?: AndroidMediaCodecAacEncoder()).encode(decodedWav, outputFile)
            val info = (m4aValidator ?: com.homoludens.citacknjiga.tts.onnx.AndroidM4aValidator()).validate(outputFile)
            require(info.mimeType == MVP_AAC_MIME && info.sampleRateHz == MVP_AUDIO_SAMPLE_RATE_HZ &&
                info.channels == MVP_AUDIO_CHANNELS && info.durationMs > 0L) {
                "Chapter AAC output is not valid 24 kHz mono audio"
            }
            return AssembledChapterAudio(outputFile, selected, encoded.durationMs)
        } catch (failure: Throwable) {
            outputFile.delete()
            throw failure
        } finally {
            if (decodedWav != outputFile) decodedWav.delete()
        }
    }

    private fun copyPcm(source: File, info: com.homoludens.citacknjiga.tts.onnx.PcmWavInfo, destination: PcmWavAppender) {
        RandomAccessFile(source, "r").use { input ->
            input.seek(info.dataOffset)
            val buffer = ByteArray(32 * 1024)
            var remaining = info.dataSizeBytes
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0) { "PCM WAV ended before its data" }
                destination.append(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun decodeM4a(source: File, destination: PcmWavAppender, expectedDurationMs: Long?) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("M4A has no audio track")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val activeDecoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            decoder = activeDecoder
            var inputDone = false
            var outputDone = false
            var formatChecked = false
            var remainingExpectedBytes = expectedDurationMs?.let { it * MVP_AUDIO_SAMPLE_RATE_HZ / 1_000L * 2L }
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000L
            while (!outputDone && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val inputIndex = activeDecoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(activeDecoder.getInputBuffer(inputIndex))
                        val sampleSize = extractor.readSampleData(input, 0)
                        if (sampleSize < 0) {
                            activeDecoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            activeDecoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                when (val outputIndex = activeDecoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = activeDecoder.outputFormat
                        require(outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == MVP_AUDIO_SAMPLE_RATE_HZ &&
                            outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == MVP_AUDIO_CHANNELS) {
                            "Decoded chapter audio is not 24 kHz mono"
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            require(outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == 2) {
                                "Decoded chapter audio is not PCM16"
                            }
                        }
                        formatChecked = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = requireNotNull(activeDecoder.getOutputBuffer(outputIndex))
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            require(formatChecked) { "Decoder emitted PCM before its output format" }
                            require(info.offset >= 0 && info.size <= output.capacity() - info.offset) {
                                "Decoder returned an invalid PCM buffer"
                            }
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val count = minOf(info.size.toLong(), remainingExpectedBytes ?: info.size.toLong()).toInt()
                            val bytes = ByteArray(count)
                            output.get(bytes)
                            destination.append(bytes, 0, bytes.size)
                            remainingExpectedBytes = remainingExpectedBytes?.minus(count)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        activeDecoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(outputDone) { "M4A decoder timed out" }
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }
}

/** JVM-friendly assembler used for deterministic PCM export and focused tests. */
public class WavChapterAudioAssembler : ChapterAudioAssembler {
    override fun assemble(sourceFiles: List<File>, outputFile: File, format: ExportAudioFormat): AssembledChapterAudio {
        require(format != ExportAudioFormat.M4A) { "WAV assembler cannot produce M4A" }
        require(sourceFiles.isNotEmpty()) { "A chapter must contain audio" }
        val durationMs = PcmWavAppender(outputFile).use { destination ->
            sourceFiles.forEach { source ->
                val info = PcmWavValidator.validate(source)
                RandomAccessFile(source, "r").use { input ->
                    input.seek(info.dataOffset)
                    val buffer = ByteArray(32 * 1024)
                    var remaining = info.dataSizeBytes
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "PCM WAV ended before its data" }
                        destination.append(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
            destination.durationMs()
        }
        PcmWavValidator.validate(outputFile)
        return AssembledChapterAudio(outputFile, ExportAudioFormat.WAV, durationMs)
    }
}

private class PcmWavAppender(private val file: File) : AutoCloseable {
    private lateinit var output: RandomAccessFile
    private var dataSize = 0L

    init {
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Chapter WAV directory is unavailable"
        }
        output = RandomAccessFile(file, "rw")
        output.setLength(0L)
        output.write(ByteArray(44))
    }

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        require(length % 2 == 0) { "PCM data must contain complete samples" }
        dataSize += length
        require(dataSize <= 0xffffffffL - 36L) { "Chapter WAV is too large" }
        output.write(bytes, offset, length)
    }

    fun durationMs(): Long = dataSize / 2L * 1_000L / MVP_AUDIO_SAMPLE_RATE_HZ

    override fun close() {
        try {
            require(dataSize > 0L) { "Chapter audio is empty" }
            output.seek(0L)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt((36L + dataSize).toInt())
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(MVP_AUDIO_CHANNELS.toShort())
                putInt(MVP_AUDIO_SAMPLE_RATE_HZ)
                putInt(MVP_AUDIO_SAMPLE_RATE_HZ * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(dataSize.toInt())
            }.array()
            output.write(header)
            output.fd.sync()
        } finally {
            output.close()
        }
    }
}
