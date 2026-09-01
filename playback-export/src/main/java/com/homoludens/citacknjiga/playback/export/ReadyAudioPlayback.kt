package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

public data class VerifiedReadyAudio(
    public val segment: AudioSegmentEntity,
    public val file: File,
)

public enum class PlaybackUnavailableReason {
    NOT_READY,
    MISSING_FILE,
    INVALID_PATH,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    FORMAT_INVALID,
    UNREADABLE,
    STALE_GENERATION_KEY,
    STALE_PROVENANCE,
}

public data class PlaybackUnavailableAudio(
    public val segment: AudioSegmentEntity,
    public val reason: PlaybackUnavailableReason,
    public val message: String,
    public val regenerationRoute: String = "generation/retry/${segment.id}",
)

/** Routes recovery to the external generation owner without changing playback or Room. */
public class PlaybackRegenerationRoute(
    private val onRequested: (String) -> Unit,
) {
    public fun request(issue: PlaybackUnavailableAudio): String {
        onRequested(issue.segment.id)
        return issue.regenerationRoute
    }
}

public data class PlaybackValidationContext(
    public val expectedGenerationKeys: Map<String, String> = emptyMap(),
    public val activeModelPackage: ModelPackageEntity? = null,
    public val modelPackages: Map<String, ModelPackageEntity> = emptyMap(),
    public val generationRuns: Map<String, GenerationRunEntity> = emptyMap(),
)

public fun interface PlaybackValidationContextSource {
    public fun current(): PlaybackValidationContext
}

public fun interface PlaybackAudioFormatValidator {
    /** Returns a stable failure reason, or null when the file is structurally readable. */
    public fun invalidReason(file: File, segment: AudioSegmentEntity): PlaybackUnavailableReason?
}

/** Lightweight JVM-safe validation; Android production adds MediaExtractor readability. */
public object DefaultPlaybackAudioFormatValidator : PlaybackAudioFormatValidator {
    override fun invalidReason(file: File, segment: AudioSegmentEntity): PlaybackUnavailableReason? =
        runCatching {
            when (file.extension.lowercase()) {
                "wav" -> validateWav(file)
                "m4a", "mp4" -> validateMp4Container(file)
                else -> PlaybackUnavailableReason.FORMAT_INVALID
            }
        }.getOrElse { PlaybackUnavailableReason.FORMAT_INVALID }

    private fun validateWav(file: File): PlaybackUnavailableReason? {
        if (file.length() < 44L) return PlaybackUnavailableReason.FORMAT_INVALID
        val header = ByteArray(44)
        file.inputStream().use { input ->
            if (input.read(header) != header.size) return PlaybackUnavailableReason.FORMAT_INVALID
        }
        if (!header.startsWith("RIFF") || !header.sliceArray(8 until 12).contentEquals("WAVE".toByteArray())) {
            return PlaybackUnavailableReason.FORMAT_INVALID
        }
        if (readShort(header, 20) != 1 || readShort(header, 22) != 1 ||
            readInt(header, 24) != 24_000 || readShort(header, 34) != 16 ||
            !header.sliceArray(36 until 40).contentEquals("data".toByteArray())
        ) {
            return PlaybackUnavailableReason.FORMAT_INVALID
        }
        val dataSize = readInt(header, 40).toLong()
        return if (dataSize > 0L && dataSize + 44L <= file.length()) null
        else PlaybackUnavailableReason.FORMAT_INVALID
    }

