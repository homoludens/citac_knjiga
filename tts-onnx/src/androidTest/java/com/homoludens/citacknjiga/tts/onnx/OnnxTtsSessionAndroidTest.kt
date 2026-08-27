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
            assertEquals(0.5f, output.pcm.first())
            assertEquals(0.5f, output.pcm.last())
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
            "CAgSDXRhc2stNC42LXRlc3Q62wMKJwoJaW5wdXRfaWRzEgl3YXZlX2Nhc3QiBENhc3QqCQoCdG8YAaABAgoyCgl3YXZlX2Nhc3QKBGhhbGYSCXdhdmVfYmFzZRoPb2Zmc2V0X3dhdmVmb3JtIgNBZGQKJQoJd2F2ZV9iYXNlCgRheGVzEgl3YXZlX2ZsYXQiB1NxdWVlemUKIQoJd2F2ZV9mbGF0CgRyZXBzEgh3YXZlZm9ybSIEVGlsZQofCglpbnB1dF9pZHMKA29uZRIIZHVyX2Jhc2UiA0FkZAojCghkdXJfYmFzZQoEYXhlcxIIcHJlZF9kdXIiB1NxdWVlemUSFmRldGVybWluaXN0aWMtYm91bmRhcnkqDAgBEAc6AQFCA29uZSoOCAEQBzoCrAJCBHJlcHMqDQgBEAc6AQBCBGF4ZXMqEAgBEAFCBGhhbGZKBAAAAD9aIgoJaW5wdXRfaWRzEhUKEwgHEg8KAggBCgkSB3NlcV9sZW5aGAoFcmVmX3MSDwoNCAESCQoCCAEKAwiAAloPCgVzcGVlZBIGCgQIARIAYiIKCHdhdmVmb3JtEhYKFAgBEhAKDhIMd2F2ZWZvcm1fbGVuYiIKCHByZWRfZHVyEhYKFAgHEhAKDhIMcHJlZF9kdXJfbGVuQgQKABAS",
        )
    }
}
