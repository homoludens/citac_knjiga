package com.homoludens.citacknjiga.tts.onnx

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class DesktopOnnxParityVectorLoaderTest {
    @Test
    public fun loadsFloatWavAndChunkedTokenInputWithoutText() {
        val directory = createTempDirectory().toFile()
        val audioDirectory = File(directory, "audio").also { it.mkdirs() }
        val pcm = FloatArray(4096) { index -> (0.2 * sin(index / 17.0)).toFloat() }
        val audio = File(audioDirectory, "fixture.wav").also { it.writeFloatWav(pcm) }
        val hash = sha256(audio)
        File(directory, "manifest.json").writeText(
            """
            {"kind":"desktop-onnx-parity-audio","version":1,
             "provenance":{"thresholds_version":"fp32-parity-v2"},
             "vectors":[{"id":"fixture","audio_file":"audio/fixture.wav",
             "sample_format":"float32-le","sample_rate_hz":24000,"channels":1,
             "sample_count":4096,"byte_size":${audio.length()},"sha256":"$hash"}]}
            """.trimIndent(),
        )
        File(directory, "inputs.json").writeText(
            """
            {"kind":"desktop-onnx-parity-inputs","version":1,"audio_manifest":"manifest.json",
             "provenance":{"thresholds_version":"fp32-parity-v2"},
             "vectors":[{"id":"fixture","speed":1.0,
             "token_id_chunks":[[0,69,0],[0,70,71,0]]}]}
            """.trimIndent(),
        )

        val vectors = DesktopOnnxParityVectorLoader.load(directory)

        assertEquals(1, vectors.size)
        assertEquals("fixture", vectors.single().id)
        assertEquals(2, vectors.single().tokenIdChunks.size)
        assertEquals(1f, vectors.single().speed)
        assertTrue(vectors.single().pcm.contentEquals(pcm))
    }

    private fun File.writeFloatWav(pcm: FloatArray) {
        val bytes = ByteBuffer.allocate(44 + pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(36 + pcm.size * 4)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16).putShort(3).putShort(1).putInt(24_000)
        bytes.putInt(24_000 * 4).putShort(4).putShort(32)
        bytes.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size * 4)
        pcm.forEach { bytes.putFloat(it) }
        writeBytes(bytes.array())
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}
