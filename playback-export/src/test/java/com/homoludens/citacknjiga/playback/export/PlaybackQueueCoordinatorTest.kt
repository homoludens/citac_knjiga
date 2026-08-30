package com.homoludens.citacknjiga.playback.export

import androidx.media3.common.MediaItem
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class PlaybackQueueCoordinatorTest {
    @Test
    public fun catalogOrdersAndDeduplicatesReadyItemsDeterministically() {
        val catalog = PlaybackCatalog.from(
            chapters = listOf(chapter("later", 1), chapter("first", 0)),
            readyAudio = listOf(audio("later-segment", "later", 0), audio("duplicate", "first", 1), audio("duplicate", "first", 1), audio("first-segment", "first", 0)),
        )

        assertEquals(listOf("first-segment", "duplicate", "later-segment"), catalog.mediaItemIds)
        assertEquals(listOf("first", "later"), catalog.chapters.filter { it.available }.map { it.id })
    }

    @Test
    public fun observesNewReadyItemsAndKeepsActiveItemPosition() {
        val source = MutableStateFlow<List<com.homoludens.citacknjiga.core.database.AudioSegmentEntity>>(emptyList())
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val firstArtifact = store.publish(
            ownerId = "first",
            destination = storage.readySegmentAudio("book", "chapter", "first"),
            writer = { it.write(byteArrayOf(1)) },
        )
        val newArtifact = store.publish(
            ownerId = "new",
            destination = storage.readySegmentAudio("book", "chapter", "new"),
            writer = { it.write(byteArrayOf(2)) },
        )
        val player = FakeQueuePlayer()
        val coordinator = PlaybackQueueCoordinator(
            readyAudio = ReadyAudioRepository(
                source = object : ReadyAudioSource {
                    override fun observeReadyAudioSegments(projectId: String) = source
                },
                storage = storage,
                artifactStore = store,
            ),
            player = player,
            scope = CoroutineScope(Dispatchers.Unconfined),
            mediaItemFactory = ::mediaItem,
        )
        coordinator.start("book", listOf(chapter("chapter", 0)))

        try {
            source.value = listOf(segment("first", 0, firstArtifact))
            player.positionMs = 420L
            player.isPlaying = true
            source.value = listOf(segment("first", 0, firstArtifact), segment("new", 1, newArtifact))

            assertEquals(listOf("first", "new"), player.items.map { it.mediaId })
            assertEquals("first", player.currentMediaItemId)
            assertEquals(420L, player.positionMs)
            assertTrue(player.replacements.last().resumePlayback)
        } finally {
            coordinator.close()
            root.deleteRecursively()
        }
    }

    @Test
    public fun defersRemovalOfPlayingItemUntilPlaybackStops() {
        val player = FakeQueuePlayer()
        val coordinator = coordinator(player)
        coordinator.update(listOf(audio("first", "chapter", 0), audio("second", "chapter", 1)))
        player.positionMs = 900L
        player.isPlaying = true

        coordinator.update(listOf(audio("second", "chapter", 1)))
        assertEquals(listOf("first", "second"), player.items.map { it.mediaId })
        assertEquals(1, player.replacements.size)

        player.isPlaying = false
        player.emitEvent()

        assertEquals(listOf("second"), player.items.map { it.mediaId })
        assertEquals(0L, player.positionMs)
        coordinator.close()
    }

    @Test
    public fun reorderingItemsPreservesTheActiveItemAndPosition() {
        val player = FakeQueuePlayer()
        val coordinator = coordinator(player)
        coordinator.update(listOf(audio("active", "chapter", 1), audio("after", "chapter", 2)))
        player.select("active")
        player.positionMs = 700L
        player.isPlaying = true

        coordinator.update(
            listOf(audio("before", "chapter", 0), audio("active", "chapter", 1), audio("after", "chapter", 2)),
        )

        assertEquals(listOf("before", "active", "after"), player.items.map { it.mediaId })
        assertEquals("active", player.currentMediaItemId)
        assertEquals(700L, player.positionMs)
        assertTrue(player.replacements.last().resumePlayback)
        coordinator.close()
    }

    private fun coordinator(player: FakeQueuePlayer) = PlaybackQueueCoordinator(
        readyAudio = testReadyRepository(),
        player = player,
        scope = CoroutineScope(Dispatchers.Unconfined),
        mediaItemFactory = ::mediaItem,
    ).also {
        it.start("book", listOf(chapter("chapter", 0)))
        player.replacements.clear()
    }

    private fun mediaItem(audio: VerifiedReadyAudio) = MediaItem.Builder()
        .setMediaId(audio.segment.id)
        .build()

    private fun chapter(id: String, ordinal: Int) = ChapterEntity(
        id = id,
        bookProjectId = "book",
        ordinal = ordinal,
        title = id,
        status = ChapterStatus.READY,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun audio(id: String, chapterId: String, sequence: Int) = VerifiedReadyAudio(
        segment = segment(id, sequence, chapterId),
        file = File("$id.wav"),
    )

    private fun segment(id: String, sequence: Int, chapterId: String = "chapter") = AudioSegmentEntity(
        id = id,
        chapterId = chapterId,
        narrationBlockId = "block-$id",
        sequence = sequence,
        chunkOrdinal = 0,
        status = com.homoludens.citacknjiga.core.database.AudioSegmentStatus.READY,
        audioPath = "/ready/$id.wav",
        audioSha256 = "a".repeat(64),
        sizeBytes = 1L,
        durationMs = 1L,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun segment(id: String, sequence: Int, artifact: com.homoludens.citacknjiga.core.storage.PublishedArtifact) =
        AudioSegmentEntity(
            id = id,
            chapterId = "chapter",
            narrationBlockId = "block-$id",
            sequence = sequence,
            chunkOrdinal = 0,
            status = com.homoludens.citacknjiga.core.database.AudioSegmentStatus.READY,
            audioPath = artifact.file.path,
            audioSha256 = artifact.sha256,
            sizeBytes = artifact.sizeBytes,
            durationMs = 1L,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun testReadyRepository(): ReadyAudioRepository = ReadyAudioRepository(
        source = object : ReadyAudioSource {
            override fun observeReadyAudioSegments(projectId: String) = flowOf(emptyList<AudioSegmentEntity>())
        },
        storage = AppPrivateStorage(File("/tmp")),
    )

    private class FakeQueuePlayer : PlaybackQueuePlayerPort {
        override var isPlaying: Boolean = false
        var positionMs: Long = 0L
        override var currentMediaItemId: String? = null
            private set
        var items: List<MediaItem> = emptyList()
            private set
        val replacements = mutableListOf<Replacement>()
        private val listeners = mutableListOf<() -> Unit>()

        override val currentPositionMs: Long get() = positionMs

        override fun replaceQueue(items: List<MediaItem>, currentItemIndex: Int, positionMs: Long, resumePlayback: Boolean) {
            this.items = items
            this.positionMs = positionMs
            currentMediaItemId = items.getOrNull(currentItemIndex)?.mediaId
            replacements += Replacement(resumePlayback)
            isPlaying = resumePlayback
        }

        override fun addListener(listener: () -> Unit) {
            listeners += listener
        }

        override fun removeListener(listener: () -> Unit) {
            listeners -= listener
        }

        fun select(id: String) {
            currentMediaItemId = id
        }

        fun emitEvent() {
            listeners.toList().forEach { it() }
        }
    }

    private data class Replacement(val resumePlayback: Boolean)
}
