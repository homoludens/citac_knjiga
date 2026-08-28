package com.homoludens.citacknjiga.proof

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device gate for the complete typed-text proof path, using only private files and assets. */
public class TypedTextProofAndroidTest {
    @Test
    public fun verifiedTextPathProducesAndPlaysTwentyFourKilohertzWav() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidTypedTextProofEngine(
            modelStore = ModelPackageStore(context.filesDir),
            preprocessorFactory = { SerbianPreprocessor.fromAssets(context.assets, context.filesDir) },
            artifactDirectory = File(context.filesDir, "typed-proof-test"),
        )

        val result = engine.generate(TypedTextProofState().text) {}

        assertFalse(result.diagnostics.phonemes.isBlank())
        assertTrue(result.diagnostics.tokenIds.size >= 2)
        assertEquals(0, result.diagnostics.tokenIds.first())
        assertEquals(0, result.diagnostics.tokenIds.last())
        assertEquals(24_000, result.wav.sampleRateHz)
        assertTrue(result.wav.sampleCount > 0)
        assertTrue(result.wav.file.isFile)

        val pcm = FileInputStream(result.wav.file).use { input ->
            val header = ByteArray(44)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                assertTrue(count > 0)
                offset += count
            }
            assertTrue(header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
            assertTrue(header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()))
            assertEquals(24_000, littleEndianInt(header, 24))
            assertEquals(1, littleEndianShort(header, 22))
            assertEquals(16, littleEndianShort(header, 34))
            input.readBytes()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(24_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            track.play()
            assertTrue(track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) > 0)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)
}
