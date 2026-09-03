package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File

public data class ActiveGenerationProgress(
    val runId: String,
    val segmentId: String,
    val completedWords: Int,
    val totalWords: Int,
    val temporaryWavBytes: Long,
)

/** Small app-private checkpoint paired with the cumulative staging WAV. */
public class GenerationProgressStore(
    private val storage: AppPrivateStorage,
) {
    public fun wavFile(runId: String): File = storage.temporaryFile(owner(runId), WAV_NAME)

    public fun update(runId: String, segmentId: String, completedWords: Int, totalWords: Int) {
        require(completedWords in 0..totalWords)
        val state = storage.temporaryFile(owner(runId), STATE_NAME)
        require(state.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val temporary = File(state.parentFile, "$STATE_NAME.tmp")
        temporary.writeText("$segmentId\n$completedWords\n$totalWords\n")
        check(temporary.renameTo(state) || run {
            temporary.copyTo(state, overwrite = true)
            temporary.delete()
        })
    }

    public fun snapshot(runId: String): ActiveGenerationProgress? {
        val values = runCatching {
            storage.temporaryFile(owner(runId), STATE_NAME)
                .takeIf(File::isFile)
                ?.readLines()
        }.getOrNull() ?: return null
        if (values.size != 3) return null
        val completedWords = values[1].toIntOrNull() ?: return null
        val totalWords = values[2].toIntOrNull() ?: return null
        if (completedWords !in 0..totalWords) return null
        return ActiveGenerationProgress(
            runId = runId,
            segmentId = values[0],
            completedWords = completedWords,
            totalWords = totalWords,
            temporaryWavBytes = wavFile(runId).takeIf(File::isFile)?.length() ?: 0L,
        )
    }

    public fun clear(runId: String) {
        storage.temporaryFile(owner(runId), STATE_NAME).parentFile?.deleteRecursively()
    }

    private fun owner(runId: String): String = "generation-progress-$runId"

    private companion object {
        const val WAV_NAME = "current.wav"
        const val STATE_NAME = "progress"
    }
}
