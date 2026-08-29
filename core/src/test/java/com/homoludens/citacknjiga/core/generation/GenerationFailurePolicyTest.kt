package com.homoludens.citacknjiga.core.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class GenerationFailurePolicyTest {
    @Test
    public fun typedFailuresKeepStableCategoriesAndCodes() {
        val audio = GenerationFailurePolicy.classify(
            GenerationFailureException(
                GenerationFailureCategory.AUDIO_VALIDATION,
                "AUDIO_CLIPPING",
                "audio is clipped",
            ),
            GenerationFailurePhase.PUBLICATION,
        )
        val provenance = GenerationFailurePolicy.classify(
            GenerationFailureException(
                GenerationFailureCategory.PROVENANCE,
                "PROVENANCE_MISMATCH",
                "wrong model",
            ),
            GenerationFailurePhase.INFERENCE,
        )

        assertEquals(GenerationFailureCategory.AUDIO_VALIDATION, audio.category)
        assertEquals("AUDIO_CLIPPING", audio.code)
        assertTrue(audio.retryable)
        assertFalse(provenance.retryable)
    }

    @Test
    public fun phaseClassifiesUntypedInferenceAndPublicationFailures() {
        val inference = GenerationFailurePolicy.classify(
            IllegalStateException("session failed"),
            GenerationFailurePhase.INFERENCE,
        )
        val write = GenerationFailurePolicy.classify(
            IllegalStateException("cannot rename"),
            GenerationFailurePhase.PUBLICATION,
        )

        assertEquals(GenerationFailureCategory.INFERENCE, inference.category)
        assertEquals("INFERENCE_FAILURE", inference.code)
        assertEquals(GenerationFailureCategory.WRITE, write.category)
        assertEquals("WRITE_FAILURE", write.code)
    }

    @Test
    public fun retryPolicyStopsAtThePersistedAttemptLimit() {
        val policy = GenerationRetryPolicy(maxAttempts = 3)
        val failure = ClassifiedGenerationFailure(
            category = GenerationFailureCategory.INFERENCE,
            code = "INFERENCE_FAILURE",
            message = "retry me",
            retryable = true,
        )

        assertTrue(policy.shouldRetry(failure, attemptCount = 1))
        assertTrue(policy.shouldRetry(failure, attemptCount = 2))
        assertFalse(policy.shouldRetry(failure, attemptCount = 3))
        assertFalse(policy.shouldRetry(failure.copy(retryable = false), attemptCount = 1))
    }
}
