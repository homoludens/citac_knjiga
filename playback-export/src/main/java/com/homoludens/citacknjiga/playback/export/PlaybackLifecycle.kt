package com.homoludens.citacknjiga.playback.export

/** Small lifecycle seam so player/session release can be tested without a device. */
public class PlaybackResourceLifecycle<P, S>(
    private val createPlayer: () -> P,
    private val createSession: (P) -> S,
    private val releasePlayer: (P) -> Unit,
    private val releaseSession: (S) -> Unit,
) {
    private var active: PlaybackResources<P, S>? = null

    public fun create(): PlaybackResources<P, S> {
        check(active == null) { "Playback resources are already created" }
        val player = createPlayer()
        return try {
            PlaybackResources(player, createSession(player), releasePlayer, releaseSession).also { active = it }
        } catch (failure: Throwable) {
            releasePlayer(player)
            throw failure
        }
    }
}

public class PlaybackResources<P, S>(
    public val player: P,
    public val session: S,
    private val releasePlayer: (P) -> Unit,
    private val releaseSession: (S) -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        releaseSession(session)
        releasePlayer(player)
    }
}
