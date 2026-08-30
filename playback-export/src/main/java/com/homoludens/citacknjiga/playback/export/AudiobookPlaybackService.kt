@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.homoludens.citacknjiga.playback.export

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Plays verified Room-ready audio; generation remains a separate owner. */
public class AudiobookPlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private lateinit var database: AudiobookDatabase
    private lateinit var readyAudio: ReadyAudioRepository
    private lateinit var positionPersistence: PlaybackPositionPersistence
    private lateinit var resources: PlaybackResources<ExoPlayer, MediaSession>
    private var queueCoordinator: PlaybackQueueCoordinator? = null
    private var queuePrepared = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(PlaybackNotificationConfiguration.provider(this))
        database = AudiobookDatabase.create(this)
        readyAudio = ReadyAudioRepository(
            source = RoomReadyAudioSource(database.audiobookDao()),
            storage = AppPrivateStorage(filesDir),
        )
        positionPersistence = PlaybackPositionPersistence(database.audiobookDao(), playbackScope)
        resources = PlaybackResourceLifecycle(
            createPlayer = {
                ExoPlayer.Builder(this)
                    .setSeekBackIncrementMs(DEFAULT_SEEK_BACK_MS)
                    .setSeekForwardIncrementMs(DEFAULT_SEEK_FORWARD_MS)
                    .setHandleAudioBecomingNoisy(true)
                    .setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        true,
                    )
                    .build()
            },
            createSession = { player ->
                MediaSession.Builder(this, player)
                    .setId(SESSION_ID)
                    .setCallback(AudiobookMediaSessionCallback())
                    .setMediaButtonPreferences(AudiobookMediaButtons.preferences)
                    .apply { sessionActivity()?.let(::setSessionActivity) }
                    .build()
            },
            releasePlayer = ExoPlayer::release,
            releaseSession = MediaSession::release,
        ).create()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        destroyed = false
        intent?.getStringExtra(EXTRA_BOOK_PROJECT_ID)?.takeIf(String::isNotBlank)?.let { projectId ->
            loadJob?.cancel()
            loadJob = serviceScope.launch {
                val book = database.audiobookDao().findProjectById(projectId) ?: return@launch
                val chapters = database.audiobookDao().findAllChapters()
                    .filter { it.bookProjectId == projectId }
                withContext(Dispatchers.Main.immediate) {
                    if (!destroyed) {
                        queueCoordinator?.close()
                        resources.player.pause()
                        queuePrepared = false
                        val playerPort = Media3PlayerPort(resources.player)
                        queueCoordinator = PlaybackQueueCoordinator(
                            readyAudio = readyAudio,
                            player = Media3PlaybackQueuePlayer(resources.player),
                            scope = playbackScope,
                            mediaItemFactory = { audio ->
                                mediaItem(audio, book, chapters.firstOrNull { it.id == audio.segment.chapterId })
                            },
                            onCatalogChanged = { catalog ->
                                positionPersistence.updateCatalog(catalog)
                                if (!queuePrepared && catalog.mediaItemIds.isNotEmpty()) {
                                    queuePrepared = true
                                    playbackScope.launch {
                                        resources.player.prepare()
                                        positionPersistence.restore(projectId, catalog, playerPort)
                                        positionPersistence.attach(projectId, catalog, playerPort)
                                        resources.player.play()
                                    }
                                }
                            },
                        ).also { it.start(projectId, chapters) }
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
        queueCoordinator?.close()
        runBlocking { positionPersistence.flush() }
        positionPersistence.close()
        playbackScope.cancel()
        serviceScope.cancel()
        resources.close()
        database.close()
        super.onDestroy()
    }

    private fun mediaItem(
        audio: VerifiedReadyAudio,
        book: BookProjectEntity,
        chapter: ChapterEntity?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(audio.segment.id)
        .setUri(android.net.Uri.fromFile(audio.file))
        .setMediaMetadata(playbackItemMetadata(book, chapter).toMediaMetadata())
        .build()

    private fun sessionActivity(): PendingIntent? = packageManager
        .getLaunchIntentForPackage(packageName)
        ?.let { launchIntent ->
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    public companion object {
        public const val ACTION_PLAY_BOOK: String = "com.homoludens.citacknjiga.action.PLAY_BOOK"
        public const val EXTRA_BOOK_PROJECT_ID: String = "book_project_id"
        private const val SESSION_ID: String = "citac_knjiga_audiobook"
        private const val DEFAULT_SEEK_BACK_MS: Long = 15_000L
        private const val DEFAULT_SEEK_FORWARD_MS: Long = 30_000L

        public fun intent(context: Context, projectId: String): Intent =
            Intent(context, AudiobookPlaybackService::class.java)
                .setAction(ACTION_PLAY_BOOK)
                .putExtra(EXTRA_BOOK_PROJECT_ID, projectId)
    }
}
