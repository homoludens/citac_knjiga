package com.homoludens.citacknjiga.core.generation

/** Stable categories used in persisted segment errors and retry decisions. */
public enum class GenerationFailureCategory {
    AUDIO_VALIDATION,
    INFERENCE,
    WRITE,
    PROVENANCE,
    UNKNOWN,
}

public enum class GenerationFailurePhase {
    INFERENCE,
    PUBLICATION,
}

/** A failure that crosses a generation boundary with an actionable stable code. */
public open class GenerationFailureException(
    public val category: GenerationFailureCategory,
    public val stableCode: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    init {
        require(stableCode.isNotBlank()) { "Generation failure code cannot be blank" }
        require(message.isNotBlank()) { "Generation failure message cannot be blank" }
    }
}

public data class ClassifiedGenerationFailure(
    public val category: GenerationFailureCategory,
    public val code: String,
    public val message: String,
    public val retryable: Boolean,
) {
    public val error: GenerationError
        get() = GenerationError(code, message)
}

/** Keeps retry behavior small, deterministic, and bounded by persisted attempts. */
public data class GenerationRetryPolicy(
    public val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts > 0) { "Maximum generation attempts must be positive" }
    }

    public fun canRetry(attemptCount: Int): Boolean = attemptCount in 0 until maxAttempts

    public fun shouldRetry(failure: ClassifiedGenerationFailure, attemptCount: Int): Boolean =
        failure.retryable && canRetry(attemptCount)

    public companion object {
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3
    }
}

public object GenerationFailurePolicy {
    public fun classify(
        failure: Throwable,
        phase: GenerationFailurePhase,
    ): ClassifiedGenerationFailure {
        val typed = failure.findCause<GenerationFailureException>()
        if (typed != null) {
            return ClassifiedGenerationFailure(
                category = typed.category,
                code = typed.stableCode,
                message = typed.message ?: typed.stableCode,
                retryable = typed.category != GenerationFailureCategory.PROVENANCE,
            )
        }
        return when (phase) {
            GenerationFailurePhase.INFERENCE -> ClassifiedGenerationFailure(
                category = GenerationFailureCategory.INFERENCE,
                code = "INFERENCE_FAILURE",
                message = failure.actionableMessage("inference failed"),
                retryable = true,
            )
            GenerationFailurePhase.PUBLICATION -> ClassifiedGenerationFailure(
                category = GenerationFailureCategory.WRITE,
                code = "WRITE_FAILURE",
                message = failure.actionableMessage("audio publication failed"),
                retryable = true,
            )
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun Throwable.actionableMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback
}
