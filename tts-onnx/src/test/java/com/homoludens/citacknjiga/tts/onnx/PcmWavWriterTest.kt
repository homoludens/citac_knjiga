package com.homoludens.citacknjiga.tts.onnx

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class PcmWavWriterTest {
    @Test
    public fun writesCanonical24KhzMonoPcm16HeaderAndSamples() {
        val directory = createTempDirectory().toFile()
        val artifact = PcmWavWriter.writeAtomic(
            destination = File(directory, "proof.wav"),
            output = OnnxTtsOutput(
                FloatArray(OnnxRuntimeContract.SAMPLES_PER_DURATION_FRAME * 2) {
                    if (it % 2 == 0) 0.5f else -0.5f
                },
                longArrayOf(1, 1),
            ),
            expectedTokenCount = 2,
        )
        val bytes = artifact.file.readBytes()

        assertTrue(artifact.file.isFile)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(1, littleEndianShort(bytes, 20))
        assertEquals(1, littleEndianShort(bytes, 22))
        assertEquals(24_000, littleEndianInt(bytes, 24))
        assertEquals(16, littleEndianShort(bytes, 34))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(2_400, littleEndianInt(bytes, 40))
        assertEquals(2_444, bytes.size)
        assertEquals(16_384, littleEndianShort(bytes, 44))
        assertEquals(-16_384, littleEndianSignedShort(bytes, 46))
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianSignedShort(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset).toShort().toInt()

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)
}
