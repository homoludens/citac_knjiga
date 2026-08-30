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

    @Test
    public fun messagesAndExceptionDetailsDoNotLeakSensitiveData() {
        val events = mutableListOf<DiagnosticEvent>()
        val diagnostics = LocalDiagnostics(DiagnosticSink { events += it })
        val latin = "Latin private document text"
        val cyrillic = "Тајни ћирилични текст"
        val failure = IllegalStateException(
            "failed for $latin; $cyrillic; content://books/private?token=secret#chapter; " +
                "file:///data/user/0/app/files/model-packages/model.onnx",
        )

        diagnostics.error(
            component = "import",
            message = "import_failed",
            attributes = mapOf(
                "error_code" to "SOURCE_UNAVAILABLE",
                "reason" to (failure.message ?: ""),
                "sourceUri" to "content://books/private?token=secret#chapter",
                "modelPath" to "/data/user/0/app/files/model-packages/model.onnx",
                "query" to "?token=secret",
                "fragment" to "#chapter",
                "book_id" to "book-123",
                "source_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )

        val event = events.single()
        val serialized = event.toString()
        assertEquals("import_failed", event.message)
        assertEquals("SOURCE_UNAVAILABLE", event.attributes["error_code"])
        assertEquals("book-123", event.attributes["book_id"])
        assertEquals("[REDACTED_URI]", event.attributes["sourceUri"])
        assertEquals("[REDACTED_TEXT]", event.attributes["modelPath"])
        assertEquals("[REDACTED]", event.attributes["reason"])
        assertTrue(serialized.contains("[REDACTED_TEXT]"))
        assertTrue(latin !in serialized)
        assertTrue(cyrillic !in serialized)
        assertTrue("content://books/private" !in serialized)
        assertTrue("file:///data/user/0" !in serialized)
        assertTrue("token=secret" !in serialized)
        assertTrue("#chapter" !in serialized)
    }

    @Test
    public fun safeCategoriesHashesIdsAndNormalMessagesRemainUseful() {
        val events = mutableListOf<DiagnosticEvent>()
        val diagnostics = LocalDiagnostics(DiagnosticSink { events += it })

        diagnostics.info(
            component = "generation",
            message = "generation_completed",
            attributes = mapOf(
                "status" to "success",
                "count" to "2",
                "enabled" to "true",
                "segment_id" to "segment-7",
                "model_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )

        val event = events.single()
        assertEquals("generation_completed", event.message)
        assertEquals("success", event.attributes["status"])
        assertEquals("2", event.attributes["count"])
        assertEquals("true", event.attributes["enabled"])
        assertEquals("segment-7", event.attributes["segment_id"])
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            event.attributes["model_sha256"],
        )
    }

    @Test
    public fun freeFormMessagesAreRedactedEvenWithoutAUri() {
        val events = mutableListOf<DiagnosticEvent>()
        val diagnostics = LocalDiagnostics(DiagnosticSink { events += it })

        diagnostics.warning("import", "This is a private Latin document")
        diagnostics.warning("import", "Ово је приватни ћирилични документ")

        assertEquals(listOf("[REDACTED_TEXT]", "[REDACTED_TEXT]"), events.map { it.message })
    }
}
