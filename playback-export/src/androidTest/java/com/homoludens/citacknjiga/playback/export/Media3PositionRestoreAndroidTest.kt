package com.homoludens.citacknjiga.playback.export

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a real ExoPlayer port and a file-backed Room database across adapter recreation. */
public class Media3PositionRestoreAndroidTest {
    @Test
    public fun media3PositionAndSpeedSurviveRoomAndPlayerRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "media3-position-${UUID.randomUUID()}.db"
        val storage = AppPrivateStorage(File(context.cacheDir, "media3-position-${UUID.randomUUID()}"))
        var database = AudiobookDatabase.create(context, databaseName)
        try {
            val source = storage.readySegmentWav("book", "chapter", "segment").apply {
                parentFile!!.mkdirs()
            }
            val artifact = AtomicArtifactStore(storage).publish(
                ownerId = "media3-position",
                destination = source,
                writer = { it.write(wavBytes()) },
            )
            seed(database, artifact.file)
            val catalog = PlaybackCatalog(
                listOf(PlaybackChapter("chapter", "Chapter", 0, listOf("segment"), 0, true)),
                listOf("segment"),
            )

            val firstPlayer = newPlayer(context, artifact.file)
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val firstPersistence = PlaybackPositionPersistence(database.audiobookDao(), firstScope)
            val firstPort = MainThreadPlayerPort(firstPlayer)
            try {
                awaitPrepared(firstPlayer)
                runOnMain {
                    firstPlayer.setPlaybackParameters(PlaybackParameters(1.5f))
                    firstPlayer.seekTo(1_500L)
                    firstPlayer.pause()
                }
                firstPersistence.attach("book", catalog, firstPort)
                firstPersistence.flush()
            } finally {
                firstPersistence.close()
                firstScope.cancel()
                runOnMain { firstPlayer.release() }
            }

            database.close()
            database = AudiobookDatabase.create(context, databaseName)
            val secondPlayer = newPlayer(context, artifact.file)
            val secondPort = MainThreadPlayerPort(secondPlayer)
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val secondPersistence = PlaybackPositionPersistence(database.audiobookDao(), secondScope)
            try {
                awaitPrepared(secondPlayer)
                val restored = secondPersistence.restore("book", catalog, secondPort)
                assertEquals("chapter", restored?.chapterId)
                assertEquals("segment", restored?.audioSegmentId)
                assertEquals(1_500L, restored?.positionMs)
                assertEquals(1.5f, restored?.speed)
                assertEquals(1_500L, mainValue { secondPlayer.currentPosition })
                assertEquals(1.5f, mainValue { secondPlayer.playbackParameters.speed })
            } finally {
                secondPersistence.close()
                secondScope.cancel()
                secondPort.close()
                runOnMain { secondPlayer.release() }
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            storage.rootDirectory.deleteRecursively()
        }
    }

    private fun seed(database: AudiobookDatabase, file: File) {
        val dao = database.audiobookDao()
        dao.insertProject(BookProjectEntity("book", "Book", "Author", "content://book", "fingerprint", status = BookProjectStatus.READY, createdAt = 1L, updatedAt = 1L))
        dao.insertChapter(ChapterEntity("chapter", "book", 0, "Chapter", status = ChapterStatus.READY, createdAt = 1L, updatedAt = 1L))
        dao.insertNarrationBlock(NarrationBlockEntity("block", "chapter", 0, NarrationBlockType.PARAGRAPH, "Tekst", status = NarrationBlockStatus.PROCESSED, createdAt = 1L, updatedAt = 1L))
        dao.insertAudioSegment(
            AudioSegmentEntity(
                id = "segment",
                chapterId = "chapter",
                narrationBlockId = "block",
                sequence = 0,
                chunkOrdinal = 0,
                status = AudioSegmentStatus.READY,
                audioPath = file.path,
                sizeBytes = file.length(),
                durationMs = 4_000L,
                sampleRate = 24_000,
                channels = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun newPlayer(context: Context, file: File): ExoPlayer {
        lateinit var player: ExoPlayer
        runOnMain {
            player = ExoPlayer.Builder(context).build()
            player.setMediaItem(
                MediaItem.Builder()
                    .setMediaId("segment")
                    .setUri(file.toURI().toString())
                    .setMimeType(MimeTypes.AUDIO_WAV)
                    .build(),
            )
            player.prepare()
        }
        return player
    }

    private suspend fun awaitPrepared(player: ExoPlayer) {
        withTimeout(5_000L) {
            while (mainValue { player.duration } < 0L) delay(20L)
        }
        assertTrue(mainValue { player.duration } >= 3_900L)
    }

    private fun wavBytes(): ByteArray {
        val samples = 24_000 * 4
        val dataSize = samples * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(dataSize)
            repeat(samples) { putShort(if (it % 2 == 0) 2_000 else -2_000) }
        }.array()
    }

    private fun runOnMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }

    private fun <T> mainValue(value: () -> T): T {
        var result: T? = null
        runOnMain { result = value() }
        return checkNotNull(result)
    }

    private inner class MainThreadPlayerPort(player: ExoPlayer) : PlaybackPlayerPort {
        private val delegate = Media3PlayerPort(player)

        override val isPlaying: Boolean get() = onMain { delegate.isPlaying }
        override val positionMs: Long get() = onMain { delegate.positionMs }
        override val durationMs: Long? get() = onMain { delegate.durationMs }
        override val currentMediaItemId: String? get() = onMain { delegate.currentMediaItemId }
        override val currentMediaItemIndex: Int get() = onMain { delegate.currentMediaItemIndex }
        override val mediaItemCount: Int get() = onMain { delegate.mediaItemCount }
        override val speed: Float get() = onMain { delegate.speed }

        override fun play() = onMain { delegate.play() }
        override fun pause() = onMain { delegate.pause() }
        override fun seekTo(positionMs: Long) = onMain { delegate.seekTo(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) =
            onMain { delegate.seekTo(mediaItemIndex, positionMs) }
        override fun setSpeed(speed: Float) = onMain { delegate.setSpeed(speed) }
        override fun addListener(listener: () -> Unit) = onMain { delegate.addListener(listener) }
        override fun removeListener(listener: () -> Unit) = onMain { delegate.removeListener(listener) }
        fun close() = onMain { delegate.close() }

        private fun <T> onMain(action: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return action()
            val result = arrayOfNulls<Any>(1)
            runOnMain { result[0] = action() }
            @Suppress("UNCHECKED_CAST")
            return result[0] as T
        }
    }
}
