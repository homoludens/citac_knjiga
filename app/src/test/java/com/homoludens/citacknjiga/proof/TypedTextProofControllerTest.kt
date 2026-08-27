package com.homoludens.citacknjiga.proof

import com.homoludens.citacknjiga.tts.onnx.WavArtifact
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class TypedTextProofControllerTest {
    @Test
    public fun successfulGenerationPublishesDiagnosticsAndWav() = runBlocking {
        val controller = TypedTextProofController(fakeEngine(), Dispatchers.Unconfined)
        controller.generate()

        assertEquals(TypedTextProofStatus.SUCCESS, controller.state.value.status)
        assertEquals("Dobar dan.", controller.state.value.diagnostics?.normalizedText)
        assertTrue(controller.state.value.wav?.file?.name == "proof.wav")
        controller.close()
    }

    @Test
    public fun failedGenerationPublishesExplicitError() = runBlocking {
        val controller = TypedTextProofController(
            engine = object : TypedTextProofEngine {
                override suspend fun generate(
                    text: String,
                    onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
                ): TypedTextProofResult = error("No verified model package is installed")
            },
            dispatcher = Dispatchers.Unconfined,
        )
        controller.generate()

        assertEquals(TypedTextProofStatus.ERROR, controller.state.value.status)
        assertTrue(controller.state.value.errorMessage.orEmpty().contains("No verified model package"))
        controller.close()
    }

    @Test
    public fun cancellationPublishesCancelledState() = runBlocking {
        val controller = TypedTextProofController(
            engine = object : TypedTextProofEngine {
                override suspend fun generate(
                    text: String,
                    onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
                ): TypedTextProofResult {
                    awaitCancellation()
                }
            },
            dispatcher = Dispatchers.Unconfined,
        )
        controller.generate()
        assertEquals(TypedTextProofStatus.GENERATING, controller.state.value.status)
        controller.cancel()

        assertEquals(TypedTextProofStatus.CANCELLED, controller.state.value.status)
        controller.close()
    }

    private fun fakeEngine(): TypedTextProofEngine = object : TypedTextProofEngine {
        override suspend fun generate(
            text: String,
            onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
        ): TypedTextProofResult {
            val diagnostics = TypedTextProofDiagnostics(
                cleanupText = text,
                normalizedText = text,
                phonemes = "d o b a r",
                tokenIds = listOf(0, 1, 0),
                protectedSpans = emptyList(),
                chunkBoundaries = listOf("0..7"),
                voiceRowIndex = 7,
                model = TypedTextModelProvenance("test", "1.0.0", "a".repeat(64)),
            )
            onDiagnostics(diagnostics)
            return TypedTextProofResult(diagnostics, WavArtifact(File("proof.wav"), 600, 24_000))
        }
    }
}
