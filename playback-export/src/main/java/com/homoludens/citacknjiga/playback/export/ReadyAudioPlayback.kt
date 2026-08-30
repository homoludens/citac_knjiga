package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

public data class VerifiedReadyAudio(
    public val segment: AudioSegmentEntity,
    public val file: File,
)

public fun interface ReadyAudioSource {
    public fun observeReadyAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>>
}

public class RoomReadyAudioSource(
    private val dao: AudiobookDao,
) : ReadyAudioSource {
    override fun observeReadyAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>> =
        dao.observeReadyAudioSegments(projectId)
}

/** Filters Room rows to files that are still verified and private. It never changes Room state. */
public class ReadyAudioRepository(
    private val source: ReadyAudioSource,
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore = AtomicArtifactStore(storage),
) {
    public fun observeVerified(projectId: String): Flow<List<VerifiedReadyAudio>> =
        source.observeReadyAudioSegments(projectId).map { segments ->
            segments.mapNotNull(::verify)
        }

    private fun verify(segment: AudioSegmentEntity): VerifiedReadyAudio? {
        val durationMs = segment.durationMs ?: return null
        if (segment.status != AudioSegmentStatus.READY ||
            segment.sampleRate != SAMPLE_RATE ||
            segment.channels != CHANNELS ||
            durationMs <= 0 ||
            segment.sizeBytes == null || segment.audioSha256.isNullOrBlank()
        ) {
            return null
        }
        val file = runCatching { File(segment.audioPath ?: return null).canonicalFile }.getOrNull() ?: return null
        val readyRoot = storage.readyAudioDirectory.canonicalFile.toPath()
        if (file == storage.rootDirectory || !file.toPath().startsWith(readyRoot) || !file.isFile) return null
        if (file.length() != segment.sizeBytes) return null
        val checksumMatches = runCatching {
            artifactStore.sha256(file).equals(segment.audioSha256, ignoreCase = true)
        }.getOrDefault(false)
        return file.takeIf { checksumMatches }?.let { VerifiedReadyAudio(segment, it) }
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val CHANNELS = 1
    }
}
