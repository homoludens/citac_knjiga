package com.homoludens.citacknjiga.tts.onnx

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.roundToInt

public data class WavArtifact(
    val file: File,
    val sampleCount: Int,
    val sampleRateHz: Int,
)

/** Writes validated mono float PCM as an app-private, canonical PCM16 WAV. */
public object PcmWavWriter {
    public fun writeAtomic(
        destination: File,
        output: OnnxTtsOutput,
        expectedTokenCount: Int,
        speed: Float = 1f,
    ): WavArtifact {
        OnnxAudioOutputValidator.validate(output, expectedTokenCount, speed)
        require(output.pcm.size <= (Int.MAX_VALUE - 44) / 2) { "PCM output is too large for a WAV artifact" }
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "WAV artifact directory is unavailable"
        }

        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                write(stream, output, expectedTokenCount, speed)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return WavArtifact(destination, output.pcm.size, output.sampleRateHz)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    public fun write(
        stream: OutputStream,
        output: OnnxTtsOutput,
        expectedTokenCount: Int,
        speed: Float = 1f,
    ) {
        OnnxAudioOutputValidator.validate(output, expectedTokenCount, speed)
        val dataSize = output.pcm.size * 2
        stream.writeAscii("RIFF")
        stream.writeLittleEndianInt(36 + dataSize)
        stream.writeAscii("WAVE")
        stream.writeAscii("fmt ")
        stream.writeLittleEndianInt(16)
        stream.writeLittleEndianShort(1)
        stream.writeLittleEndianShort(output.channels)
        stream.writeLittleEndianInt(output.sampleRateHz)
        stream.writeLittleEndianInt(output.sampleRateHz * output.channels * 2)
        stream.writeLittleEndianShort(output.channels * 2)
        stream.writeLittleEndianShort(16)
        stream.writeAscii("data")
        stream.writeLittleEndianInt(dataSize)
        output.pcm.forEach { sample ->
            val scale = if (sample < 0f) 32_768f else Short.MAX_VALUE.toFloat()
            stream.writeLittleEndianShort((sample * scale).roundToInt())
        }
    }

    private fun OutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun OutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