    private fun validateMp4Container(file: File): PlaybackUnavailableReason? {
        var hasFileType = false
        var hasMovie = false
        var hasMediaData = false
        RandomAccessFile(file, "r").use { input ->
            var offset = 0L
            while (offset + 8L <= input.length()) {
                input.seek(offset)
                val size = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(input::readFully).decodeToString()
                val boxSize = when {
                    size == 0L -> input.length() - offset
                    size >= 8L -> size
                    else -> return PlaybackUnavailableReason.FORMAT_INVALID
                }
                if (offset + boxSize > input.length()) return PlaybackUnavailableReason.FORMAT_INVALID
                when (type) {
                    "ftyp" -> {
                        hasFileType = true
                        val brands = ByteArray(minOf(boxSize - 8L, 256L).toInt())
                        input.readFully(brands)
                        val brandText = brands.decodeToString()
                        if (!(brandText.contains("M4A ") || brandText.contains("isom") || brandText.contains("mp4"))) {
                            return PlaybackUnavailableReason.FORMAT_INVALID
                        }
                    }
                    "moov" -> hasMovie = true
                    "mdat" -> hasMediaData = true
                }
                offset += boxSize
            }
        }
        return if (hasFileType && hasMovie && hasMediaData) null else PlaybackUnavailableReason.FORMAT_INVALID
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        readShort(bytes, offset) or (readShort(bytes, offset + 2) shl 16)

    private fun ByteArray.startsWith(value: String): Boolean = contentEquals(value.toByteArray())
}

/** Room snapshot used by the repository to reject stale generated audio. */
public class RoomPlaybackValidationContextSource(
    private val dao: AudiobookDao,
    private val expectedGenerationKeys: () -> Map<String, String> = { emptyMap() },
) : PlaybackValidationContextSource {
    override fun current(): PlaybackValidationContext = PlaybackValidationContext(
        expectedGenerationKeys = expectedGenerationKeys(),
        activeModelPackage = dao.findActiveModelPackage(),
        modelPackages = dao.findAllModelPackages().associateBy { it.id },
        generationRuns = dao.findAllGenerationRuns().associateBy { it.id },
    )
}

/** Android-only readability check layered on the JVM-safe container check. */
public class MediaExtractorPlaybackAudioFormatValidator(
    private val structural: PlaybackAudioFormatValidator = DefaultPlaybackAudioFormatValidator,
) : PlaybackAudioFormatValidator {
    override fun invalidReason(file: File, segment: AudioSegmentEntity): PlaybackUnavailableReason? {
        structural.invalidReason(file, segment)?.let { return it }
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount > 0) null else PlaybackUnavailableReason.UNREADABLE
        } catch (_: Throwable) {
            PlaybackUnavailableReason.UNREADABLE
        } finally {
            extractor.release()
        }
    }
}

public data class PlaybackAudioSnapshot(
    public val available: List<VerifiedReadyAudio>,
    public val unavailable: List<PlaybackUnavailableAudio>,
)

public fun interface ReadyAudioSource {
    public fun observeReadyAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>>

    public fun observeAllAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>> =
        observeReadyAudioSegments(projectId)
}

public class RoomReadyAudioSource(
    private val dao: AudiobookDao,
) : ReadyAudioSource {
    override fun observeReadyAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>> =
        dao.observeReadyAudioSegments(projectId)

    override fun observeAllAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>> =
        dao.observeAllAudioSegments().map { segments ->
            val chapterIds = dao.findAllChapters()
                .filter { it.bookProjectId == projectId }
                .map { it.id }
                .toSet()
            segments.filter { it.chapterId in chapterIds }
        }
}

