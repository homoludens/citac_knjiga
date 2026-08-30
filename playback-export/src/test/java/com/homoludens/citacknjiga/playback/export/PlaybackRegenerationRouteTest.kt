package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

public class PlaybackRegenerationRouteTest {
    @Test
    public fun callbackReceivesTheUnavailableSegmentAndReturnsAnActionableRoute() {
        var requested: String? = null
        val issue = PlaybackUnavailableAudio(
            segment = AudioSegmentEntity(
                id = "segment-1",
                chapterId = "chapter",
                narrationBlockId = "block",
                sequence = 0,
                chunkOrdinal = 0,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            reason = PlaybackUnavailableReason.NOT_READY,
            message = "Audio is not ready for playback",
        )

        val route = PlaybackRegenerationRoute { requested = it }

        assertEquals("generation/retry/segment-1", route.request(issue))
        assertEquals("segment-1", requested)
    }
}
