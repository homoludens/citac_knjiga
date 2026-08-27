package com.homoludens.citacknjiga.tts.onnx.preprocessing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class SerbianPreprocessingAndroidTest {
    @Test
    public fun loadsGoldenCorpusFromAndroidTestAsset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val corpus = instrumentation.context.assets.open("vectors.json").use(GoldenVectorFixtures::load)

        assertEquals(26, corpus.vectors.size)
        assertEquals("greeting-latin", corpus.vectors.first().id)
        assertEquals("paragraph-no-sentence-boundary", corpus.vectors.last().id)
    }

    @Test
    public fun nativePronunciationRemainsFailClosedOnAndroid() {
        val resources = SerbianPreprocessingResources.fromAssets(
            InstrumentationRegistry.getInstrumentation().targetContext.assets,
        )
        val failure = try {
            SerbianPreprocessor(resources).process("Dobar dan.")
            error("expected the unavailable native phonemizer to fail")
        } catch (exception: SerbianPreprocessingException) {
            exception
        }

        assertEquals(PreprocessingStage.PHONEMES, failure.stage)
        assertTrue(failure.message.orEmpty().contains("no approximation"))
    }

    @Test
    public fun nativeSmokeGateMatchesRepresentativeVectors() {
        val corpus = loadCorpus()
        val ids = setOf("greeting-latin", "diacritics-latin", "mixed-scripts", "input-limit-at", "paragraph-no-sentence-boundary")
        val expected = corpus.vectors.filter { it.id in ids }
        assertEquals(ids, expected.map { it.id }.toSet())
        val preprocessor = nativePreprocessor()

        expected.forEach { vector ->
            val actual = preprocessor.process(vector.text)
            assertNull("${vector.id}: first divergent stage", firstDivergentStage(vector, actual))
        }
    }

    @Test
    public fun nativeFullGoldenGateMatchesAllVectors() {
        val corpus = loadCorpus()
        val preprocessor = nativePreprocessor()

        corpus.vectors.forEach { vector ->
            val actual = preprocessor.process(vector.text)
            assertNull("${vector.id}: first divergent stage", firstDivergentStage(vector, actual))
        }
    }

    private fun nativePreprocessor(): SerbianPreprocessor {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        return SerbianPreprocessor.fromAssets(targetContext.assets, targetContext.filesDir)
    }

    private fun loadCorpus(): GoldenCorpus = InstrumentationRegistry.getInstrumentation().context.assets
        .open("vectors.json").use(GoldenVectorFixtures::load)
}
