package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.core.storage.PublishedArtifact
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class BoundedGenerationRunnerTest {
    @Test
    public fun segmentsAreClaimedInSequenceAndOneInferenceIsOneCheckpoint() = runBlocking {
        val fixture = fixture(listOf(segment("second", 1), segment("first", 0)))
        val generated = mutableListOf<String>()

        val result = fixture.runner { segment, _ ->
            generated += segment.id
            audio(segment.id)
        }.run("run")

        assertEquals(BoundedGenerationStatus.COMPLETED, result.status)
        assertEquals(listOf("first", "second"), generated)
        assertEquals(listOf("first", "second"), result.generatedSegmentIds)
        assertEquals(1, fixture.state.segments["first"]!!.attemptCount)
        assertEquals(1, fixture.state.segments["second"]!!.attemptCount)
    }

    @Test
    public fun concurrentRunnersConditionallyClaimEachSegmentOnce() = runBlocking {
        val fixture = fixture((0 until 4).map { segment("segment-$it", it) })
        val generated = Collections.synchronizedList(mutableListOf<String>())
        (0 until 2).map {
            async {
                fixture.runner { segment, _ ->
                    generated += segment.id
                    audio(segment.id)
                }.run("run")
            }
        }.awaitAll()

        assertEquals(listOf("segment-0", "segment-1", "segment-2", "segment-3"), generated.sortedBy { it })
        assertTrue(fixture.state.segments.values.all { it.attemptCount == 1 })
    }

    @Test
    public fun pauseAndCancelAreObservedOnlyBetweenSegments() = runBlocking {
        val pauseFixture = fixture(listOf(segment("first", 0), segment("second", 1)))
        var pauseCalls = 0
        val paused = pauseFixture.runner { segment, _ ->
            pauseCalls++
            if (pauseCalls == 1) pauseFixture.state.pause()
            audio(segment.id)
        }.run("run")

        assertEquals(BoundedGenerationStatus.PAUSED, paused.status)
        assertEquals(listOf("first"), paused.generatedSegmentIds)
        assertEquals(AudioSegmentStatus.READY, pauseFixture.state.segments["first"]!!.status)
        assertEquals(AudioSegmentStatus.PENDING, pauseFixture.state.segments["second"]!!.status)

        val cancelFixture = fixture(listOf(segment("first", 0), segment("second", 1)))
        var cancelCalls = 0
        val cancelled = cancelFixture.runner { segment, _ ->
            cancelCalls++
            if (cancelCalls == 1) cancelFixture.state.cancel()
            audio(segment.id)
        }.run("run")

        assertEquals(BoundedGenerationStatus.CANCELLED, cancelled.status)
        assertEquals(1, cancelCalls)
        assertEquals(AudioSegmentStatus.READY, cancelFixture.state.segments["first"]!!.status)
        assertEquals(AudioSegmentStatus.PENDING, cancelFixture.state.segments["second"]!!.status)
    }

    @Test
    public fun successfulAudioIsValidatedPublishedAndRecordedWithProvenance() = runBlocking {
        val fixture = fixture(listOf(segment("segment", 0)))
        val result = fixture.runner { segment, _ -> audio(segment.id) }.run("run")
        val saved = fixture.state.segments.getValue("segment")

        assertEquals(BoundedGenerationStatus.COMPLETED, result.status)
        assertEquals(AudioSegmentStatus.READY, saved.status)
        assertEquals("generation-segment", saved.generationKey)
        assertEquals("model-sha", saved.modelPackageSha256)
        assertEquals("voice-sha", saved.voiceSha256)
        assertEquals(fixture.storage.readySegmentAudio("book", "chapter", "segment").path, saved.audioPath)
        assertTrue(File(saved.audioPath!!).isFile)
        assertEquals("segment", File(saved.audioPath).readText())
    }

    @Test
    public fun transientFailureIsRetriedAndSuccessfulAudioClearsTheError() = runBlocking {
        val fixture = fixture(listOf(segment("segment", 0)))
        var attempts = 0
        val first = fixture.runner { segment, _ ->
            attempts++
            if (attempts == 1) error("temporary inference failure")
            audio(segment.id)
        }.run("run")

        assertEquals(BoundedGenerationStatus.COMPLETED, first.status)
        assertEquals(AudioSegmentStatus.READY, fixture.state.segments["segment"]!!.status)
        assertEquals(2, fixture.state.segments["segment"]!!.attemptCount)
        assertEquals(null, fixture.state.segments["segment"]!!.lastError)
    }

    @Test
    public fun retryLimitPersistsTheFinalInferenceFailure() = runBlocking {
        val fixture = fixture(listOf(segment("segment", 0)))

        val result = fixture.runner(GenerationRetryPolicy(maxAttempts = 2)) { _, _ ->
            error("model unavailable")
        }.run("run")

        assertEquals(BoundedGenerationStatus.FAILED, result.status)
        assertEquals(listOf("segment"), result.failedSegmentIds)
        assertEquals(2, fixture.state.segments["segment"]!!.attemptCount)
        assertTrue(fixture.state.segments["segment"]!!.lastError!!.startsWith("INFERENCE_FAILURE:"))
    }

    @Test
    public fun writeFailureIsCategorizedAndNeverPublishesAnArtifact() = runBlocking {
        val fixture = fixture(listOf(segment("segment", 0)))

        val result = fixture.runner(GenerationRetryPolicy(maxAttempts = 1)) { _, _ ->
            audio("segment").copy(writer = { error("disk full") })
        }.run("run")

        assertEquals(BoundedGenerationStatus.FAILED, result.status)
        assertEquals("WRITE_FAILURE: disk full", fixture.state.segments["segment"]!!.lastError)
        assertFalse(fixture.storage.readySegmentAudio("book", "chapter", "segment").exists())
    }

    @Test
    public fun readySegmentIsReusedWhileOnlyPendingSegmentIsGenerated() = runBlocking {
        val ready = segment("ready", 0).copy(status = AudioSegmentStatus.READY)
        val pending = segment("pending", 1)
        val fixture = fixture(listOf(ready, pending))
        val generated = mutableListOf<String>()

        val result = fixture.runner { segment, _ ->
            generated += segment.id
            audio(segment.id)
        }.run("run")

        assertEquals(BoundedGenerationStatus.COMPLETED, result.status)
        assertEquals(listOf("pending"), generated)
        assertEquals(AudioSegmentStatus.READY, fixture.state.segments["ready"]!!.status)
    }

    @Test
    public fun coroutineCancellationReleasesClaimWithoutPublishingAudio() = runBlocking {
        val fixture = fixture(listOf(segment("segment", 0)))
        val claimed = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val job = async {
            fixture.runner { segment, _ ->
                claimed.complete(Unit)
                unblock.await()
                audio(segment.id)
            }.run("run")
        }

        claimed.await()
        job.cancel()
        unblock.complete(Unit)
        try {
            job.await()
        } catch (_: CancellationException) {
            // Expected: cancellation is not a generation failure.
        }

        assertEquals(AudioSegmentStatus.PENDING, fixture.state.segments["segment"]!!.status)
        assertFalse(fixture.storage.readyAudioDirectory.walkTopDown().any { it.isFile })
    }

    private fun fixture(segments: List<AudioSegmentEntity>): Fixture {
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val state = FakeGenerationState(segments)
        return Fixture(storage, state, AtomicArtifactStore(storage))
    }

    private fun Fixture.runner(
        retryPolicy: GenerationRetryPolicy = GenerationRetryPolicy(),
        generator: suspend (AudioSegmentEntity, NarrationBlockEntity) -> GeneratedSegmentAudio,
    ) =
        BoundedGenerationRunner(
            state = state,
            storage = storage,
            artifactStore = artifactStore,
            generator = SegmentGenerator(generator),
            retryPolicy = retryPolicy,
        )

    private fun audio(id: String) = GeneratedSegmentAudio(
        provenance = GenerationProvenance(
            generationKey = "generation-$id",
            modelPackageId = "model",
            modelPackageSha256 = "model-sha",
            voiceSha256 = "voice-sha",
            preprocessingVersion = PREPROCESSING,
            pronunciationVersion = PRONUNCIATION,
            inferenceSettingsHash = SETTINGS,
            audioProcessingVersion = AUDIO,
        ),
        sampleRateHz = 24_000,
        channels = 1,
        durationMs = 100,
        writer = { it.write(id.toByteArray()) },
        validator = { file -> require(file.readText() == id) },
    )

    private fun segment(id: String, sequence: Int) = AudioSegmentEntity(
        id = id,
        chapterId = "chapter",
        narrationBlockId = "block-$id",
        sequence = sequence,
        chunkOrdinal = 0,
        generationRunId = "run",
        createdAt = 1,
        updatedAt = 1,
    )

    private data class Fixture(
        val storage: AppPrivateStorage,
        val state: FakeGenerationState,
        val artifactStore: AtomicArtifactStore,
    )

    private class FakeGenerationState(initialSegments: List<AudioSegmentEntity>) : GenerationStateGateway {
        val segments = initialSegments.associateBy { it.id }.toMutableMap()
        private val blocks = initialSegments.associate { segment ->
            segment.narrationBlockId to NarrationBlockEntity(
                id = segment.narrationBlockId,
                chapterId = segment.chapterId,
                ordinal = segment.sequence,
                blockType = NarrationBlockType.PARAGRAPH,
                sourceText = "Text ${segment.id}",
                createdAt = 1,
                updatedAt = 1,
            )
        }
        private var run = runEntity()

        @Synchronized
        override fun findGenerationRun(runId: String): GenerationRunEntity? = run.takeIf { it.id == runId }

        @Synchronized
        override fun startGenerationRun(runId: String): GenerationRunEntity {
            if (run.status == GenerationRunStatus.QUEUED) {
                run = run.copy(status = GenerationRunStatus.RUNNING, attemptCount = run.attemptCount + 1)
            }
            return run
        }

        @Synchronized
        override fun claimNextSegment(runId: String): ClaimedGenerationSegment? {
            if (run.status != GenerationRunStatus.RUNNING) return null
            val next = segments.values
                .filter { it.status == AudioSegmentStatus.PENDING }
                .sortedWith(compareBy<AudioSegmentEntity> { it.sequence }.thenBy { it.id })
                .firstOrNull() ?: return null
            val claimed = next.copy(status = AudioSegmentStatus.GENERATING, attemptCount = next.attemptCount + 1)
            segments[next.id] = claimed
            return ClaimedGenerationSegment(claimed, blocks.getValue(next.narrationBlockId))
        }

        @Synchronized
        override fun completeAudioSegment(
            segmentId: String,
            published: PublishedArtifact,
            audio: GeneratedSegmentAudio,
        ): AudioSegmentEntity {
            val completed = segments.getValue(segmentId).copy(
                status = AudioSegmentStatus.READY,
                lastError = null,
                generationKey = audio.provenance.generationKey,
                modelPackageId = audio.provenance.modelPackageId,
                modelPackageSha256 = audio.provenance.modelPackageSha256,
                voiceSha256 = audio.provenance.voiceSha256,
                preprocessingVersion = audio.provenance.preprocessingVersion,
                pronunciationVersion = audio.provenance.pronunciationVersion,
                inferenceSettingsHash = audio.provenance.inferenceSettingsHash,
                audioProcessingVersion = audio.provenance.audioProcessingVersion,
                audioPath = published.file.path,
                audioSha256 = published.sha256,
                sizeBytes = published.sizeBytes,
                durationMs = audio.durationMs,
                sampleRate = audio.sampleRateHz,
                channels = audio.channels,
            )
            segments[segmentId] = completed
            return completed
        }

        @Synchronized
        override fun failAudioSegment(segmentId: String, error: GenerationError): AudioSegmentEntity {
            val failed = segments.getValue(segmentId).copy(status = AudioSegmentStatus.FAILED, lastError = error.record)
            segments[segmentId] = failed
            run = run.copy(status = GenerationRunStatus.RUNNING)
            return failed
        }

        @Synchronized
        override fun retryAudioSegment(segmentId: String): AudioSegmentEntity {
            val retry = segments.getValue(segmentId).copy(status = AudioSegmentStatus.PENDING)
            segments[segmentId] = retry
            return retry
        }

        @Synchronized
        override fun releaseAudioSegment(segmentId: String): AudioSegmentEntity {
            val released = segments.getValue(segmentId).copy(status = AudioSegmentStatus.PENDING)
            segments[segmentId] = released
            return released
        }

        @Synchronized
        override fun finishGenerationRun(runId: String): GenerationRunEntity {
            if (segments.values.any { it.status == AudioSegmentStatus.PENDING || it.status == AudioSegmentStatus.GENERATING }) {
                return run
            }
            run = run.copy(status = if (segments.values.any { it.status == AudioSegmentStatus.FAILED }) {
                GenerationRunStatus.FAILED
            } else {
                GenerationRunStatus.COMPLETED
            })
            return run
        }

        @Synchronized
        fun pause() {
            run = run.copy(status = GenerationRunStatus.PAUSED)
        }

        @Synchronized
        fun cancel() {
            run = run.copy(status = GenerationRunStatus.CANCELLED)
        }

    }

    private companion object {
        const val PREPROCESSING = "prep-v1"
        const val PRONUNCIATION = "pron-v1"
        const val SETTINGS = "settings"
        const val AUDIO = "audio-v1"

        fun runEntity() = GenerationRunEntity(
            id = "run",
            bookProjectId = "book",
            modelPackageId = "model",
            preprocessingVersion = PREPROCESSING,
            pronunciationVersion = PRONUNCIATION,
            inferenceSettingsHash = SETTINGS,
            audioProcessingVersion = AUDIO,
            status = GenerationRunStatus.QUEUED,
            requestedAt = 1,
        )
    }
}
