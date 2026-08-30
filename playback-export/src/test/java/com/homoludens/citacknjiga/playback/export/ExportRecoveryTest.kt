package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.ExportChapterStatus
import org.junit.Assert.assertTrue
import org.junit.Test

public class ExportRecoveryTest {
    @Test
    public fun stateMachineAllowsOnlyExplicitRetryBackToPending() {
        ExportJobStateValidator.requireTransition(ExportChapterStatus.PENDING, ExportChapterStatus.WRITING)
        ExportJobStateValidator.requireTransition(ExportChapterStatus.WRITING, ExportChapterStatus.VERIFIED)
        ExportJobStateValidator.requireTransition(ExportChapterStatus.VERIFIED, ExportChapterStatus.PENDING)
        assertTrue(runCatching {
            ExportJobStateValidator.requireTransition(ExportChapterStatus.PENDING, ExportChapterStatus.VERIFIED)
        }.isFailure)
    }
}
