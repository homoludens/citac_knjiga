package com.homoludens.citacknjiga.tts.onnx.preprocessing

import com.homoludens.citacknjiga.core.generation.GenerationKeyCalculator
import com.homoludens.citacknjiga.core.generation.GenerationKeyInput
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
    public fun equivalentLatinAndCyrillicVectorsKeepTheSameDownstreamIdentity() {
        val corpus = loadCorpus()
        val vectors = corpus.vectors.associateBy { it.id }
        val phonemesByText = corpus.vectors.associate { it.normalizedText to it.phonemes }
        val preprocessor = SerbianPreprocessor(
            resources = loadResources(),
            phonemizer = SerbianPhonemizer { text -> phonemesByText.getValue(text) },
        )
        val shared = mapOf(
            "modelSha256" to "model",
            "voiceSha256" to "voice",
            "preprocessingVersion" to "prep-v1",
            "pronunciationVersion" to "pron-v1",
            "audioProcessingVersion" to "audio-v1",
        )

        listOf(
            "greeting-latin" to "greeting-cyrillic",
            "digraphs-latin" to "digraphs-cyrillic",
        ).forEach { (latinId, cyrillicId) ->
            val latin = preprocessor.process(vectors.getValue(latinId).text)
            val cyrillic = preprocessor.process(vectors.getValue(cyrillicId).text)
            assertEquals(latin.phonemes, cyrillic.phonemes)
            assertEquals(latin.tokenIds, cyrillic.tokenIds)
            assertEquals(latin.chunkBoundaries, cyrillic.chunkBoundaries)
            assertEquals(
                GenerationKeyCalculator.calculate(
                    GenerationKeyInput(
                        tokens = latin.tokenIds,
                        modelSha256 = shared.getValue("modelSha256"),
                        voiceSha256 = shared.getValue("voiceSha256"),
                        preprocessingVersion = shared.getValue("preprocessingVersion"),
                        pronunciationVersion = shared.getValue("pronunciationVersion"),
                        inferenceSettings = mapOf("speed" to "1.0"),
                        audioProcessingVersion = shared.getValue("audioProcessingVersion"),
                    ),
                ),
                GenerationKeyCalculator.calculate(
                    GenerationKeyInput(
                        tokens = cyrillic.tokenIds,
                        modelSha256 = shared.getValue("modelSha256"),
                        voiceSha256 = shared.getValue("voiceSha256"),
                        preprocessingVersion = shared.getValue("preprocessingVersion"),
                        pronunciationVersion = shared.getValue("pronunciationVersion"),
                        inferenceSettings = mapOf("speed" to "1.0"),
                        audioProcessingVersion = shared.getValue("audioProcessingVersion"),
                    ),
                ),
            )
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

    @Test
    public fun oversizedInputProducesCompleteModelSizedInputs() {
        val expected = loadCorpus().vectors.last()
        val resources = loadResources()
        val preprocessor = SerbianPreprocessor(
            resources = resources,
            phonemizer = SerbianPhonemizer { expected.phonemes },
        )

        val output = preprocessor.process(expected.text)
        val chunks = output.chunkBoundaries.map(output::tokenIdsForChunk)

        assertEquals(listOf(508, 19), chunks.map { it.size })
        assertTrue(chunks.all { it.size <= 512 && it.first() == 0 && it.last() == 0 })
    }

    @Test
    public fun exposesTheVerifiedOperationalAndHardModelLimits() {
        val limits = loadResources().modelLimits

        assertEquals(507, limits.operationalPhonemeSymbols)
        assertEquals(510, limits.hardPhonemeSymbols)
        assertEquals(512, limits.modelMaxSequenceLength)
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
