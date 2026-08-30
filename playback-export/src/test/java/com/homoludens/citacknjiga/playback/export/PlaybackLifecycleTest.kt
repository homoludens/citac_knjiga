package com.homoludens.citacknjiga.playback.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

public class PlaybackLifecycleTest {
    @Test
    public fun sessionReleasesBeforePlayerAndOnlyOnce() {
        val events = mutableListOf<String>()
        val lifecycle = PlaybackResourceLifecycle(
            createPlayer = { "player" },
            createSession = { player -> events += "session-created:$player"; "session" },
            releasePlayer = { events += "player-released:$it" },
            releaseSession = { events += "session-released:$it" },
        )

        val resources = lifecycle.create()
        resources.close()
        resources.close()

        assertSame("player", resources.player)
        assertEquals(
            listOf("session-created:player", "session-released:session", "player-released:player"),
            events,
        )
    }

    @Test
    public fun failedSessionCreationReleasesPlayer() {
        var released = false
        val lifecycle = PlaybackResourceLifecycle(
            createPlayer = { "player" },
            createSession = { error("session unavailable") },
            releasePlayer = { released = true },
            releaseSession = { error("not reached") },
        )

        runCatching { lifecycle.create() }

        assertEquals(true, released)
    }
}
