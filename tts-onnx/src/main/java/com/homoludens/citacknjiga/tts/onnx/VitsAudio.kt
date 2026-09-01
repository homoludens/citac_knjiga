package com.homoludens.citacknjiga.tts.onnx

import kotlin.math.round
import kotlin.math.roundToInt
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

public data class VitsNativeAudio(
    val pcm: FloatArray,
    val sampleRateHz: Int = 22_050,
    val channels: Int = 1,
)

public class VitsAudioValidationException(message: String) : IllegalArgumentException(message)

/** Validates the native contract and performs the sole native-to-app conversion. */
public object VitsAudioOutputValidator {
    public const val NATIVE_RATE_HZ: Int = 22_050
    public const val FINAL_RATE_HZ: Int = 24_000
    public const val CHANNELS: Int = 1
    public const val RESAMPLER_VERSION: String = "serbian-vits-resampler-v1"

    public fun validateNative(audio: VitsNativeAudio) {
        requireAudio(audio, NATIVE_RATE_HZ)
    }

    public fun resampleOnce(audio: VitsNativeAudio): VitsNativeAudio {
        validateNative(audio)
        val length = round(audio.pcm.size.toDouble() * FINAL_RATE_HZ / NATIVE_RATE_HZ).toInt()
        require(length > 0) { "VITS audio output is empty" }
        val output = FloatArray(length)
        val scale = if (length == 1) 0.0 else (audio.pcm.size - 1).toDouble() / (length - 1)
        for (index in output.indices) {
            val position = index * scale
            val left = position.toInt().coerceAtMost(audio.pcm.lastIndex)
            val right = (left + 1).coerceAtMost(audio.pcm.lastIndex)
            val fraction = position - left
            output[index] = (audio.pcm[left] * (1.0 - fraction) + audio.pcm[right] * fraction).toFloat()
        }
        val result = VitsNativeAudio(output, FINAL_RATE_HZ, CHANNELS)
        validateFinal(result)
        return result
    }

    public fun validateFinal(audio: VitsNativeAudio) {
        requireAudio(audio, FINAL_RATE_HZ)
    }

    private fun requireAudio(audio: VitsNativeAudio, rate: Int) {
        if (audio.sampleRateHz != rate) throw VitsAudioValidationException("Expected $rate Hz VITS audio")
        if (audio.channels != CHANNELS) throw VitsAudioValidationException("VITS audio must be mono")
        if (audio.pcm.isEmpty()) throw VitsAudioValidationException("VITS audio is empty")
        if (audio.pcm.any { !it.isFinite() || kotlin.math.abs(it) >= 1f }) {
            throw VitsAudioValidationException("VITS audio contains invalid PCM")
        }
    }
}

/** Writes the validated VITS output using the app's canonical PCM16 WAV format. */
public object VitsWavWriter {
    public fun writeAtomic(destination: File, audio: VitsNativeAudio): WavArtifact {
        VitsAudioOutputValidator.validateFinal(audio)
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "VITS WAV artifact directory is unavailable"
        }
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val dataSize = audio.pcm.size * 2
                stream.write("RIFF".toByteArray(Charsets.US_ASCII))
                writeInt(stream, 36 + dataSize)
                stream.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                writeInt(stream, 16)
                writeShort(stream, 1)
                writeShort(stream, audio.channels)
                writeInt(stream, audio.sampleRateHz)
                writeInt(stream, audio.sampleRateHz * audio.channels * 2)
                writeShort(stream, audio.channels * 2)
                writeShort(stream, 16)
                stream.write("data".toByteArray(Charsets.US_ASCII))
                writeInt(stream, dataSize)
                audio.pcm.forEach { sample ->
                    writeShort(stream, (sample * if (sample < 0f) 32_768f else Short.MAX_VALUE.toFloat()).roundToInt())
                }
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return WavArtifact(destination, audio.pcm.size, audio.sampleRateHz)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun writeShort(stream: FileOutputStream, value: Int) {
        stream.write(value and 0xff)
        stream.write((value ushr 8) and 0xff)
    }

    private fun writeInt(stream: FileOutputStream, value: Int) {
        writeShort(stream, value)
        writeShort(stream, value ushr 16)
    }
}
