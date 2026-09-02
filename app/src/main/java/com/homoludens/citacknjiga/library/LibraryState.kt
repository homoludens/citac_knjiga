package com.homoludens.citacknjiga.library

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunEntity
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.core.database.GenerationProgressSnapshot
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.PlaybackPositionEntity
import com.homoludens.citacknjiga.core.generation.GenerationEngine
import com.homoludens.citacknjiga.core.generation.GenerationRequest
import com.homoludens.citacknjiga.core.generation.GenerationRequestFactory
import com.homoludens.citacknjiga.core.generation.GenerationScope
import com.homoludens.citacknjiga.core.generation.QueuedGeneration
import com.homoludens.citacknjiga.tts.onnx.TtsEngine
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
    public val completedWords: Long = completed.toLong(),
    public val totalWords: Long = total.toLong(),
) {
    public val percentage: Int = if (totalWords <= 0L) {
        0
    } else {
        ((completedWords.toDouble() / totalWords.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }
    public val fraction: Float = if (totalWords <= 0L) {
        0f
    } else {
        (completedWords.toDouble() / totalWords.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    public val usesWordEstimate: Boolean = completedWords != completed.toLong() || totalWords != total.toLong()
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
    public val generationStatus: GenerationRunStatus? = null,
)

public data class LibraryBookDisplay(
    public val project: BookProjectEntity,
    public val chapters: List<ChapterDisplay>,
    public val generationProgress: ProgressDisplay,
    public val readyChapterCount: Int,
    public val storageBytes: Long,
    public val listeningProgress: ListeningProgressDisplay?,
    public val failures: List<String>,
    public val generationRunId: String? = null,
    public val generationStatus: GenerationRunStatus? = null,
) {
    public val title: String = project.title
    public val author: String = project.author.orEmpty()
    public val coverPath: String? = project.coverPath
    public val hasGenerationWork: Boolean = generationProgress.total > 0
    public val status: BookProjectStatus = project.status
}

public data class LibraryViewState(
    public val books: List<LibraryBookDisplay> = emptyList(),
)

public enum class RegenerationResultStatus {
    QUEUING,
    QUEUED,
    FAILED,
}

public data class RegenerationFeedback(
    public val projectId: String,
    public val scope: GenerationScope,
    public val status: RegenerationResultStatus,
    public val runId: String? = null,
)

public data class RegenerationResult(
    public val projectId: String,
    public val scope: GenerationScope,
    public val status: RegenerationResultStatus,
    public val queued: QueuedGeneration? = null,
)

/** Builds fresh persisted requests so retry always uses current source and engine selection. */
public class LibraryRegenerationController(
    private val findProject: (String) -> BookProjectEntity?,
    private val findChapters: () -> Collection<ChapterEntity>,
    private val findNarrationBlocks: () -> Collection<NarrationBlockEntity>,
    private val findRun: (String) -> GenerationRunEntity?,
    private val findSegments: () -> Collection<AudioSegmentEntity>,
    private val invalidateAndQueue: (GenerationRequest) -> QueuedGeneration,
    private val selectedEngine: () -> TtsEngine,
) {
    public fun regenerate(projectId: String, scope: GenerationScope): RegenerationResult {
        return try {
            val project = findProject(projectId) ?: return failed(projectId, scope)
            val request = GenerationRequestFactory.fromExistingNarrationBlocks(
                project = project,
                chapters = findChapters(),
                narrationBlocks = findNarrationBlocks(),
                scope = scope,
                engine = selectedEngine().toGenerationEngine(),
            )
            if (request.narrationBlocks.isEmpty()) return failed(projectId, scope)
            RegenerationResult(
                projectId = projectId,
                scope = scope,
                status = RegenerationResultStatus.QUEUED,
                queued = invalidateAndQueue(request),
            )
        } catch (_: Exception) {
            failed(projectId, scope)
        }
    }

    /** Reconstructs the persisted chapter/book scope before starting a clean replacement. */
    public fun retry(runId: String): RegenerationResult? {
        val run = findRun(runId) ?: return null
        val projectChapters = findChapters().filter { it.bookProjectId == run.bookProjectId }
        val narrationBlocks = findNarrationBlocks()
        val runChapterIds = findSegments()
            .filter { it.generationRunId == runId }
            .map(AudioSegmentEntity::chapterId)
            .toSet()
        if (runChapterIds.isEmpty()) return null
        val narratableChapterIds = projectChapters
            .filter { chapter ->
                narrationBlocks.any { block ->
                    block.chapterId == chapter.id &&
                        block.blockType != com.homoludens.citacknjiga.core.database.NarrationBlockType.SKIPPED &&
                        block.sourceText.isNotBlank()
                }
            }
            .map(ChapterEntity::id)
            .toSet()
        val scope = when {
            runChapterIds == narratableChapterIds -> GenerationScope.CompleteBook
            runChapterIds.size == 1 -> GenerationScope.Chapter(runChapterIds.single())
            else -> return null
        }
        return regenerate(run.bookProjectId, scope)
    }

    private fun failed(projectId: String, scope: GenerationScope): RegenerationResult =
        RegenerationResult(projectId, scope, RegenerationResultStatus.FAILED)

    private fun TtsEngine.toGenerationEngine(): GenerationEngine = when (this) {
        TtsEngine.KOKORO -> GenerationEngine.KOKORO
        TtsEngine.VITS -> GenerationEngine.VITS
    }
}

public object LibraryDisplayMapper {
    public fun mapBooks(
        projects: List<BookProjectEntity>,
        chapters: List<ChapterEntity>,
        segments: List<AudioSegmentEntity>,
        runs: List<GenerationRunEntity>,
        positions: List<PlaybackPositionEntity>,
        fileSize: (String) -> Long = { File(it).length() },
        chapterProgress: List<GenerationProgressSnapshot> = emptyList(),
        bookProgress: List<GenerationProgressSnapshot> = emptyList(),
    ): List<LibraryBookDisplay> = projects.filterNot { it.isDeleting }.map { project ->
        val projectChapters = chapters.filter { it.bookProjectId == project.id }
        val projectSegments = segments.filter { segment ->
            projectChapters.any { it.id == segment.chapterId }
        }
        val projectRuns = runs.filter { it.bookProjectId == project.id }
        mapBook(
            project = project,
            chapters = projectChapters,
            segments = projectSegments,
            runs = projectRuns,
            positions = positions,
            fileSize = fileSize,
            chapterProgress = chapterProgress,
            bookProgress = bookProgress.firstOrNull { it.scopeId == project.id },
        )
    }

    public fun mapBook(
        project: BookProjectEntity,
        chapters: List<ChapterEntity>,
        segments: List<AudioSegmentEntity>,
        runs: List<GenerationRunEntity>,
        positions: List<PlaybackPositionEntity>,
        fileSize: (String) -> Long = { File(it).length() },
        chapterProgress: List<GenerationProgressSnapshot> = emptyList(),
        bookProgress: GenerationProgressSnapshot? = null,
    ): LibraryBookDisplay {
        val chapterDisplays = chapters.sortedBy { it.ordinal }.map { chapter ->
            val chapterSegments = segments.filter { it.chapterId == chapter.id }
            val chapterProgressSnapshot = chapterProgress.firstOrNull { it.scopeId == chapter.id }
            val chapterRun = chapterProgressSnapshot?.generationStatus ?: runs
                .filter { run -> chapterSegments.any { it.generationRunId == run.id } }
                .maxWithOrNull(compareBy<GenerationRunEntity> { it.requestedAt }.thenBy { it.id })
                ?.status
            ChapterDisplay(
                chapter = chapter,
                progress = chapterProgressSnapshot?.toDisplay() ?: legacyProgress(chapterSegments),
                durationMs = chapterSegments.sumOf { it.durationMs ?: 0L },
                storageBytes = chapterSegments.sumOf { it.sizeBytes ?: 0L } +
                    chapter.canonicalMarkdownPath?.let(fileSize).safeSize(),
                generationStatus = chapterRun,
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
        val latestRun = runs
            .filter { it.bookProjectId == project.id }
            .maxWithOrNull(compareBy<GenerationRunEntity> { it.requestedAt }.thenBy { it.id })
        val progress = bookProgress?.toDisplay() ?: legacyProgress(segments)
        return LibraryBookDisplay(
            project = project,
            chapters = chapterDisplays,
            generationProgress = progress,
            readyChapterCount = chapterDisplays.count { it.chapter.status == ChapterStatus.READY },
            storageBytes = calculateStorage(project, chapterDisplays, fileSize),
            listeningProgress = currentPosition?.let { position ->
                ListeningProgressDisplay(listeningChapter?.title, position.positionMs, listeningDuration)
            },
            failures = failures,
            generationRunId = latestRun?.id,
            generationStatus = bookProgress?.generationStatus ?: latestRun?.status,
        )
    }

    private fun GenerationProgressSnapshot.toDisplay(): ProgressDisplay {
        val wordBased = estimatedSegments > 0
        return ProgressDisplay(
            completed = completedSegments,
            total = totalSegments,
            completedWords = if (wordBased) completedWords else completedSegments.toLong(),
            totalWords = if (wordBased) totalWords else totalSegments.toLong(),
        )
    }

    private fun legacyProgress(segments: List<AudioSegmentEntity>): ProgressDisplay = ProgressDisplay(
        completed = segments.count { it.status == AudioSegmentStatus.READY },
        total = segments.size,
    )

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
    private val chapterProgress: Flow<List<GenerationProgressSnapshot>> = dao.observeChapterGenerationProgress()
    private val bookProgress: Flow<List<GenerationProgressSnapshot>> = dao.observeBookGenerationProgress()

    private val rows = combine(
        projects,
        chapters,
        segments,
        runs,
        positions,
    ) { projectRows, chapterRows, segmentRows, runRows, positionRows ->
        LibraryRows(projectRows, chapterRows, segmentRows, runRows, positionRows)
    }

    public val state: StateFlow<LibraryViewState> = combine(rows, chapterProgress, bookProgress) {
        currentRows, chapterProgressRows, bookProgressRows ->
        LibraryViewState(
            LibraryDisplayMapper.mapBooks(
                projects = currentRows.projects,
                chapters = currentRows.chapters,
                segments = currentRows.segments,
                runs = currentRows.runs,
                positions = currentRows.positions,
                chapterProgress = chapterProgressRows,
                bookProgress = bookProgressRows,
            ),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), LibraryViewState())

    override fun close() {
        scope.cancel()
    }
}

private data class LibraryRows(
    val projects: List<BookProjectEntity>,
    val chapters: List<ChapterEntity>,
    val segments: List<AudioSegmentEntity>,
    val runs: List<GenerationRunEntity>,
    val positions: List<PlaybackPositionEntity>,
)
