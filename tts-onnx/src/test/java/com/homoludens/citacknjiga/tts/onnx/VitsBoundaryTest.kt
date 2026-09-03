package com.homoludens.citacknjiga.tts.onnx

import com.homoludens.citacknjiga.core.generation.BoundedGenerationResult
import com.homoludens.citacknjiga.core.generation.BoundedGenerationStatus
import com.homoludens.citacknjiga.core.generation.BoundedGenerationRunner
import com.homoludens.citacknjiga.core.generation.ClaimedGenerationSegment
import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationError
import com.homoludens.citacknjiga.core.generation.GenerationStateGateway
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.core.storage.PublishedArtifact
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class VitsBoundaryTest {
    @Test
    public fun resamplerAcceptsOnlyNativeAudioAndProducesOneFinalRate() {
        val audio = VitsNativeAudio(FloatArray(4_410) { if (it % 2 == 0) 0.1f else -0.1f })
        val output = VitsAudioOutputValidator.resampleOnce(audio)
        assertEquals(4_800, output.pcm.size)
        assertEquals(24_000, output.sampleRateHz)
        assertEquals(1, output.channels)
        assertTrue(output.pcm.all { it.isFinite() })
    }

    @Test
    public fun resamplerRejectsWrongRateAndInvalidSamples() {
        assertTrue(runCatching {
            VitsAudioOutputValidator.resampleOnce(VitsNativeAudio(floatArrayOf(0.1f), 24_000, 1))
        }.isFailure)
        assertTrue(runCatching {
            VitsAudioOutputValidator.resampleOnce(VitsNativeAudio(floatArrayOf(Float.NaN)))
        }.isFailure)
    }

    @Test
    public fun frontendRejectsUnsupportedInputAndHandlesDeclaredSerbianForms() {
        val vocabulary = VitsSerbianFrontendTestVocabulary.map
        val frontend = VitsSerbianFrontend(vocabulary, blankId = 139)
        val output = frontend.process("Čao, npr.")
        assertEquals("чао, на пример", output.normalizedText)
        assertEquals(listOf(139, 42, 139, 15, 139, 32, 139, 6, 139, 14, 139,
            30, 139, 15, 139, 14, 139, 33, 139, 34, 139, 24, 139, 29, 139,
            21, 139, 34, 139), output.tokenIds)
        assertTrue(runCatching { frontend.process("Знак §") }.isFailure)
        assertTrue(runCatching { frontend.process("Чао 2") }.isFailure)
    }

    @Test
    public fun frontendNormalizesCommonBookPunctuationAndForeignDiacritics() {
        val frontend = VitsSerbianFrontend(VitsSerbianFrontendTestVocabulary.map, blankId = 139)

        val output = frontend.process("— Dobàr »Köbenhavn«…")

        assertEquals("- добар 'кобенхавн'.", output.normalizedText)
    }

    @Test
    public fun sessionClosesNativeHandleAndChecksCancellation() {
        val native = object : SherpaVitsNativeSession {
            var closed = false
            override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float) =
                VitsNativeAudio(floatArrayOf(0.1f, -0.1f))
            override fun close() { closed = true }
        }
        val session = SherpaVitsSession.fromNative(native)
        val tokenIds = intArrayOf(139, 36, 139, 21, 139, 26, 139, 35, 139)
        val cancelled = AtomicBoolean(true)
        assertTrue(runCatching { session.generate(tokenIds, 0, 1f) { cancelled.get() } }.isFailure)
        session.close()
        assertTrue(native.closed)
        session.close()
        assertFalse(runCatching { session.generate(tokenIds, 0, 1f) }.isSuccess)
    }

    @Test
    public fun segmentGeneratorPublishesStrictWavWithThePendingGenerationKey() {
        val packageInfo = packageInfo()
        val frontend = VitsSerbianFrontend(VitsSerbianFrontendTestVocabulary.map, blankId = 139)
        val generator = VitsSegmentGenerator(
            frontend = frontend,
            session = SherpaVitsSession.fromNative(object : SherpaVitsNativeSession {
                override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float) =
                    VitsNativeAudio(FloatArray(2_205) { 0.1f })

                override fun close() = Unit
            }),
            packageInfo = packageInfo,
            inferenceSettingsHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH,
        )
        val block = com.homoludens.citacknjiga.core.database.NarrationBlockEntity(
            id = "block",
            chapterId = "chapter",
            ordinal = 0,
            blockType = com.homoludens.citacknjiga.core.database.NarrationBlockType.PARAGRAPH,
            sourceText = "Čao.",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val key = VitsGenerationContract.generationKey(packageInfo, frontend.process(block.sourceText).tokenIds)
        val segment = com.homoludens.citacknjiga.core.database.AudioSegmentEntity(
            id = "segment",
            chapterId = "chapter",
            narrationBlockId = "block",
            sequence = 0,
            chunkOrdinal = 0,
            generationKey = key,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val generated = runBlocking { generator.generate(segment, block) }
        assertEquals("wav", generated.artifactExtension)
        val file = createTempDirectory().toFile().resolve("segment.wav")
        file.outputStream().use(generated.writer)
        generated.validator(file)
        assertEquals(key, generated.provenance.generationKey)
        assertTrue(file.isFile)
    }

    @Test
    public fun segmentGeneratorRejectsAnUnexpectedGenerationKey() {
        val packageInfo = packageInfo()
        val generator = VitsSegmentGenerator(
            frontend = VitsSerbianFrontend(VitsSerbianFrontendTestVocabulary.map, blankId = 139),
            session = SherpaVitsSession.fromNative(object : SherpaVitsNativeSession {
                override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float) = VitsNativeAudio(floatArrayOf(0.1f))
                override fun close() = Unit
            }),
            packageInfo = packageInfo,
            inferenceSettingsHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH,
        )
        val segment = com.homoludens.citacknjiga.core.database.AudioSegmentEntity(
            id = "segment", chapterId = "chapter", narrationBlockId = "block", sequence = 0,
            chunkOrdinal = 0, generationKey = "wrong", createdAt = 1L, updatedAt = 1L,
        )
        val block = com.homoludens.citacknjiga.core.database.NarrationBlockEntity(
            id = "block", chapterId = "chapter", ordinal = 0,
            blockType = com.homoludens.citacknjiga.core.database.NarrationBlockType.PARAGRAPH,
            sourceText = "Čao.", createdAt = 1L, updatedAt = 1L,
        )
        assertTrue(runCatching { runBlocking { generator.generate(segment, block) } }.isFailure)
    }

    @Test
    public fun vitsSegmentUsesBoundedRunnerAndAtomicPublication() = runBlocking {
        val packageInfo = packageInfo()
        val frontend = VitsSerbianFrontend(VitsSerbianFrontendTestVocabulary.map, blankId = 139)
        val block = narrationBlock()
        val key = VitsGenerationContract.generationKey(packageInfo, frontend.process(block.sourceText).tokenIds)
        val segment = AudioSegmentEntity(
            id = "segment",
            chapterId = "chapter",
            narrationBlockId = block.id,
            sequence = 0,
            chunkOrdinal = 0,
            generationKey = key,
            generationRunId = "run",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val state = SingleSegmentGenerationState(segment, block, run(packageInfo))
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val generator = VitsSegmentGenerator(
            frontend = frontend,
            session = SherpaVitsSession.fromNative(object : SherpaVitsNativeSession {
                override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float) =
                    VitsNativeAudio(FloatArray(2_205) { 0.1f })

                override fun close() = Unit
            }),
            packageInfo = packageInfo,
            inferenceSettingsHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH,
            modelPackageId = "room-package",
        )

        val result = BoundedGenerationRunner(
            state = state,
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            generator = generator,
        ).run("run")

        assertEquals(BoundedGenerationStatus.COMPLETED, result.status)
        assertEquals(listOf("segment"), result.generatedSegmentIds)
        val saved = state.segment
        assertEquals(AudioSegmentStatus.READY, saved.status)
        assertEquals(24_000, saved.sampleRate)
        assertEquals(1, saved.channels)
        assertTrue(saved.audioPath!!.endsWith("segment.wav"))
        assertEquals(2_400L, PcmWavValidator.validate(File(saved.audioPath!!)).sampleCount)
        assertTrue(storage.temporaryDirectory.walkTopDown().none { it.isFile })
        generator.close()
    }

    @Test
    public fun failedVitsGenerationLeavesExistingKokoroAudioUntouched() = runBlocking {
        val packageInfo = packageInfo()
        val frontend = VitsSerbianFrontend(VitsSerbianFrontendTestVocabulary.map, blankId = 139)
        val block = narrationBlock()
        val key = VitsGenerationContract.generationKey(packageInfo, frontend.process(block.sourceText).tokenIds)
        val segment = AudioSegmentEntity(
            id = "vits-segment",
            chapterId = "chapter",
            narrationBlockId = block.id,
            sequence = 1,
            chunkOrdinal = 0,
            generationKey = key,
            generationRunId = "run",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val state = SingleSegmentGenerationState(segment, block, run(packageInfo))
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val kokoroAudio = storage.readySegmentAudio("book", "chapter", "kokoro-segment").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("existing Kokoro audio")
        }
        val generator = VitsSegmentGenerator(
            frontend = frontend,
            session = SherpaVitsSession.fromNative(object : SherpaVitsNativeSession {
                override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float): VitsNativeAudio =
                    error("VITS session failed")

                override fun close() = Unit
            }),
            packageInfo = packageInfo,
            inferenceSettingsHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH,
        )

        val result = BoundedGenerationRunner(
            state = state,
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            generator = generator,
            retryPolicy = com.homoludens.citacknjiga.core.generation.GenerationRetryPolicy(maxAttempts = 1),
        ).run("run")

        assertEquals(BoundedGenerationStatus.FAILED, result.status)
        assertEquals(AudioSegmentStatus.FAILED, state.segment.status)
        assertEquals("existing Kokoro audio", kokoroAudio.readText())
        assertTrue(!storage.readySegmentWav("book", "chapter", "vits-segment").exists())
        generator.close()
    }

    @Test
    public fun executorClosesItsSessionAfterSuccessFailureAndCancellation() = runBlocking {
        val outcomes = listOf<Any?>(
            BoundedGenerationResult("run", BoundedGenerationStatus.COMPLETED, emptyList(), emptyList()),
            IllegalStateException("failure"),
            CancellationException("cancelled"),
        )
        outcomes.forEach { outcome ->
            var closed = false
            val packageInfo = packageInfo()
            val generator = VitsSegmentGenerator(
                frontend = VitsSerbianFrontend(VitsSerbianFrontendTestVocabulary.map, blankId = 139),
                session = SherpaVitsSession.fromNative(object : SherpaVitsNativeSession {
                    override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float) = VitsNativeAudio(floatArrayOf(0.1f))
                    override fun close() { closed = true }
                }),
                packageInfo = packageInfo,
                inferenceSettingsHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH,
            )
            val executor = VitsGenerationExecutor(
                openGenerator = { generator },
                modelPackageIdForRun = { "room-package" },
                executeRun = { _, _ ->
                    when (outcome) {
                        is Throwable -> throw outcome
                        else -> outcome as BoundedGenerationResult
                    }
                },
            )
            runCatching { executor.execute("run") }
            assertTrue(closed)
        }
    }

    @Test
    public fun unqualifiedVitsIsNotExposedAndKokoroRemainsDefault() {
        val selector = TtsEngineSelector(
            VitsModelPackageStore(createTempDirectory().toFile()),
            apiLevel = 33,
            abi = "arm64-v8a",
            runtimeAvailable = { false },
        )
        assertEquals(listOf(TtsEngine.KOKORO), selector.available())
        assertEquals(TtsEngine.KOKORO, selector.select(TtsEngine.VITS))
    }

    @Test
    public fun kokoroUsesContractFrontendWhenPackageMetadataOmitsIt() {
        assertEquals(
            KokoroGenerationContract.PREPROCESSING_VERSION,
            KokoroGenerationContract.frontendVersion(packageInfo().copy(frontendVersion = null)),
        )
    }

    private fun narrationBlock() = com.homoludens.citacknjiga.core.database.NarrationBlockEntity(
        id = "block",
        chapterId = "chapter",
        ordinal = 0,
        blockType = com.homoludens.citacknjiga.core.database.NarrationBlockType.PARAGRAPH,
        sourceText = "Čao.",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun run(packageInfo: InstalledModelPackage) = GenerationRunEntity(
        id = "run",
        bookProjectId = "book",
        modelPackageId = "room-package",
        preprocessingVersion = VitsGenerationContract.PREPROCESSING_VERSION,
        pronunciationVersion = VitsGenerationContract.PREPROCESSING_VERSION,
        inferenceSettingsHash = VitsGenerationContract.INFERENCE_SETTINGS_HASH,
        audioProcessingVersion = VitsGenerationContract.AUDIO_PROCESSING_VERSION,
        status = GenerationRunStatus.QUEUED,
        requestedAt = 1L,
        engine = packageInfo.engine,
        modelRevision = packageInfo.modelRevision,
        speakerId = packageInfo.speakerId,
        frontendVersion = packageInfo.frontendVersion,
        nativeSampleRate = packageInfo.nativeSampleRateHz,
        finalSampleRate = packageInfo.sampleRateHz,
        resamplerVersion = packageInfo.resamplerVersion,
        runtimeId = packageInfo.runtimeId,
        runtimeVersion = packageInfo.runtimeVersion,
    )
}

