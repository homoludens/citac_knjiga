package com.homoludens.citacknjiga.library

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

public data class ProgressDisplay(
    public val completed: Int,
    public val total: Int,
) {
    public val fraction: Float = if (total == 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

public data class ListeningProgressDisplay(
    public val chapterTitle: String?,
    public val positionMs: Long,
    public val durationMs: Long?,
) {
    public val fraction: Float? = durationMs?.takeIf { it > 0 }?.let {
        (positionMs.toFloat() / it).coerceIn(0f, 1f)
    }
}

public data class ChapterDisplay(
    public val chapter: ChapterEntity,
    public val progress: ProgressDisplay,
    public val durationMs: Long,
    public val storageBytes: Long,
)

public data class LibraryBookDisplay(
    public val project: BookProjectEntity,
    public val chapters: List<ChapterDisplay>,
    public val generationProgress: ProgressDisplay,
    public val readyChapterCount: Int,
    public val storageBytes: Long,
    public val listeningProgress: ListeningProgressDisplay?,
    public val failures: List<String>,
) {
    public val title: String = project.title.ifBlank { "Без наслова" }
    public val author: String = project.author ?: "Аутор није наведен"
    public val coverPath: String? = project.coverPath
    public val hasGenerationWork: Boolean = generationProgress.total > 0
    public val status: BookProjectStatus = project.status
}

public data class LibraryViewState(
    public val books: List<LibraryBookDisplay> = emptyList(),
)

public object LibraryDisplayMapper {
    public fun mapBooks(
        projects: List<BookProjectEntity>,
        chapters: List<ChapterEntity>,
        segments: List<AudioSegmentEntity>,
        runs: List<GenerationRunEntity>,
        positions: List<PlaybackPositionEntity>,
        fileSize: (String) -> Long = { File(it).length() },
    ): List<LibraryBookDisplay> = projects.map { project ->
        val projectChapters = chapters.filter { it.bookProjectId == project.id }
        val projectSegments = segments.filter { segment ->
            projectChapters.any { it.id == segment.chapterId }
        }
        val projectRuns = runs.filter { it.bookProjectId == project.id }
        mapBook(project, projectChapters, projectSegments, projectRuns, positions, fileSize)
    }

    public fun mapBook(
        project: BookProjectEntity,
        chapters: List<ChapterEntity>,
        segments: List<AudioSegmentEntity>,
        runs: List<GenerationRunEntity>,
        positions: List<PlaybackPositionEntity>,
        fileSize: (String) -> Long = { File(it).length() },
    ): LibraryBookDisplay {
        val chapterDisplays = chapters.sortedBy { it.ordinal }.map { chapter ->
            val chapterSegments = segments.filter { it.chapterId == chapter.id }
            ChapterDisplay(
                chapter = chapter,
                progress = ProgressDisplay(
                    completed = chapterSegments.count { it.status == AudioSegmentStatus.READY },
                    total = chapterSegments.size,
                ),
                durationMs = chapterSegments.sumOf { it.durationMs ?: 0L },
                storageBytes = chapterSegments.sumOf { it.sizeBytes ?: 0L } +
                    chapter.canonicalMarkdownPath?.let(fileSize).safeSize(),
            )
        }
        val currentPosition = positions.firstOrNull { it.bookProjectId == project.id }
        val listeningChapter = currentPosition?.chapterId?.let { id -> chapters.firstOrNull { it.id == id } }
        val listeningDuration = listeningChapter?.let { chapter ->
            segments.filter { it.chapterId == chapter.id }.sumOf { it.durationMs ?: 0L }
        }
        val failures = buildList {
            project.lastError?.let(::add)
            chapterDisplays.mapNotNull { it.chapter.lastError }.forEach(::add)
            segments.filter { it.chapterId in chapters.map(ChapterEntity::id) }
                .mapNotNull { it.lastError }
                .forEach(::add)
            runs.filter { it.bookProjectId == project.id }.mapNotNull { it.lastError }.forEach(::add)
        }.distinct()
        return LibraryBookDisplay(
            project = project,
            chapters = chapterDisplays,
            generationProgress = ProgressDisplay(
                completed = segments.count { it.status == AudioSegmentStatus.READY },
                total = segments.size,
            ),
            readyChapterCount = chapterDisplays.count { it.chapter.status == ChapterStatus.READY },
            storageBytes = calculateStorage(project, chapterDisplays, fileSize),
            listeningProgress = currentPosition?.let { position ->
                ListeningProgressDisplay(listeningChapter?.title, position.positionMs, listeningDuration)
            },
            failures = failures,
        )
    }

    private fun calculateStorage(
        project: BookProjectEntity,
        chapters: List<ChapterDisplay>,
        fileSize: (String) -> Long,
    ): Long = listOfNotNull(
        project.sourcePath?.let(fileSize),
        project.coverPath?.let(fileSize),
    ).sum() + chapters.sumOf { it.storageBytes }

    private fun Long?.safeSize(): Long = this?.coerceAtLeast(0L) ?: 0L
}

/** Small Room-backed state holder; navigation and generation actions stay outside it. */
public class LibraryController(
    dao: AudiobookDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AutoCloseable {
    private val projects: Flow<List<BookProjectEntity>> = dao.observeAllProjects()
    private val chapters: Flow<List<ChapterEntity>> = dao.observeAllChapters()
    private val segments: Flow<List<AudioSegmentEntity>> = dao.observeAllAudioSegments()
    private val runs: Flow<List<GenerationRunEntity>> = dao.observeAllGenerationRuns()
    private val positions: Flow<List<PlaybackPositionEntity>> = dao.observeAllPlaybackPositions()

    public val state: StateFlow<LibraryViewState> = combine(
        projects,
        chapters,
        segments,
        runs,
        positions,
    ) { projectRows, chapterRows, segmentRows, runRows, positionRows ->
        LibraryViewState(
            LibraryDisplayMapper.mapBooks(projectRows, chapterRows, segmentRows, runRows, positionRows),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), LibraryViewState())

    override fun close() {
        scope.cancel()
    }
}
