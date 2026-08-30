package com.homoludens.citacknjiga.playback.export

import com.homoludens.citacknjiga.core.database.ExportChapterStatus

/** Keeps export checkpoints monotonic except for an explicit retry. */
public object ExportJobStateValidator {
    public fun requireTransition(from: ExportChapterStatus, to: ExportChapterStatus) {
        require(
            when (from) {
                ExportChapterStatus.PENDING -> to in setOf(ExportChapterStatus.WRITING, ExportChapterStatus.CANCELLED)
                ExportChapterStatus.WRITING -> to in setOf(
                    ExportChapterStatus.VERIFIED,
                    ExportChapterStatus.FAILED,
                    ExportChapterStatus.PENDING,
                    ExportChapterStatus.CANCELLED,
                )
                ExportChapterStatus.VERIFIED -> to == ExportChapterStatus.PENDING
                ExportChapterStatus.FAILED -> to in setOf(ExportChapterStatus.PENDING, ExportChapterStatus.CANCELLED)
                ExportChapterStatus.CANCELLED -> to == ExportChapterStatus.PENDING
            },
        ) { "Invalid export chapter transition $from -> $to" }
    }
}
