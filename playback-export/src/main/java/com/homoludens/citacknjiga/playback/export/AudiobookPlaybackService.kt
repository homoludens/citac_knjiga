package com.homoludens.citacknjiga.playback.export

import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plays a snapshot of verified Room-ready audio; generation remains a separate owner. */
public class AudiobookPlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private lateinit var database: AudiobookDatabase
    private lateinit var readyAudio: ReadyAudioRepository
    private lateinit var resources: PlaybackResources<ExoPlayer, MediaSession>
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        database = AudiobookDatabase.create(this)
        readyAudio = ReadyAudioRepository(
            source = RoomReadyAudioSource(database.audiobookDao()),
            storage = AppPrivateStorage(filesDir),
        )
        resources = PlaybackResourceLifecycle(
            createPlayer = {
                ExoPlayer.Builder(this).build().apply {
                    setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        true,
                    )
                }
            },
            createSession = { player -> MediaSession.Builder(this, player).build() },
            releasePlayer = ExoPlayer::release,
            releaseSession = MediaSession::release,
        ).create()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        destroyed = false
        intent?.getStringExtra(EXTRA_BOOK_PROJECT_ID)?.takeIf(String::isNotBlank)?.let { projectId ->
            loadJob?.cancel()
            loadJob = serviceScope.launch {
                val items = readyAudio.observeVerified(projectId).first()
                if (items.isEmpty()) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (!destroyed) {
                        resources.player.setMediaItems(items.map(::mediaItem))
                        resources.player.prepare()
                        resources.player.play()
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = resources.session

    override fun onDestroy() {
        destroyed = true
        loadJob?.cancel()
        serviceScope.cancel()
        resources.close()
        database.close()
        super.onDestroy()
    }

    private fun mediaItem(audio: VerifiedReadyAudio): MediaItem = MediaItem.Builder()
        .setMediaId(audio.segment.id)
        .setUri(android.net.Uri.fromFile(audio.file))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(audio.segment.id).build())
        .build()

    public companion object {
        public const val ACTION_PLAY_BOOK: String = "com.homoludens.citacknjiga.action.PLAY_BOOK"
        public const val EXTRA_BOOK_PROJECT_ID: String = "book_project_id"

        public fun intent(context: Context, projectId: String): Intent =
            Intent(context, AudiobookPlaybackService::class.java)
                .setAction(ACTION_PLAY_BOOK)
                .putExtra(EXTRA_BOOK_PROJECT_ID, projectId)
    }
}
