package com.homoludens.citacknjiga.tts.onnx.preprocessing

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class SerbianPreprocessingTest {
    @Test
    public fun loadsTheCommittedGoldenCorpusAsJvmFixture() {
        val corpus = loadCorpus()

        assertEquals("reference-20260827-task-3.5", corpus.version)
        assertEquals(26, corpus.vectors.size)
        assertEquals("greeting-latin", corpus.vectors.first().id)
        assertEquals("paragraph-no-sentence-boundary", corpus.vectors.last().id)
    }

    @Test
    public fun kotlinStagesMatchGoldenOutputsAfterInjectedReferencePhonemes() {
        val corpus = loadCorpus()
        val resources = loadResources()
        val phonemesByText = corpus.vectors.associate { it.normalizedText to it.phonemes }
        val preprocessor = SerbianPreprocessor(
            resources = resources,
            phonemizer = SerbianPhonemizer { text -> phonemesByText.getValue(text) },
        )

        corpus.vectors.forEach { expected ->
            val actual = try {
                preprocessor.process(expected.text)
            } catch (failure: SerbianPreprocessingException) {
                throw AssertionError("${expected.id}: ${failure.message}", failure)
            }
            assertNull("${expected.id}: first divergent stage", firstDivergentStage(expected, actual))
        }
    }

    @Test
    public fun unavailableNativePhonemizerFailsClosedAtPhonemeStage() {
        val exception = try {
            SerbianPreprocessor(loadResources()).process("Dobar dan.")
            error("expected the unavailable native phonemizer to fail")
        } catch (failure: SerbianPreprocessingException) {
            failure
        }

        assertEquals(PreprocessingStage.PHONEMES, exception.stage)
        assertEquals(PreprocessingFailureCode.NATIVE_PHONEMIZER_UNAVAILABLE, exception.code)
        assertTrue(exception.message.orEmpty().contains("no approximation"))
    }

    @Test
    public fun firstDivergenceStopsAtTheEarliestStage() {
        val expected = loadCorpus().vectors.first()
        val actual = SerbianPreprocessingOutput(
            cleanupText = expected.cleanupText,
            normalizedText = "different",
            phonemes = "different",
            tokenIds = listOf(0, 0),
            protectedSpans = listOf(TextRange(0, 1)),
            chunkBoundaries = listOf(TextRange(0, 1)),
            voiceRowIndex = 1,
        )

        assertEquals(PreprocessingStage.NORMALIZED_TEXT, firstDivergentStage(expected, actual))
    }

    private fun loadCorpus(): GoldenCorpus = fixture("vectors.json").use(GoldenVectorFixtures::load)

    private fun loadResources(): SerbianPreprocessingResources = SerbianPreprocessingResources.fromJson(
        normalization = fixture("normalization-v1.json"),
        vocabulary = fixture("vocabulary-v1.json"),
        chunking = fixture("chunking-v1.json"),
    )

    private fun fixture(name: String): InputStream = requireNotNull(
        requireNotNull(javaClass.classLoader).getResourceAsStream(name),
    ) { "missing JVM fixture: $name" }
}
