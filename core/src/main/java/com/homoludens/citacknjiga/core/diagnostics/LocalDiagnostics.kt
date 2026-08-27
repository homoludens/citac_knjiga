package com.homoludens.citacknjiga.core.diagnostics

import android.util.Log

public enum class DiagnosticLevel(internal val priority: Int) {
    DEBUG(Log.DEBUG),
    INFO(Log.INFO),
    WARNING(Log.WARN),
    ERROR(Log.ERROR),
}

public data class DiagnosticEvent(
    val timestampMillis: Long,
    val level: DiagnosticLevel,
    val component: String,
    val message: String,
    val attributes: Map<String, String>,
)

public fun interface DiagnosticSink {
    public fun emit(event: DiagnosticEvent)
}

/** Records only local, structured events. Callers must keep messages developer-authored. */
public class LocalDiagnostics(
    private val sink: DiagnosticSink,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    public constructor() : this(AndroidLogDiagnosticSink())

    public fun debug(component: String, message: String, attributes: Map<String, String> = emptyMap()): Unit =
        record(DiagnosticLevel.DEBUG, component, message, attributes)

    public fun info(component: String, message: String, attributes: Map<String, String> = emptyMap()): Unit =
        record(DiagnosticLevel.INFO, component, message, attributes)

    public fun warning(component: String, message: String, attributes: Map<String, String> = emptyMap()): Unit =
        record(DiagnosticLevel.WARNING, component, message, attributes)

    public fun error(component: String, message: String, attributes: Map<String, String> = emptyMap()): Unit =
        record(DiagnosticLevel.ERROR, component, message, attributes)

    private fun record(
        level: DiagnosticLevel,
        component: String,
        message: String,
        attributes: Map<String, String>,
    ) {
        sink.emit(
            DiagnosticEvent(
                timestampMillis = nowMillis(),
                level = level,
                component = component,
                message = message,
                attributes = attributes.mapValues { (key, value) -> DiagnosticRedactor.redact(key, value) },
            ),
        )
    }
}

public object DiagnosticRedactor {
    private val safeKeys = setOf(
        "count",
        "duration_ms",
        "enabled",
        "error_code",
        "reason",
        "retry_count",
        "route",
        "size_bytes",
        "stage",
        "status",
        "variant",
        "version",
    )

    public fun redact(key: String, value: String): String {
        val normalizedKey = key.lowercase()
        return when {
            normalizedKey.contains("uri") -> "[REDACTED_URI]"
            normalizedKey.contains("text") || normalizedKey.contains("content") -> "[REDACTED_TEXT]"
            normalizedKey in safeKeys -> value
            else -> "[REDACTED]"
        }
    }
}

private class AndroidLogDiagnosticSink : DiagnosticSink {
    override fun emit(event: DiagnosticEvent) {
        val fields = event.attributes.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = " {") { (key, value) -> "$key=$value" } + "}"
        Log.println(event.level.priority, "CitacKnjiga/${event.component}", event.message + fields)
    }
}
