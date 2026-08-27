package com.homoludens.citacknjiga.tts.onnx

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class OnnxTtsSessionAndroidTest {
    @Test
    public fun runsTheDeclaredNamedTypedBoundaryAndConvertsPcm() {
        OnnxTtsSession.fromArtifacts(TEST_MODEL, FloatArray(DraganaStyleTable.VALUE_COUNT)).use { session ->
            val output = session.generate(listOf(0, 0), speed = 1f)

            assertEquals(OnnxRuntimeContract.SAMPLE_RATE_HZ, output.sampleRateHz)
            assertEquals(OnnxRuntimeContract.CHANNELS, output.channels)
            assertArrayEquals(longArrayOf(1, 1), output.predDur)
            assertEquals(600, output.pcm.size)
            assertEquals(0f, output.pcm.first())
            assertEquals(0f, output.pcm.last())
        }
    }

    @Test
    public fun closesSessionDeterministicallyAndIsIdempotent() {
        val session = OnnxTtsSession.fromArtifacts(TEST_MODEL, FloatArray(DraganaStyleTable.VALUE_COUNT))

        session.close()
        session.close()

        assertThrows(IllegalStateException::class.java) { session.generate(listOf(0, 0), 1f) }
    }

    private companion object {
        val TEST_MODEL: ByteArray = Base64.getDecoder().decode(
            "CAgSDXRhc2stNC42LXRlc3Q6lQMKJwoJaW5wdXRfaWRzEgl3YXZlX2Jhc2UiBENhc3QqCQoCdG8YAaABAgolCgl3YXZlX2Jhc2UKBGF4ZXMSCXdhdmVfZmxhdCIHU3F1ZWV6ZQohCgl3YXZlX2ZsYXQKBHJlcHMSCHdhdmVmb3JtIgRUaWxlCh8KCWlucHV0X2lkcwoDb25lEghkdXJfYmFzZSIDQWRkCiMKCGR1cl9iYXNlCgRheGVzEghwcmVkX2R1ciIHU3F1ZWV6ZRIWZGV0ZXJtaW5pc3RpYy1ib3VuZGFyeSoMCAEQBzoBAUIDb25lKg4IARAHOgKsAkIEcmVwcyoNCAEQBzoBAEIEYXhlc1oiCglpbnB1dF9pZHMSFQoTCAcSDwoCCAEKCRIHc2VxX2xlbloYCgVyZWZfcxIPCg0IARIJCgIIAQoDCIACWg8KBXNwZWVkEgYKBAgBEgBiIgoId2F2ZWZvcm0SFgoUCAESEAoOEgx3YXZlZm9ybV9sZW5iIgoIcHJlZF9kdXISFgoUCAcSEAoOEgxwcmVkX2R1cl9sZW5CBAoAEBI=",
        )
    }
}
