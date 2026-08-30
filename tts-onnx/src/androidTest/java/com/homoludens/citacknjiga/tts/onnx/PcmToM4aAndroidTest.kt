package com.homoludens.citacknjiga.tts.onnx

import android.media.MediaCodecList
import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.generation.ClaimedGenerationSegment
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationError
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationStateGateway
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.core.storage.PublishedArtifact
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

public class PcmToM4aAndroidTest {
    @Test
    public fun realMediaCodecOutputPassesContainerAndTrackValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "A 24 kHz mono AAC-LC encoder is unavailable",
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MVP_AAC_MIME, ignoreCase = true) } &&
                    info.getCapabilitiesForType(MVP_AAC_MIME).audioCapabilities?.let {
                        it.isSampleRateSupported(MVP_AUDIO_SAMPLE_RATE_HZ) &&
                            it.maxInputChannelCount >= MVP_AUDIO_CHANNELS &&
                            it.bitrateRange.contains(MVP_AAC_BITRATE_BPS)
                    } == true
            },
        )
        val storage = AppPrivateStorage(context.filesDir)
        val input = storage.temporaryFile("task-10-3", "input.wav").apply {
            checkNotNull(parentFile).mkdirs()
            writeBytes(validWav())
        }
        val output = storage.temporaryFile("task-10-3", "output.m4a")
        try {
            AndroidMediaCodecAacEncoder().encode(input, output)
            val info = AndroidM4aValidator().validate(output)

            assertEquals(MVP_AAC_MIME, info.mimeType)
            assertEquals(MVP_AUDIO_SAMPLE_RATE_HZ, info.sampleRateHz)
            assertEquals(MVP_AUDIO_CHANNELS, info.channels)
            assertTrue(info.durationMs > 0L)
            assertTrue(output.length() > 0L)
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    public fun unavailableCodecUsesPrivateWavFallbackAndKeepsRawUntilReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = AppPrivateStorage(context.filesDir)
        val input = storage.temporaryFile("task-10-3-fallback", "input.wav").apply {
            checkNotNull(parentFile).mkdirs()
            writeBytes(validWav())
        }
        val state = RecordingState()
        try {
            val result = AudioArtifactPublisher(
                storage = storage,
                artifactStore = AtomicArtifactStore(storage),
                state = state,
                encoder = PcmToM4aEncoder { _, _ ->
                    throw AacEncodingException("AAC_UNAVAILABLE", "test codec unavailable")
                },
                m4aValidator = StructuralM4aValidator,
            ).publish(run(), claimed(), input, provenance())

            assertEquals(PublishedAudioFormat.PCM_WAV, result.format)
            assertTrue(result.artifact.file.name.endsWith(".wav"))
            assertTrue(state.completed)
            assertFalse(input.exists())
            assertTrue(PcmWavValidator.validate(result.artifact.file).durationMs > 0L)
        } finally {
            input.delete()
            storage.readyAudioDirectory.deleteRecursively()
            storage.temporaryDirectory.deleteRecursively()
        }
    }

    private class RecordingState : GenerationStateGateway {
        var completed = false

        override fun findGenerationRun(runId: String): GenerationRunEntity = run()
        override fun startGenerationRun(runId: String): GenerationRunEntity = run()
        override fun claimNextSegment(runId: String): ClaimedGenerationSegment? = null
        override fun completeAudioSegment(
            segmentId: String,
            published: PublishedArtifact,
            audio: GeneratedSegmentAudio,
        ): AudioSegmentEntity {
            completed = true
            return segment().copy(status = AudioSegmentStatus.READY, audioPath = published.file.path)
        }
        override fun failAudioSegment(segmentId: String, error: GenerationError): AudioSegmentEntity = segment()
        override fun retryAudioSegment(segmentId: String): AudioSegmentEntity = segment()
        override fun releaseAudioSegment(segmentId: String): AudioSegmentEntity = segment()
        override fun failGenerationRun(runId: String, error: GenerationError): GenerationRunEntity = run()
        override fun finishGenerationRun(runId: String): GenerationRunEntity = run()
    }

    private companion object {
        fun run() = GenerationRunEntity(
            id = "run",
            bookProjectId = "book",
            modelPackageId = "model",
            preprocessingVersion = "prep",
            pronunciationVersion = "pron",
            inferenceSettingsHash = "settings",
            audioProcessingVersion = "audio",
            status = GenerationRunStatus.RUNNING,
            requestedAt = 1L,
        )

        fun segment() = AudioSegmentEntity(
            id = "segment",
            chapterId = "chapter",
            narrationBlockId = "block",
            sequence = 0,
            chunkOrdinal = 0,
            generationRunId = "run",
            status = AudioSegmentStatus.GENERATING,
            createdAt = 1L,
            updatedAt = 1L,
        )

        fun claimed() = ClaimedGenerationSegment(
            segment(),
            NarrationBlockEntity(
                id = "block",
                chapterId = "chapter",
                ordinal = 0,
                blockType = NarrationBlockType.PARAGRAPH,
                sourceText = "Dobar dan.",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        fun provenance() = GenerationProvenance(
            generationKey = "generation",
            modelPackageId = "model",
            modelPackageSha256 = "model-sha",
            voiceSha256 = "voice-sha",
            preprocessingVersion = "prep",
            pronunciationVersion = "pron",
            inferenceSettingsHash = "settings",
            audioProcessingVersion = "audio",
        )

        fun validWav(): ByteArray {
            val dataSize = 12_000 * 2
            return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(MVP_AUDIO_SAMPLE_RATE_HZ)
                putInt(MVP_AUDIO_SAMPLE_RATE_HZ * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
                repeat(12_000) { putShort(if (it % 2 == 0) 2_000 else -2_000) }
            }.array()
        }
    }
}
