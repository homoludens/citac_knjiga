package com.homoludens.citacknjiga.playback.export

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.homoludens.citacknjiga.core.database.ChapterEntity
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class PlayerControlState(
    public val projectId: String? = null,
    public val connected: Boolean = false,
    public val playing: Boolean = false,
    public val positionMs: Long = 0L,
    public val durationMs: Long? = null,
    public val currentChapterId: String? = null,
    public val chapters: List<PlaybackChapter> = emptyList(),
    public val jumps: PlaybackJumpValues = PlaybackJumpValues(),
    public val speed: Float = 1.0f,
)

/** A MediaController-backed view model. It never creates or releases the service player. */
public class AudiobookPlayerController(
    context: Context,
    private val readyAudio: ReadyAudioRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val stateMutable = MutableStateFlow(PlayerControlState())
    private val controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private var playerPort: Media3PlayerPort? = null
    private var commands: PlaybackControlCommands? = null
    private var readyJob: Job? = null
    private var closed = false
    private var selectedChapters: List<ChapterEntity> = emptyList()
    private var catalog = PlaybackCatalog(emptyList(), emptyList())
    private var pendingChapterId: String? = null
    private var selectedSpeed = 1.0f

    public val state: StateFlow<PlayerControlState> = stateMutable.asStateFlow()

    init {
        val token = SessionToken(
            appContext,
            ComponentName(appContext, AudiobookPlaybackService::class.java),
        )
        controllerFuture = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture.addListener(
            {
                if (closed) return@addListener
                runCatching { controllerFuture.get() }
                    .onSuccess(::attachController)
                    .onFailure { publish() }
            },
            Executor { it.run() },
        )
    }

    public fun bindBook(projectId: String, chapters: List<ChapterEntity>) {
        if (stateMutable.value.projectId == projectId && selectedChapters == chapters) return
        selectedChapters = chapters
        catalog = PlaybackCatalog(chapters.sortedBy { it.ordinal }.map { chapter ->
            PlaybackChapter(chapter.id, chapter.title, chapter.ordinal, emptyList(), 0, false)
        }, emptyList())
        readyJob?.cancel()
        readyJob = scope.launch {
            readyAudio.observeVerified(projectId).collect { ready ->
                catalog = PlaybackCatalog.from(selectedChapters, ready)
                publish()
                applyPendingChapter()
            }
        }
        stateMutable.value = stateMutable.value.copy(projectId = projectId, chapters = catalog.chapters)
        publish()
    }

    public fun playPause() {
        val port = playerPort
        if (port == null || port.mediaItemCount == 0) {
            startSelectedBook()
        } else {
            commands?.playPause()
            publish()
        }
    }

    public fun seek(positionMs: Long) {
        commands?.seek(positionMs)
        publish()
    }

    public fun jumpBackward() {
        commands?.jumpBackward(state.value.jumps)
        publish()
    }

    public fun jumpForward() {
        commands?.jumpForward(state.value.jumps)
        publish()
    }

    public fun previousChapter(): Boolean = commands?.previousChapter(catalog) == true

    public fun nextChapter(): Boolean = commands?.nextChapter(catalog) == true

    public fun selectChapter(chapterId: String): Boolean {
        val chapter = catalog.chapters.firstOrNull { it.id == chapterId && it.available } ?: return false
        pendingChapterId = chapter.id
        if (playerPort?.mediaItemCount == 0) startSelectedBook()
        applyPendingChapter()
        return true
    }

    public fun setJumpValues(backwardMs: Long, forwardMs: Long) {
        stateMutable.value = stateMutable.value.copy(jumps = PlaybackJumpValues.of(backwardMs, forwardMs))
    }

    public fun setSpeed(speed: Float): Boolean {
        if (speed !in SUPPORTED_PLAYBACK_SPEEDS) return false
        selectedSpeed = speed
        commands?.setSpeed(speed)
        publish()
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        readyJob?.cancel()
        playerPort?.let { port ->
            port.close()
        }
        mediaController?.release()
        controllerFuture.cancel(false)
        scope.cancel()
    }

    private fun attachController(controller: MediaController) {
        if (closed) {
            controller.release()
            return
        }
        mediaController = controller
        playerPort = Media3PlayerPort(controller).also { port ->
            port.addListener(::onPlayerChanged)
        }
        commands = PlaybackControlCommands(playerPort!!)
        publish()
    }

    private fun onPlayerChanged() {
        scope.launch {
            applyPendingChapter()
            publish()
        }
    }

    private fun applyPendingChapter() {
        val chapterId = pendingChapterId ?: return
        if (commands?.selectChapter(catalog, chapterId) == true) pendingChapterId = null
    }

    private fun startSelectedBook() {
        state.value.projectId?.let { projectId ->
            appContext.startService(AudiobookPlaybackService.intent(appContext, projectId))
        }
    }

    private fun publish() {
        val port = playerPort
        val currentChapter = catalog.chapterForMediaItemId(port?.currentMediaItemId)
        stateMutable.value = stateMutable.value.copy(
            connected = port != null,
            playing = port?.isPlaying ?: false,
            positionMs = port?.positionMs?.coerceAtLeast(0L) ?: 0L,
            durationMs = port?.durationMs,
            currentChapterId = currentChapter?.id,
            chapters = catalog.chapters,
            speed = port?.speed ?: selectedSpeed,
        )
    }
}
