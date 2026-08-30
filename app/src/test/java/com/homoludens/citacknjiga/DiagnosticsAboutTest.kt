package com.homoludens.citacknjiga

import com.homoludens.citacknjiga.core.diagnostics.DiagnosticEvent
import com.homoludens.citacknjiga.core.diagnostics.DiagnosticLevel
import com.homoludens.citacknjiga.diagnostics.DiagnosticsAboutState
import com.homoludens.citacknjiga.diagnostics.DiagnosticsExport
import com.homoludens.citacknjiga.diagnostics.DiagnosticsModelState
import com.homoludens.citacknjiga.diagnostics.DiagnosticsStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class DiagnosticsAboutTest {
    @Test
    public fun missingStateIsExplicitForModelStorageAttributionAndEvidence() {
        val state = DiagnosticsAboutState.missing()

        assertTrue(state.model.status.name == "MISSING")
        assertTrue(state.attributions.isEmpty())
        assertTrue(state.storage.status.name == "MISSING")
        assertTrue(state.evidence.all { it.status.name == "MISSING" })
    }

    @Test
    public fun exportDoesNotContainDocumentTextUrisPathsModelContentsOrExceptions() {
        val state = DiagnosticsAboutState.missing()
        val event = DiagnosticEvent(
            timestampMillis = 1L,
            level = DiagnosticLevel.ERROR,
            component = "import",
            message = "import_failed",
            attributes = mapOf(
                "documentText" to "private Serbian text",
                "sourceUri" to "content://private/book",
                "modelPath" to "/data/user/0/app/model.onnx",
                "reason" to "raw exception details",
            ),
        )

        val export = DiagnosticsExport.render(state, listOf(event))

        assertFalse(export.contains("private Serbian text"))
        assertFalse(export.contains("content://private/book"))
        assertFalse(export.contains("/data/user/0/app/model.onnx"))
        assertFalse(export.contains("raw exception details"))
        assertTrue(export.contains("[REDACTED_TEXT]"))
        assertTrue(export.contains("[REDACTED_URI]"))
    }

    @Test
    public fun exportCoversEveryModelStatusWithoutPrivateFields() {
        DiagnosticsStatus.entries.forEach { status ->
            val state = DiagnosticsAboutState.missing().copy(
                model = DiagnosticsModelState(
                    status = status,
                    packageId = "safe-package",
                    packageVersion = "1.0.0",
                    packageSha256 = "a".repeat(64),
                    failureCode = "${status.name}_FAILURE",
                ),
            )
            val export = DiagnosticsExport.render(state, emptyList())
            assertTrue(export.contains("status=${status.name.lowercase()}"))
            assertFalse(export.contains("/data/"))
            assertFalse(export.contains("content://"))
        }
    }
}
