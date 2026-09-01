package com.homoludens.citacknjiga.proof

import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.generation.GenerationKeyCalculator
import com.homoludens.citacknjiga.core.generation.GenerationKeyInput
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.document.epub.EpubAcceptanceResult
import com.homoludens.citacknjiga.document.epub.EpubChapter
import com.homoludens.citacknjiga.tts.onnx.WavArtifact
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public data class EpubChapterGenerationResult(
    public val chapter: EpubChapter,
    public val diagnostics: TypedTextProofDiagnostics,
    public val audio: WavArtifact,
    public val segment: AudioSegmentEntity,
)

/** One-shot task-7.9 proof coordinator; it is deliberately not a durable worker. */
public class EpubChapterProofService(
    private val dao: AudiobookDao,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val proofEngine: TypedTextProofEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    public suspend fun generate(
        accepted: EpubAcceptanceResult.Published,
        chapterOrdinal: Int,
    ): EpubChapterGenerationResult = withContext(ioDispatcher) {
        val preview = accepted.preview
        val documentChapter = preview.document.chapters.getOrNull(chapterOrdinal)
            ?: throw IllegalArgumentException("The selected EPUB chapter does not exist")
        val text = documentChapter.blocks
            .filter { it.sourceText.isNotBlank() && it.type != NarrationBlockType.SKIPPED }
            .joinToString("\n\n") { it.sourceText }
            .trim()
        require(text.isNotEmpty()) { "The selected EPUB chapter has no narratable text" }
        require(dao.findAllAudioSegments().none { it.chapterId == documentChapter.id }) {
            "The selected EPUB chapter already has generation work or audio"
        }

        val now = clock()
        val projection = preview.document.toRoomProjection(accepted.source, now)
        val project = projection.project.copy(status = BookProjectStatus.READY, updatedAt = now)
        dao.updateProject(project)
        projection.chapters.forEach { chapter ->
            dao.insertChapter(
                chapter.copy(
                    canonicalMarkdownPath = storage.canonicalChapterText(project.id, chapter.id).path,
                ),
            )
        }
        projection.narrationBlocks.forEach(dao::insertNarrationBlock)

        val chapter = projection.chapters[chapterOrdinal].copy(
            canonicalMarkdownPath = storage.canonicalChapterText(project.id, projection.chapters[chapterOrdinal].id).path,
        )
        val block = projection.narrationBlocks
            .filter { it.chapterId == chapter.id && it.sourceText.isNotBlank() && it.blockType != NarrationBlockType.SKIPPED }
            .firstOrNull()
            ?: throw IllegalArgumentException("The selected EPUB chapter has no narratable block")
        val runId = "${project.id}-task-7-9-${UUID.randomUUID()}"
        val inferenceSettingsHash = sha256("provider=cpu;intra_op=1;inter_op=1;speed=1.0")
        val queued = GenerationRunEntity(
            id = runId,
            bookProjectId = project.id,
            preprocessingVersion = PREPROCESSING_VERSION,
            pronunciationVersion = PRONUNCIATION_VERSION,
            inferenceSettingsHash = inferenceSettingsHash,
            audioProcessingVersion = AUDIO_PROCESSING_VERSION,
            requestedAt = now,
        )
        dao.insertGenerationRun(queued)
        var running = queued.copy(status = GenerationRunStatus.RUNNING, startedAt = clock())
        dao.updateGenerationRun(running)

        try {
            val proof = proofEngine.generate(text) {}
            val model = proof.diagnostics.model
            val preprocessingVersion = model.engine.takeIf { it == "vits" }
                ?.let { model.preprocessing }
                ?: PREPROCESSING_VERSION
            running = running.copy(
                preprocessingVersion = preprocessingVersion,
                pronunciationVersion = preprocessingVersion,
                engine = model.engine,
                modelRevision = model.modelRevision,
                speakerId = model.speakerId,
                frontendVersion = model.frontendVersion,
                nativeSampleRate = model.nativeSampleRateHz,
                finalSampleRate = model.finalSampleRateHz,
                runtimeId = model.runtimeId,
                runtimeVersion = model.runtimeVersion,
            )
            dao.updateGenerationRun(running)
            val keys = GenerationKeyCalculator.calculate(
                GenerationKeyInput(
                    tokens = proof.diagnostics.tokenIds,
                    modelSha256 = model.packageSha256,
                    voiceSha256 = model.voiceSha256,
                    preprocessingVersion = preprocessingVersion,
                    pronunciationVersion = preprocessingVersion,
                    inferenceSettings = mapOf(
                        "execution_provider" to "cpu",
                        "inter_op_threads" to "1",
                        "intra_op_threads" to "1",
                        "speed" to "1.0",
                    ),
                    audioProcessingVersion = AUDIO_PROCESSING_VERSION,
                    engine = model.engine,
                    modelRevision = model.modelRevision,
                    speakerId = model.speakerId,
                    frontendVersion = model.frontendVersion,
                    nativeSampleRateHz = model.nativeSampleRateHz,
                    finalSampleRateHz = model.finalSampleRateHz,
                    resamplerVersion = model.resamplerVersion,
                    runtimeId = model.runtimeId,
                    runtimeVersion = model.runtimeVersion,
                ),
            )
            val destination = storage.readyChapterWav(project.id, chapter.id)
            val published = artifactStore.publish(
                ownerId = "task-7-9-${project.id}-${chapter.id}",
                destination = destination,
                writer = { output -> proof.wav.file.inputStream().use { it.copyTo(output) } },
                validator = { file -> validateWav(file, proof.wav.sampleCount) },
            )
            val segment = AudioSegmentEntity(
                id = "${chapter.id}-task-7-9",
                chapterId = chapter.id,
                narrationBlockId = block.id,
                sequence = 0,
                chunkOrdinal = 0,
                generationKey = keys.generationKey,
                generationRunId = runId,
                modelPackageId = model.packageId,
                modelPackageSha256 = model.packageSha256,
                voiceSha256 = model.voiceSha256,
                preprocessingVersion = preprocessingVersion,
                pronunciationVersion = preprocessingVersion,
                inferenceSettingsHash = inferenceSettingsHash,
                audioProcessingVersion = AUDIO_PROCESSING_VERSION,
                status = AudioSegmentStatus.READY,
                audioPath = published.file.path,
                audioSha256 = published.sha256,
                sizeBytes = published.sizeBytes,
                durationMs = proof.wav.sampleCount * 1_000L / proof.wav.sampleRateHz,
                createdAt = now,
                updatedAt = clock(),
                engine = model.engine,
                modelRevision = model.modelRevision,
                speakerId = model.speakerId,
                frontendVersion = model.frontendVersion,
                nativeSampleRate = model.nativeSampleRateHz,
                finalSampleRate = model.finalSampleRateHz,
                resamplerVersion = model.resamplerVersion,
                runtimeId = model.runtimeId,
                runtimeVersion = model.runtimeVersion,
            )
            dao.insertAudioSegment(segment)
            dao.updateNarrationBlock(
                block.copy(
                    normalizedText = proof.diagnostics.normalizedText,
                    normalizedTextHash = sha256(proof.diagnostics.normalizedText),
                    phonemeHash = sha256(proof.diagnostics.phonemes),
                    tokenHash = sha256(proof.diagnostics.tokenIds.joinToString(",")),
                    preprocessingVersion = preprocessingVersion,
                    pronunciationVersion = preprocessingVersion,
                    status = NarrationBlockStatus.PROCESSED,
                    updatedAt = clock(),
                ),
            )
            dao.updateChapter(chapter.copy(status = ChapterStatus.READY, updatedAt = clock()))
            dao.updateGenerationRun(
                running.copy(
                    status = GenerationRunStatus.COMPLETED,
                    startedAt = now,
                    finishedAt = clock(),
                ),
            )
            EpubChapterGenerationResult(
                chapter = documentChapter,
                diagnostics = proof.diagnostics,
                audio = WavArtifact(destination, proof.wav.sampleCount, proof.wav.sampleRateHz),
                segment = segment,
            )
        } catch (failure: Throwable) {
            runCatching {
                dao.updateGenerationRun(
                    running.copy(
                        status = GenerationRunStatus.FAILED,
                        startedAt = now,
                        finishedAt = clock(),
                        lastError = failure.message,
                    ),
                )
                dao.updateChapter(chapter.copy(status = ChapterStatus.FAILED, lastError = failure.message, updatedAt = clock()))
            }
            throw failure
        }
    }

    private fun validateWav(file: File, expectedSamples: Int) {
        require(file.length() == 44L + expectedSamples * 2L) { "Published WAV size is invalid" }
        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            input.readFully(header)
            require(header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) { "Published artifact is not RIFF" }
            require(header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) { "Published artifact is not WAVE" }
            require(readShort(header, 20) == 1 && readShort(header, 22) == 1) { "Published WAV is not mono PCM" }
            require(readInt(header, 24) == 24_000 && readShort(header, 34) == 16) {
                "Published WAV is not 24 kHz PCM16"
            }
            require(readInt(header, 40) == expectedSamples * 2) { "Published WAV sample count is invalid" }
        }
    }

    private fun FileInputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            require(count > 0) { "Published WAV header is truncated" }
            offset += count
        }
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        readShort(bytes, offset) or (readShort(bytes, offset + 2) shl 16)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val PREPROCESSING_VERSION = "kokoro-sr-ca5590d9/contract-1"
        const val PRONUNCIATION_VERSION = "espeak-ng-1.52.0-sr"
        const val AUDIO_PROCESSING_VERSION = "pcm16-wav-v1"
    }
}
