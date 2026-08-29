package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class GenerationStateTest {
    @Test
    public fun declaredTransitionsAcceptLifecycleAndRecoveryEdges() {
        GenerationStateValidator.validateProject(BookProjectStatus.IMPORTING, BookProjectStatus.READY)
        GenerationStateValidator.validateChapter(ChapterStatus.GENERATING, ChapterStatus.PARTIAL)
        GenerationStateValidator.validateSegment(AudioSegmentStatus.READY, AudioSegmentStatus.STALE)
        GenerationStateValidator.validateRun(GenerationRunStatus.RUNNING, GenerationRunStatus.QUEUED)
    }

    @Test
    public fun invalidTransitionsAndTerminalRunsAreRejected() {
        assertThrows(InvalidStateTransitionException::class.java) {
            GenerationStateValidator.validateProject(BookProjectStatus.IMPORTING, BookProjectStatus.COMPLETED)
        }
        assertThrows(InvalidStateTransitionException::class.java) {
            GenerationStateValidator.validateSegment(AudioSegmentStatus.READY, AudioSegmentStatus.GENERATING)
        }
        assertThrows(InvalidStateTransitionException::class.java) {
            GenerationStateValidator.validateRun(GenerationRunStatus.COMPLETED, GenerationRunStatus.QUEUED)
        }
    }

    @Test
    public fun errorsAreStableAndActionableRecords() {
        val error = GenerationError("MODEL_OUTPUT_INVALID", "audio contains silence")

        assertEquals("MODEL_OUTPUT_INVALID: audio contains silence", error.record)
        assertThrows(IllegalArgumentException::class.java) { GenerationError("", "message") }
        assertThrows(IllegalArgumentException::class.java) { GenerationError("CODE", " ") }
    }
}