private class SingleSegmentGenerationState(
    initialSegment: AudioSegmentEntity,
    private val block: NarrationBlockEntity,
    private var run: GenerationRunEntity,
) : GenerationStateGateway {
    var segment: AudioSegmentEntity = initialSegment
        private set

    override fun findGenerationRun(runId: String): GenerationRunEntity? = run.takeIf { it.id == runId }

    override fun startGenerationRun(runId: String): GenerationRunEntity {
        run = run.copy(status = GenerationRunStatus.RUNNING)
        return run
    }

    override fun claimNextSegment(runId: String): ClaimedGenerationSegment? =
        if (run.status == GenerationRunStatus.RUNNING && segment.status == AudioSegmentStatus.PENDING) {
            segment = segment.copy(status = AudioSegmentStatus.GENERATING)
            ClaimedGenerationSegment(segment, block)
        } else {
            null
        }

    override fun completeAudioSegment(
        segmentId: String,
        published: PublishedArtifact,
        audio: GeneratedSegmentAudio,
    ): AudioSegmentEntity {
        segment = segment.copy(
            status = AudioSegmentStatus.READY,
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
        run = run.copy(status = GenerationRunStatus.RUNNING)
        return segment
    }

    override fun failAudioSegment(segmentId: String, error: GenerationError): AudioSegmentEntity =
        segment.copy(status = AudioSegmentStatus.FAILED, lastError = error.record).also { segment = it }

    override fun retryAudioSegment(segmentId: String): AudioSegmentEntity = segment

    override fun releaseAudioSegment(segmentId: String): AudioSegmentEntity =
        segment.copy(status = AudioSegmentStatus.PENDING).also { segment = it }

    override fun failGenerationRun(runId: String, error: GenerationError): GenerationRunEntity =
        run.copy(status = GenerationRunStatus.FAILED, lastError = error.record).also { run = it }

    override fun finishGenerationRun(runId: String): GenerationRunEntity =
        run.copy(
            status = if (segment.status == AudioSegmentStatus.FAILED) {
                GenerationRunStatus.FAILED
            } else {
                GenerationRunStatus.COMPLETED
            },
        ).also { run = it }
}

private fun packageInfo(): InstalledModelPackage = InstalledModelPackage(
    packageId = "room-package",
    packageVersion = "1.0.0",
    identitySha256 = "1".repeat(64),
    modelSha256 = "2".repeat(64),
    voiceSha256 = "3".repeat(64),
    runtimeId = "sherpa-onnx",
    runtimeVersion = "34eba5a27220026b5981b633981c53205515067d",
    preprocessingCompatibilityId = "serbian-vits-preprocessing-v1",
    engine = "vits",
    modelRevision = "83dc1e1b95d85b9f5602dc94909706fc83dfbc6c",
    speakerId = 0,
    nativeSampleRateHz = 22_050,
    frontendVersion = "serbian-vits-preprocessing-v1",
    resamplerVersion = "serbian-vits-resampler-v1",
    qualificationStatus = "PASS",
)

private object VitsSerbianFrontendTestVocabulary {
    val map: Map<Int, Int> = ("!+'(),-.:;_?/ " + "абвгдђежзијклљмнњопрстћуфхцчџш")
        .mapIndexed { index, value -> value.code to index + 1 }
        .toMap()
}
