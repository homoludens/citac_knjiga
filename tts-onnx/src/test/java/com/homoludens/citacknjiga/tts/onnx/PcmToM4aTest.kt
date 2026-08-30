package com.homoludens.citacknjiga.tts.onnx

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
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class PcmToM4aTest {
    @Test
    public fun validWavIsStrictlyParsed() {
        val directory = createTempDirectory().toFile()
        val wav = File(directory, "input.wav").apply { writeBytes(validWav()) }

        val info = PcmWavValidator.validate(wav)

        assertEquals(44L, info.dataOffset)
        assertEquals(960L, info.dataSizeBytes)
        assertEquals(480L, info.sampleCount)
        assertEquals(20L, info.durationMs)
    }

    @Test
    public fun failedReplacementKeepsReadyArtifactAndRawPcm() {
        val fixture = fixture(withReadyArtifact = true)
        val oldBytes = fixture.oldReady!!.readBytes()

        val failure = runCatching {
            fixture.publisher(AacEncodingException("AAC_ENCODING_FAILURE", "codec failed"))
                .publish(fixture.run, fixture.claimed, fixture.staging, provenance())
        }.exceptionOrNull()

        assertTrue(failure is AacEncodingException)
        assertEquals(oldBytes.toList(), fixture.oldReady.readBytes().toList())
        assertTrue(fixture.staging.isFile)
        assertFalse(fixture.state.completed)
    }

    @Test
    public fun invalidM4aValidationKeepsReadyArtifactAndRawPcm() {
        val fixture = fixture(withReadyArtifact = true, encoderFailure = null)
        val oldBytes = fixture.oldReady!!.readBytes()
        val invalidEncoder = PcmToM4aEncoder { _, output ->
            output.writeBytes("not an m4a".toByteArray())
            EncodedM4a(0L)
        }
        val publisher = AudioArtifactPublisher(
            storage = fixture.storage,
            artifactStore = AtomicArtifactStore(fixture.storage),
            state = fixture.state,
            encoder = invalidEncoder,
            m4aValidator = StructuralM4aValidator,
        )

        val failure = runCatching {
            publisher.publish(fixture.run, fixture.claimed, fixture.staging, provenance())
        }.exceptionOrNull()

        assertTrue(failure is AacEncodingException)
        assertEquals(oldBytes.toList(), fixture.oldReady.readBytes().toList())
        assertTrue(fixture.staging.isFile)
    }

    @Test
    public fun unavailableAacFallsBackToValidatedWavAndDeletesRawAfterRoomReady() {
        val fixture = fixture(withReadyArtifact = false)

        val result = fixture.publisher(AacEncodingException("AAC_UNAVAILABLE", "no AAC encoder"))
            .publish(fixture.run, fixture.claimed, fixture.staging, provenance())

        assertEquals(PublishedAudioFormat.PCM_WAV, result.format)
        assertTrue(result.artifact.file.name.endsWith(".wav"))
        assertTrue(result.artifact.file.isFile)
        assertFalse(fixture.staging.exists())
        assertTrue(fixture.state.completed)
        assertEquals(result.artifact.file.path, fixture.state.completedAudioPath)
    }

    @Test
    public fun verifiedM4aIsPublishedWithChecksumAndRoomCheckpoint() {
        val fixture = fixture(withReadyArtifact = false, encoderFailure = null)

        val result = fixture.publisher()
            .publish(fixture.run, fixture.claimed, fixture.staging, provenance())

        assertEquals(PublishedAudioFormat.AAC_M4A, result.format)
        assertTrue(result.artifact.file.isFile)
        assertEquals(result.artifact.sizeBytes, result.artifact.file.length())
        assertEquals(100L, result.durationMs)
        assertFalse(fixture.staging.exists())
        assertTrue(fixture.state.completed)
    }

    @Test
    public fun failedRoomCheckpointDoesNotReplaceExistingReadyArtifact() {
        val fixture = fixture(withReadyArtifact = true, roomFailure = IllegalStateException("database stopped"))
        val oldBytes = fixture.oldReady!!.readBytes()

        val failure = runCatching {
            fixture.publisher().publish(fixture.run, fixture.claimed, fixture.staging, provenance())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(oldBytes.toList(), fixture.oldReady.readBytes().toList())
        assertTrue(fixture.staging.isFile)
    }

    @Test
    public fun portablePathDoesNotUseWavFallback() {
        val fixture = fixture(withReadyArtifact = false, encoderFailure = AacEncodingException("AAC_UNAVAILABLE", "install a platform AAC encoder"))

        val failure = runCatching {
            fixture.publisher().publish(fixture.run, fixture.claimed, fixture.staging, provenance(), portable = true)
        }.exceptionOrNull()

        assertTrue(failure is AacEncodingException)
        assertEquals("AAC_UNAVAILABLE", (failure as AacEncodingException).failureCode)
        assertTrue(fixture.staging.isFile)
        assertFalse(fixture.state.completed)
    }

    private fun Fixture.publisher(encoderFailure: AacEncodingException? = this.encoderFailure): AudioArtifactPublisher {
        val encoder = PcmToM4aEncoder { _, output ->
            encoderFailure?.let { throw it }
            output.writeBytes(validM4a())
            EncodedM4a(100L)
        }
        return AudioArtifactPublisher(
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            state = state,
            encoder = encoder,
            m4aValidator = M4aValidator { file ->
                StructuralM4aValidator.validate(file).copy(durationMs = 100L)
            },
        )
    }

    private fun fixture(
        withReadyArtifact: Boolean,
        encoderFailure: AacEncodingException? = AacEncodingException("AAC_UNAVAILABLE", "no AAC encoder"),
        roomFailure: Throwable? = null,
    ): Fixture {
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val staging = storage.temporaryFile("segment", "input.wav").apply {
            checkNotNull(parentFile).mkdirs()
            writeBytes(validWav())
        }
        val segment = segment()
        val oldReady = if (withReadyArtifact) storage.readySegmentAudio("book", "chapter", "segment").apply {
            checkNotNull(parentFile).mkdirs()
            writeBytes("old verified m4a".toByteArray())
        } else {
            null
        }
        val claimed = ClaimedGenerationSegment(segment.copy(audioPath = oldReady?.path), block())
        return Fixture(
            storage = storage,
            staging = staging,
            oldReady = oldReady,
            claimed = claimed,
            run = run(),
            state = FakeState(roomFailure),
            encoderFailure = encoderFailure,
        )
    }

    private data class Fixture(
        val storage: AppPrivateStorage,
        val staging: File,
        val oldReady: File?,
        val claimed: ClaimedGenerationSegment,
        val run: GenerationRunEntity,
        val state: FakeState,
        val encoderFailure: AacEncodingException?,
    )

    private class FakeState(private val roomFailure: Throwable?) : GenerationStateGateway {
        var completed = false
        var completedAudioPath: String? = null

        override fun findGenerationRun(runId: String): GenerationRunEntity? = run()
        override fun startGenerationRun(runId: String): GenerationRunEntity = run()
        override fun claimNextSegment(runId: String): ClaimedGenerationSegment? = null

        override fun completeAudioSegment(
            segmentId: String,
            published: PublishedArtifact,
            audio: GeneratedSegmentAudio,
        ): AudioSegmentEntity {
            roomFailure?.let { throw it }
            completed = true
            completedAudioPath = published.file.path
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

        fun block() = NarrationBlockEntity(
            id = "block",
            chapterId = "chapter",
            ordinal = 0,
            blockType = NarrationBlockType.PARAGRAPH,
            sourceText = "Dobar dan.",
            createdAt = 1L,
            updatedAt = 1L,
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
            val dataSize = 480 * 2
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
                repeat(480) { putShort(if (it % 2 == 0) 2_000 else -2_000) }
            }.array()
        }

        fun validM4a(): ByteArray = ByteBuffer.allocate(16 + 8 + 9).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(16)
            put("ftyp".toByteArray(Charsets.US_ASCII))
            put("M4A ".toByteArray(Charsets.US_ASCII))
            putInt(0)
            putInt(8)
            put("moov".toByteArray(Charsets.US_ASCII))
            putInt(9)
            put("mdat".toByteArray(Charsets.US_ASCII))
            put(0)
        }.array()
    }
}