public class PlaybackAvailabilityPolicy(
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore,
    private val formatValidator: PlaybackAudioFormatValidator = DefaultPlaybackAudioFormatValidator,
) {
    public fun check(
        segment: AudioSegmentEntity,
        context: PlaybackValidationContext = PlaybackValidationContext(),
    ): PlaybackUnavailableAudio? {
        fun unavailable(reason: PlaybackUnavailableReason, message: String) =
            PlaybackUnavailableAudio(segment, reason, message)
        val durationMs = segment.durationMs
        if (segment.status != AudioSegmentStatus.READY || durationMs == null || durationMs <= 0L) {
            return unavailable(PlaybackUnavailableReason.NOT_READY, "Audio is not ready for playback")
        }
        if (segment.sampleRate != SAMPLE_RATE || segment.channels != CHANNELS) {
            return unavailable(PlaybackUnavailableReason.FORMAT_INVALID, "Audio format is not 24 kHz mono")
        }
        val file = runCatching {
            File(segment.audioPath ?: return unavailable(PlaybackUnavailableReason.MISSING_FILE, "Audio file is missing"))
                .canonicalFile
        }.getOrElse { return unavailable(PlaybackUnavailableReason.INVALID_PATH, "Audio path is invalid") }
        val readyRoot = storage.readyAudioDirectory.canonicalFile.toPath()
        if (file == storage.rootDirectory || !file.toPath().startsWith(readyRoot)) {
            return unavailable(PlaybackUnavailableReason.INVALID_PATH, "Audio is outside private ready storage")
        }
        if (!file.isFile) return unavailable(PlaybackUnavailableReason.MISSING_FILE, "Audio file is missing")
        if (segment.sizeBytes == null || file.length() != segment.sizeBytes) {
            return unavailable(PlaybackUnavailableReason.SIZE_MISMATCH, "Audio file size does not match Room")
        }
        if (segment.audioSha256.isNullOrBlank() || !runCatching {
                artifactStore.sha256(file).equals(segment.audioSha256, ignoreCase = true)
            }.getOrDefault(false)
        ) {
            return unavailable(PlaybackUnavailableReason.CHECKSUM_MISMATCH, "Audio checksum does not match Room")
        }
        formatValidator.invalidReason(file, segment)?.let { reason ->
            return unavailable(
                reason,
                if (reason == PlaybackUnavailableReason.UNREADABLE) "Audio cannot be decoded" else "Audio format is invalid",
            )
        }
        val expectedKey = context.expectedGenerationKeys[segment.id]
        if (segment.generationKey.isNullOrBlank() || expectedKey != null &&
            !segment.generationKey.equals(expectedKey, ignoreCase = true)
        ) {
            return unavailable(PlaybackUnavailableReason.STALE_GENERATION_KEY, "Audio was generated from stale input")
        }
        if (segment.generationRunId.isNullOrBlank() || segment.modelPackageSha256.isNullOrBlank() ||
            segment.voiceSha256.isNullOrBlank() || segment.preprocessingVersion.isNullOrBlank() ||
            segment.pronunciationVersion.isNullOrBlank() || segment.inferenceSettingsHash.isNullOrBlank() ||
            segment.audioProcessingVersion.isNullOrBlank()
        ) {
            return unavailable(PlaybackUnavailableReason.STALE_PROVENANCE, "Audio provenance is incomplete")
        }
        val activeModel = segment.modelPackageId?.let(context.modelPackages::get)
            ?: context.activeModelPackage
        if (activeModel != null && (
                segment.modelPackageId != activeModel.id ||
                    !segment.modelPackageSha256.equals(activeModel.packageSha256, ignoreCase = true) ||
                    !segment.voiceSha256.equals(activeModel.voiceSha256, ignoreCase = true) ||
                    segment.preprocessingVersion != activeModel.preprocessingVersion ||
                    segment.pronunciationVersion != activeModel.pronunciationVersion
            )
        ) {
            return unavailable(PlaybackUnavailableReason.STALE_PROVENANCE, "Audio provenance is stale")
        }
        val run = segment.generationRunId?.let(context.generationRuns::get)
        if (context.generationRuns.isNotEmpty() && (
                run == null ||
                    run.modelPackageId != segment.modelPackageId ||
                    run.inferenceSettingsHash != segment.inferenceSettingsHash ||
                    run.audioProcessingVersion != segment.audioProcessingVersion ||
                    run.preprocessingVersion != segment.preprocessingVersion ||
                    run.pronunciationVersion != segment.pronunciationVersion
            )
        ) {
            return unavailable(PlaybackUnavailableReason.STALE_PROVENANCE, "Audio provenance is stale")
        }
        return null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val CHANNELS = 1
    }
}

/** Filters Room rows to files that are still verified and private. It never changes Room state. */
public class ReadyAudioRepository(
    private val source: ReadyAudioSource,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore = AtomicArtifactStore(storage),
    private val formatValidator: PlaybackAudioFormatValidator = DefaultPlaybackAudioFormatValidator,
    private val validationContext: PlaybackValidationContextSource = PlaybackValidationContextSource { PlaybackValidationContext() },
    private val validationDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    public fun observe(projectId: String): Flow<PlaybackAudioSnapshot> =
        source.observeAllAudioSegments(projectId).map { segments ->
            val context = validationContext.current()
            val available = mutableListOf<VerifiedReadyAudio>()
            val unavailable = mutableListOf<PlaybackUnavailableAudio>()
            val policy = PlaybackAvailabilityPolicy(storage, artifactStore, formatValidator)
            segments.forEach { segment ->
                val result = policy.check(segment, context)
                if (result == null) {
                    available += VerifiedReadyAudio(segment, File(segment.audioPath!!).canonicalFile)
                } else {
                    unavailable += result
                }
            }
            PlaybackAudioSnapshot(available, unavailable)
        }.flowOn(validationDispatcher)

    public fun observeVerified(projectId: String): Flow<List<VerifiedReadyAudio>> =
        observe(projectId).map { it.available }
}
