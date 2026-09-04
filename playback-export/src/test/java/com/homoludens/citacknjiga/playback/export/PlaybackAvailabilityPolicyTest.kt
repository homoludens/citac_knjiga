package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.ModelPackageEntity
import com.homoludens.citacknjiga.core.database.ModelPackageStatus
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class PlaybackAvailabilityPolicyTest {
    @Test
    public fun missingNotReadyAndCorruptAudioHaveStableReasons() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val artifact = store.publish("test", storage.readySegmentAudio("book", "chapter", "ready"), { output ->
            output.write(validM4a())
        })
        val policy = PlaybackAvailabilityPolicy(storage, store)

        assertEquals(
            PlaybackUnavailableReason.MISSING_FILE,
            policy.check(segment("missing", audioPath = storage.readySegmentAudio("book", "chapter", "missing").path))?.reason,
        )
        assertEquals(
            PlaybackUnavailableReason.NOT_READY,
            policy.check(segment("pending", status = AudioSegmentStatus.PENDING, audioPath = artifact.file.path))?.reason,
        )

        RandomAccessFileByte.flip(artifact.file)
        assertEquals(
            PlaybackUnavailableReason.CHECKSUM_MISMATCH,
            policy.check(segment("corrupt", audioPath = artifact.file.path, sha256 = artifact.sha256))?.reason,
        )
        root.deleteRecursively()
    }

    @Test
    public fun staleKeyAndProvenanceAreRejectedBeforePlayback() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val artifact = store.publish("test", storage.readySegmentAudio("book", "chapter", "ready"), { it.write(validM4a()) })
        val policy = PlaybackAvailabilityPolicy(storage, store)
        val ready = segment("ready", audioPath = artifact.file.path, sha256 = artifact.sha256)

        assertEquals(
            PlaybackUnavailableReason.STALE_GENERATION_KEY,
            policy.check(ready, PlaybackValidationContext(expectedGenerationKeys = mapOf("ready" to "new-key")))?.reason,
        )
        assertEquals(
            PlaybackUnavailableReason.STALE_PROVENANCE,
            policy.check(ready, PlaybackValidationContext(activeModelPackage = activeModel("other-model")))?.reason,
        )
        root.deleteRecursively()
    }

    @Test
    public fun validContainerPassesAndMalformedContainerFails() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val valid = store.publish("valid", storage.readySegmentAudio("book", "chapter", "valid"), { it.write(validM4a()) })
        val malformed = store.publish("bad", storage.readySegmentAudio("book", "chapter", "bad"), { it.write("not m4a".toByteArray()) })
        val policy = PlaybackAvailabilityPolicy(storage, store)

        assertNull(policy.check(segment("valid", audioPath = valid.file.path, sha256 = valid.sha256)))
        assertEquals(
            PlaybackUnavailableReason.FORMAT_INVALID,
            policy.check(segment("bad", audioPath = malformed.file.path, sha256 = malformed.sha256))?.reason,
        )
        val unreadablePolicy = PlaybackAvailabilityPolicy(
            storage,
            store,
            PlaybackAudioFormatValidator { _, _ -> PlaybackUnavailableReason.UNREADABLE },
        )
        assertEquals(
            PlaybackUnavailableReason.UNREADABLE,
            unreadablePolicy.check(segment("valid", audioPath = valid.file.path, sha256 = valid.sha256))?.reason,
        )
        root.deleteRecursively()
    }

    @Test
    public fun validPcmWavPassesFormatValidation() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val wav = store.publish(
            "wav",
            storage.readySegmentWav("book", "chapter", "wav"),
            { it.write(validWav()) },
        )

        assertNull(
            PlaybackAvailabilityPolicy(storage, store).check(
                segment("wav", audioPath = wav.file.path, sha256 = wav.sha256),
            ),
        )
        root.deleteRecursively()
    }

    private fun segment(
        id: String,
        status: AudioSegmentStatus = AudioSegmentStatus.READY,
        audioPath: String,
        sha256: String? = "a".repeat(64),
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
        audioPath = audioPath,
        audioSha256 = sha256,
        sizeBytes = File(audioPath).length(),
        durationMs = 100L,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun activeModel(id: String) = ModelPackageEntity(
        id = id,
        packageIdentity = id,
        packageVersion = "1",
        packageSha256 = "b".repeat(64),
        modelSha256 = "d".repeat(64),
        voiceSha256 = "c".repeat(64),
        preprocessingVersion = "preprocessing-v1",
        pronunciationVersion = "pronunciation-v1",
        packagePath = "/model.zip",
        status = ModelPackageStatus.ACTIVE,
        importedAt = 1L,
    )

    private fun validM4a(): ByteArray = box("ftyp", "M4A ".toByteArray() + ByteArray(12)) +
        box("moov", ByteArray(1)) + box("mdat", byteArrayOf(1))

    private fun validWav(): ByteArray = ByteBuffer.allocate(46)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(38).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(2).putShort(1)
        .array()

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + payload.size)
            .put(type.toByteArray())
            .put(payload)
            .array()

    private object RandomAccessFileByte {
        fun flip(file: File) {
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
        }
    }
}
