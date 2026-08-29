package com.homoludens.citacknjiga.core.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class GenerationKeyTest {
    @Test
    public fun sameInputsProduceStableKeysWithoutTimestamps() {
        val input = input()

        assertEquals(GenerationKeyCalculator.calculate(input), GenerationKeyCalculator.calculate(input))
    }

    @Test
    public fun tokenChangesInvalidateOnlyTheGenerationKey() {
        val original = GenerationKeyCalculator.calculate(input(tokens = listOf(0, 11, 0)))
        val changed = GenerationKeyCalculator.calculate(input(tokens = listOf(0, 12, 0)))

        assertEquals(original.dependencyKey, changed.dependencyKey)
        assertNotEquals(original.generationKey, changed.generationKey)
    }

    @Test
    public fun changingOneBlockLeavesUnchangedBlockReusable() {
        val original = listOf(
            GenerationKeyCalculator.calculate(input(tokens = listOf(0, 11, 0))),
            GenerationKeyCalculator.calculate(input(tokens = listOf(0, 12, 0))),
        )
        val afterOneBlockChange = listOf(
            GenerationKeyCalculator.calculate(input(tokens = listOf(0, 11, 0))),
            GenerationKeyCalculator.calculate(input(tokens = listOf(0, 13, 0))),
        )

        assertEquals(original[0], afterOneBlockChange[0])
        assertEquals(original[1].dependencyKey, afterOneBlockChange[1].dependencyKey)
        assertNotEquals(original[1].generationKey, afterOneBlockChange[1].generationKey)
    }

    @Test
    public fun settingsMapOrderIsCanonicalButTokenOrderIsSignificant() {
        val first = GenerationKeyCalculator.calculate(
            input(inferenceSettings = linkedMapOf("speed" to "1.0", "pause_ms" to "120")),
        )
        val reorderedSettings = GenerationKeyCalculator.calculate(
            input(inferenceSettings = linkedMapOf("pause_ms" to "120", "speed" to "1.0")),
        )
        val reorderedTokens = GenerationKeyCalculator.calculate(input(tokens = listOf(0, 12, 11, 0)))

        assertEquals(first, reorderedSettings)
        assertNotEquals(first.generationKey, reorderedTokens.generationKey)
    }

    @Test
    public fun everyDependencyInputInvalidatesBothKeys() {
        val original = GenerationKeyCalculator.calculate(input())
        val variants = listOf(
            input(modelSha256 = "model-b"),
            input(voiceSha256 = "voice-b"),
            input(preprocessingVersion = "preprocessing-v2"),
            input(pronunciationVersion = "pronunciation-v2"),
            input(inferenceSettings = mapOf("speed" to "1.1")),
            input(audioProcessingVersion = "audio-v2"),
        )

        variants.forEach { variant ->
            val changed = GenerationKeyCalculator.calculate(variant)
            assertNotEquals(original.dependencyKey, changed.dependencyKey)
            assertNotEquals(original.generationKey, changed.generationKey)
        }
    }

    @Test
    public fun keysAreLowercaseSha256Digests() {
        val keys = GenerationKeyCalculator.calculate(input())

        assertTrue(keys.dependencyKey.matches(Regex("[0-9a-f]{64}")))
        assertTrue(keys.generationKey.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(keys.dependencyKey, keys.generationKey)
    }

    private fun input(
        tokens: List<Int> = listOf(0, 11, 12, 0),
        modelSha256: String = "model-a",
        voiceSha256: String = "voice-a",
        preprocessingVersion: String = "preprocessing-v1",
        pronunciationVersion: String = "pronunciation-v1",
        inferenceSettings: Map<String, String> = mapOf("speed" to "1.0", "pause_ms" to "80"),
        audioProcessingVersion: String = "audio-v1",
    ): GenerationKeyInput = GenerationKeyInput(
        tokens = tokens,
        modelSha256 = modelSha256,
        voiceSha256 = voiceSha256,
        preprocessingVersion = preprocessingVersion,
        pronunciationVersion = pronunciationVersion,
        inferenceSettings = inferenceSettings,
        audioProcessingVersion = audioProcessingVersion,
    )
}
