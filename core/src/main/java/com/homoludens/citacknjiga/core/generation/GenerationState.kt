package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunStatus

/** One requested state change, kept explicit for validation and diagnostics. */
public data class StateTransition<S : Enum<S>>(
    public val from: S,
    public val to: S,
)

/** A stable, user-actionable value stored in the existing last_error columns. */
public data class GenerationError(
    public val code: String,
    public val message: String,
) {
    init {
        require(code.isNotBlank()) { "Error code cannot be blank" }
        require(message.isNotBlank()) { "Error message cannot be blank" }
    }

    public val record: String
        get() = "${code.trim()}: ${message.trim()}"
}

public class InvalidStateTransitionException(
    public val entityType: String,
    public val transition: StateTransition<*>,
) : IllegalStateException(
    "Invalid $entityType transition: ${transition.from.name} -> ${transition.to.name}",
)

public class StateConsistencyException(message: String) : IllegalStateException(message)

/** Valid transitions for the durable states. Reconciliation transitions are included. */
public object GenerationStateTransitions {
    public val project: Map<BookProjectStatus, Set<BookProjectStatus>> = mapOf(
        BookProjectStatus.IMPORTING to setOf(BookProjectStatus.READY, BookProjectStatus.FAILED),
        BookProjectStatus.READY to setOf(BookProjectStatus.GENERATING, BookProjectStatus.FAILED),
        BookProjectStatus.GENERATING to setOf(
            BookProjectStatus.PAUSED,
            BookProjectStatus.COMPLETED,
            BookProjectStatus.FAILED,
            BookProjectStatus.READY,
        ),
        BookProjectStatus.PAUSED to setOf(
            BookProjectStatus.GENERATING,
            BookProjectStatus.FAILED,
            BookProjectStatus.READY,
        ),
        BookProjectStatus.COMPLETED to setOf(BookProjectStatus.GENERATING, BookProjectStatus.READY),
        BookProjectStatus.FAILED to setOf(BookProjectStatus.GENERATING, BookProjectStatus.READY),
    )

    public val chapter: Map<ChapterStatus, Set<ChapterStatus>> = mapOf(
        ChapterStatus.PENDING to setOf(ChapterStatus.GENERATING, ChapterStatus.READY, ChapterStatus.FAILED),
        ChapterStatus.GENERATING to setOf(
            ChapterStatus.PARTIAL,
            ChapterStatus.READY,
            ChapterStatus.FAILED,
            ChapterStatus.PENDING,
        ),
        ChapterStatus.PARTIAL to setOf(
            ChapterStatus.GENERATING,
            ChapterStatus.READY,
            ChapterStatus.FAILED,
            ChapterStatus.PENDING,
        ),
        ChapterStatus.READY to setOf(ChapterStatus.GENERATING, ChapterStatus.PARTIAL, ChapterStatus.PENDING),
        ChapterStatus.FAILED to setOf(ChapterStatus.GENERATING, ChapterStatus.PENDING),
    )

    public val segment: Map<AudioSegmentStatus, Set<AudioSegmentStatus>> = mapOf(
        AudioSegmentStatus.PENDING to setOf(AudioSegmentStatus.GENERATING, AudioSegmentStatus.STALE, AudioSegmentStatus.FAILED),
        AudioSegmentStatus.GENERATING to setOf(
            AudioSegmentStatus.READY,
            AudioSegmentStatus.FAILED,
            AudioSegmentStatus.PENDING,
            AudioSegmentStatus.STALE,
        ),
        AudioSegmentStatus.READY to setOf(AudioSegmentStatus.STALE),
        AudioSegmentStatus.STALE to setOf(
            AudioSegmentStatus.PENDING,
            AudioSegmentStatus.GENERATING,
            AudioSegmentStatus.FAILED,
        ),
        AudioSegmentStatus.FAILED to setOf(AudioSegmentStatus.PENDING),
    )

    public val run: Map<GenerationRunStatus, Set<GenerationRunStatus>> = mapOf(
        GenerationRunStatus.QUEUED to setOf(
            GenerationRunStatus.RUNNING,
            GenerationRunStatus.PAUSED,
            GenerationRunStatus.FAILED,
            GenerationRunStatus.CANCELLED,
        ),
        GenerationRunStatus.RUNNING to setOf(
            GenerationRunStatus.PAUSED,
            GenerationRunStatus.COMPLETED,
            GenerationRunStatus.FAILED,
            GenerationRunStatus.CANCELLED,
            GenerationRunStatus.QUEUED,
        ),
        GenerationRunStatus.PAUSED to setOf(
            GenerationRunStatus.RUNNING,
            GenerationRunStatus.CANCELLED,
            GenerationRunStatus.QUEUED,
        ),
        GenerationRunStatus.FAILED to setOf(GenerationRunStatus.QUEUED),
        GenerationRunStatus.COMPLETED to emptySet(),
        GenerationRunStatus.CANCELLED to emptySet(),
    )
}

public object GenerationStateValidator {
    public fun validateProject(from: BookProjectStatus, to: BookProjectStatus): Unit =
        validate("project", StateTransition(from, to), GenerationStateTransitions.project)

    public fun validateChapter(from: ChapterStatus, to: ChapterStatus): Unit =
        validate("chapter", StateTransition(from, to), GenerationStateTransitions.chapter)

    public fun validateSegment(from: AudioSegmentStatus, to: AudioSegmentStatus): Unit =
        validate("audio segment", StateTransition(from, to), GenerationStateTransitions.segment)

    public fun validateRun(from: GenerationRunStatus, to: GenerationRunStatus): Unit =
        validate("generation run", StateTransition(from, to), GenerationStateTransitions.run)

    private fun <S : Enum<S>> validate(
        entityType: String,
        transition: StateTransition<S>,
        allowed: Map<S, Set<S>>,
    ) {
        if (transition.to !in allowed.getValue(transition.from)) {
            throw InvalidStateTransitionException(entityType, transition)
        }
    }
}
