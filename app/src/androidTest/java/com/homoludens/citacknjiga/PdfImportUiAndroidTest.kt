package com.homoludens.citacknjiga

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.document.DocumentBlock
import com.homoludens.citacknjiga.core.document.ImportProvenance
import com.homoludens.citacknjiga.core.document.PageLocator
import com.homoludens.citacknjiga.document.pdf.NormalizedRect
import com.homoludens.citacknjiga.document.pdf.PdfImportInspection
import com.homoludens.citacknjiga.document.pdf.PdfImportPreview
import com.homoludens.citacknjiga.document.pdf.PdfPage
import com.homoludens.citacknjiga.document.pdf.PdfTextBlock
import com.homoludens.citacknjiga.document.pdf.PageRange
import com.homoludens.citacknjiga.document.pdf.StagedPdfSource
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

public class PdfImportUiAndroidTest {
    @get:Rule
    public val composeRule = createComposeRule()

    @Test
    public fun invalidRangeIsRejectedAndPreviewTextIsShownBeforeAcceptance() {
        var accepted = false
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    PdfImportPreviewContent(
                        state = PdfImportUiState.Ready(preview()),
                        enabled = true,
                        onSelect = {},
                        onPreview = { _, _ -> },
                        onAccept = { accepted = true },
                        onCancel = {},
                        onCancelLoading = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Преглед текста").assertExists()
        composeRule.onAllNodesWithText("1", useUnmergedTree = true).get(0).performTextReplacement("0")
        composeRule.onNodeWithText("Прикажи изабране странице").performClick()
        composeRule.onNodeWithText("Опсег страница није исправан.").assertExists()
        composeRule.onNodeWithText("Прихвати и увези").assertExists()
        assertFalse(accepted)
    }

    private fun preview(): PdfImportPreview {
        val fingerprint = "a".repeat(64)
        val locator = PageLocator(fingerprint, 1)
        val block = PdfTextBlock(
            DocumentBlock(0, NarrationBlockType.PARAGRAPH, "Преглед текста", locator.block(0)),
            NormalizedRect(0f, 0f, 1f, 0.1f),
        )
        val source = StagedPdfSource("ui", "content://pdf", fingerprint, File("/tmp/ui.pdf"), 1)
        return PdfImportPreview(
            stagedSource = source,
            inspection = PdfImportInspection(
                pageCount = 3,
                range = PageRange(1, 1),
                pages = listOf(PdfPage(1, "Преглед текста", listOf(block), locator)),
                warnings = emptyList(),
                blockingDiagnostics = emptyList(),
                provenance = ImportProvenance(fingerprint, source.sourceUri, source.sourceFile.path, source.projectId),
            ),
        )
    }

    private fun serbianContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(java.util.Locale("sr"))
        })
    }
}
