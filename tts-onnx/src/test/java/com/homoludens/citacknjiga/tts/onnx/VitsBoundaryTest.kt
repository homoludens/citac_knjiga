package com.homoludens.citacknjiga.tts.onnx

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class VitsBoundaryTest {
    @Test
    public fun resamplerAcceptsOnlyNativeAudioAndProducesOneFinalRate() {
        val audio = VitsNativeAudio(FloatArray(4_410) { if (it % 2 == 0) 0.1f else -0.1f })
        val output = VitsAudioOutputValidator.resampleOnce(audio)
        assertEquals(4_800, output.pcm.size)
        assertEquals(24_000, output.sampleRateHz)
        assertEquals(1, output.channels)
        assertTrue(output.pcm.all { it.isFinite() })
    }

    @Test
    public fun resamplerRejectsWrongRateAndInvalidSamples() {
        assertTrue(runCatching {
            VitsAudioOutputValidator.resampleOnce(VitsNativeAudio(floatArrayOf(0.1f), 24_000, 1))
        }.isFailure)
        assertTrue(runCatching {
            VitsAudioOutputValidator.resampleOnce(VitsNativeAudio(floatArrayOf(Float.NaN)))
        }.isFailure)
    }

    @Test
    public fun frontendRejectsUnsupportedInputAndHandlesDeclaredSerbianForms() {
        val vocabulary = VitsSerbianFrontendTestVocabulary.map
        val frontend = VitsSerbianFrontend(vocabulary, blankId = 139)
        val output = frontend.process("Čao, npr.")
        assertEquals("чао, на пример", output.normalizedText)
        assertEquals(listOf(139, 42, 139, 15, 139, 32, 139, 6, 139, 14, 139,
            30, 139, 15, 139, 14, 139, 33, 139, 34, 139, 24, 139, 29, 139,
            21, 139, 34, 139), output.tokenIds)
        assertTrue(runCatching { frontend.process("Знак §") }.isFailure)
        assertTrue(runCatching { frontend.process("Чао 2") }.isFailure)
    }

    @Test
    public fun sessionClosesNativeHandleAndChecksCancellation() {
        val native = object : SherpaVitsNativeSession {
            var closed = false
            override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float) =
                VitsNativeAudio(floatArrayOf(0.1f, -0.1f))
            override fun close() { closed = true }
        }
        val session = SherpaVitsSession.fromNative(native)
        val tokenIds = intArrayOf(139, 36, 139, 21, 139, 26, 139, 35, 139)
        val cancelled = AtomicBoolean(true)
        assertTrue(runCatching { session.generate(tokenIds, 0, 1f) { cancelled.get() } }.isFailure)
        session.close()
        assertTrue(native.closed)
        session.close()
        assertFalse(runCatching { session.generate(tokenIds, 0, 1f) }.isSuccess)
    }

    @Test
    public fun unqualifiedVitsIsNotExposedAndKokoroRemainsDefault() {
        val selector = TtsEngineSelector(
            VitsModelPackageStore(createTempDirectory().toFile()),
            apiLevel = 33,
            abi = "arm64-v8a",
        )
        assertEquals(listOf(TtsEngine.KOKORO), selector.available())
        assertEquals(TtsEngine.KOKORO, selector.select(TtsEngine.VITS))
    }
}

private object VitsSerbianFrontendTestVocabulary {
    val map: Map<Int, Int> = ("!+'(),-.:;_?/ " + "абвгдђежзијклљмнњопрстћуфхцчџш")
        .mapIndexed { index, value -> value.code to index + 1 }
        .toMap()
}
