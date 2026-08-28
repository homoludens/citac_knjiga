package com.homoludens.citacknjiga.tts.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class OnnxAudioOutputValidatorTest {
    @Test
    public fun acceptsAValidOutput() {
        OnnxAudioOutputValidator.validate(validOutput(), expectedTokenCount = 2)
    }

    @Test
    public fun rejectsNonFiniteSamples() {
        val pcm = validPcm()
        pcm[10] = Float.NaN

        assertCode(OnnxAudioFailureCode.NON_FINITE_SAMPLES, OnnxTtsOutput(pcm, longArrayOf(1, 1)))
    }

    @Test
    public fun rejectsSilence() {
        assertCode(
            OnnxAudioFailureCode.SILENCE,
            OnnxTtsOutput(FloatArray(OnnxRuntimeContract.SAMPLES_PER_DURATION_FRAME * 2), longArrayOf(1, 1)),
        )
    }

    @Test
    public fun rejectsNearSilenceBelowTheRmsFloor() {
        assertCode(
            OnnxAudioFailureCode.SILENCE,
            OnnxTtsOutput(
                FloatArray(OnnxRuntimeContract.SAMPLES_PER_DURATION_FRAME * 2) { 0.0005f },
                longArrayOf(1, 1),
            ),
        )
    }

    @Test
    public fun rejectsNearSilenceAboveTheSilentFractionLimit() {
        val pcm = FloatArray(OnnxRuntimeContract.SAMPLES_PER_DURATION_FRAME * 2)
        pcm[0] = 0.1f
        pcm[1] = -0.1f

        assertCode(OnnxAudioFailureCode.SILENCE, OnnxTtsOutput(pcm, longArrayOf(1, 1)))
    }

    @Test
    public fun rejectsClippingAndOutOfDomainSamples() {
        val pcm = validPcm()
        pcm[10] = 1f

        assertCode(OnnxAudioFailureCode.CLIPPING, OnnxTtsOutput(pcm, longArrayOf(1, 1)))
    }

    @Test
    public fun rejectsWrongSampleRateAndChannelMetadata() {
        assertCode(
            OnnxAudioFailureCode.INVALID_SAMPLE_RATE,
            validOutput().copy(sampleRateHz = 16_000),
        )
        assertCode(
            OnnxAudioFailureCode.INVALID_CHANNEL_COUNT,
            validOutput().copy(channels = 2),
        )
    }

    @Test
    public fun rejectsInconsistentSampleCount() {
        assertCode(
            OnnxAudioFailureCode.SAMPLE_COUNT_MISMATCH,
            OnnxTtsOutput(validPcm().copyOf(validPcm().size - 1), longArrayOf(1, 1)),
        )
    }

    @Test
    public fun rejectsImplausibleDuration() {
        assertCode(
            OnnxAudioFailureCode.IMPLAUSIBLE_DURATION,
            OnnxTtsOutput(validPcm(), longArrayOf(OnnxRuntimeContract.MAX_DURATION_FRAMES + 1, 1)),
        )
    }

    private fun assertCode(code: OnnxAudioFailureCode, output: OnnxTtsOutput) {
        val exception = assertThrows(OnnxAudioValidationException::class.java) {
            OnnxAudioOutputValidator.validate(output, expectedTokenCount = 2)
        }
        assertEquals(code, exception.code)
    }

    private fun validOutput(): OnnxTtsOutput = OnnxTtsOutput(validPcm(), longArrayOf(1, 1))

    private fun validPcm(): FloatArray = FloatArray(OnnxRuntimeContract.SAMPLES_PER_DURATION_FRAME * 2) { index ->
        if (index % 2 == 0) 0.1f else -0.1f
    }
}
