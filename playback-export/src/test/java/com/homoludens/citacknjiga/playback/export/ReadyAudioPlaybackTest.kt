package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class ReadyAudioPlaybackTest {
    @Test
    public fun repositoryEmitsOnlyVerifiedReadyPrivateAudio() = runBlocking {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val valid = store.publish(
            ownerId = "test",
            destination = storage.readySegmentAudio("book", "chapter", "valid"),
            writer = { it.write("verified wav bytes".toByteArray()) },
        )
        val outside = File(root, "outside.wav").apply { writeText("also bytes") }
        val source = FakeReadyAudioSource(
            listOf(
                segment("valid", AudioSegmentStatus.READY, valid.file, valid.sha256, valid.sizeBytes),
                segment("pending", AudioSegmentStatus.PENDING, valid.file, valid.sha256, valid.sizeBytes),
                segment("checksum", AudioSegmentStatus.READY, valid.file, "0".repeat(64), valid.sizeBytes),
                segment("outside", AudioSegmentStatus.READY, outside, store.sha256(outside), outside.length()),
            ),
        )

        val result = ReadyAudioRepository(
            source,
            storage,
            store,
            formatValidator = PlaybackAudioFormatValidator { _, _ -> null },
        ).observeVerified("book").first()

        assertEquals(listOf("valid"), result.map { it.segment.id })
        assertTrue(result.single().file.isFile)
        assertFalse(source.mutatedState)
        root.deleteRecursively()
        Unit
    }

    private fun segment(
        id: String,
        status: AudioSegmentStatus,
        file: File,
        sha256: String,
        sizeBytes: Long,
    ) = AudioSegmentEntity(
        id = id,
        chapterId = "chapter",
        narrationBlockId = "block-$id",
        sequence = 0,
        chunkOrdinal = 0,
        generationKey = "generation-key",
        generationRunId = "run",
        modelPackageId = "model",
        modelPackageSha256 = "b".repeat(64),
        voiceSha256 = "c".repeat(64),
        preprocessingVersion = "preprocessing-v1",
        pronunciationVersion = "pronunciation-v1",
        inferenceSettingsHash = "settings",
        audioProcessingVersion = "audio-v1",
        status = status,
        audioPath = file.path,
        audioSha256 = sha256,
        sizeBytes = sizeBytes,
        durationMs = 100,
        createdAt = 1,
        updatedAt = 1,
    )

    private class FakeReadyAudioSource(
        private val segments: List<AudioSegmentEntity>,
    ) : ReadyAudioSource {
        var mutatedState: Boolean = false

        override fun observeReadyAudioSegments(projectId: String): Flow<List<AudioSegmentEntity>> = flowOf(segments)
    }
}
