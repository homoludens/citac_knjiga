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

/** Records only local, structured events. Free-form document data is never a log message. */
public class LocalDiagnostics(
    private val sink: DiagnosticSink,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val recordedEvents = ArrayDeque<DiagnosticEvent>()

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
        val event = DiagnosticEvent(
            timestampMillis = nowMillis(),
            level = level,
            component = DiagnosticRedactor.component(component),
            message = DiagnosticRedactor.message(message),
            attributes = attributes.mapValues { (key, value) -> DiagnosticRedactor.redact(key, value) },
        )
        sink.emit(event)
        synchronized(recordedEvents) {
            recordedEvents.addLast(event)
            while (recordedEvents.size > MAX_RETAINED_EVENTS) recordedEvents.removeFirst()
        }
    }

    /** Returns the bounded, already-redacted local event history for user export. */
    public fun snapshot(): List<DiagnosticEvent> = synchronized(recordedEvents) { recordedEvents.toList() }

    public fun redactedExport(): String = snapshot().joinToString(separator = "\n") { event ->
        val attributes = event.attributes.entries.sortedBy { it.key }
            .joinToString(separator = ",") { (key, value) -> "$key=$value" }
        "${event.timestampMillis}|${event.level.name}|${event.component}|${event.message}|$attributes"
    }

    private companion object {
        const val MAX_RETAINED_EVENTS = 100
    }
}

public object DiagnosticRedactor {
    private val safeCategoryKeys = setOf(
        "errorcode",
        "reason",
        "route",
        "stage",
        "status",
        "variant",
        "version",
        "abi",
        "distribution",
        "evidence",
        "license",
        "mode",
        "provider",
        "runtime",
    )
    private val numericKeys = setOf("count", "durationms", "retrycount", "sizebytes")
    private val booleanKeys = setOf("enabled")
    private val safeIdKeys = setOf("bookid", "chapterid", "segmentid", "runid", "packageid")
    private val safeHashKeys = setOf(
        "hash",
        "sha256",
        "sourcefingerprint",
        "sourcesha256",
        "modelsha256",
        "voicesha256",
    )
    private val safeToken = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$")
    private val safeIdentifier = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
    private val integer = Regex("^-?[0-9]+$")
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")

    private val sensitiveKeyParts = setOf(
        "content",
        "exception",
        "fragment",
        "message",
        "path",
        "query",
        "text",
        "throwable",
        "uri",
    )

    public const val REDACTED: String = "[REDACTED]"
    public const val REDACTED_TEXT: String = "[REDACTED_TEXT]"
    public const val REDACTED_URI: String = "[REDACTED_URI]"

    public fun component(value: String): String = value.takeIf(::isSafeToken) ?: REDACTED

    /** Diagnostic messages are stable developer-authored categories, not prose or payloads. */
    public fun message(value: String): String = value.takeIf(::isSafeToken) ?: REDACTED_TEXT

    public fun redact(key: String, value: String): String {
        val normalizedKey = normalizeKey(key)
        return when {
            sensitiveKeyParts.any(normalizedKey::contains) -> when {
                normalizedKey.contains("uri") -> REDACTED_URI
                else -> REDACTED_TEXT
            }
            normalizedKey in numericKeys -> value.takeIf { it.matches(integer) } ?: REDACTED
            normalizedKey in booleanKeys -> value.takeIf { it == "true" || it == "false" } ?: REDACTED
            normalizedKey in safeCategoryKeys -> value.takeIf(::isSafeToken) ?: REDACTED
            normalizedKey in safeIdKeys -> value.takeIf { it.matches(safeIdentifier) } ?: REDACTED
            normalizedKey in safeHashKeys -> value.takeIf { it.matches(sha256) } ?: REDACTED
            else -> REDACTED
        }
    }

    private fun normalizeKey(key: String): String = key.lowercase().filter(Char::isLetterOrDigit)

    private fun isSafeToken(value: String): Boolean = value.matches(safeToken)
}

private class AndroidLogDiagnosticSink : DiagnosticSink {
    override fun emit(event: DiagnosticEvent) {
        val fields = event.attributes.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = " {") { (key, value) -> "$key=$value" } + "}"
        Log.println(event.level.priority, "CitacKnjiga/${event.component}", event.message + fields)
    }
}
