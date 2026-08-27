package com.homoludens.citacknjiga.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class LocalDiagnosticsTest {
    @Test
    public fun eventsAreStructuredAndSensitiveAttributesAreRedacted() {
        val events = mutableListOf<DiagnosticEvent>()
        val diagnostics = LocalDiagnostics(
            sink = DiagnosticSink { events += it },
            nowMillis = { 1234L },
        )

        diagnostics.warning(
            component = "import",
            message = "provider_unavailable",
            attributes = mapOf(
                "count" to "2",
                "documentText" to "private text",
                "sourceUri" to "content://private/document",
            ),
        )

        val event = events.single()
        assertEquals(1234L, event.timestampMillis)
        assertEquals(DiagnosticLevel.WARNING, event.level)
        assertEquals("2", event.attributes["count"])
        assertEquals("[REDACTED_TEXT]", event.attributes["documentText"])
        assertEquals("[REDACTED_URI]", event.attributes["sourceUri"])
        assertTrue(event.attributes.values.none { it == "private text" })
    }
}
