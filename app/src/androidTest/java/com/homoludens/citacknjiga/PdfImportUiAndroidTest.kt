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
import com.homoludens.citacknjiga.document.pdf.PdfAcceptanceResult
import com.homoludens.citacknjiga.document.pdf.PdfDocumentProjector
import com.homoludens.citacknjiga.document.pdf.PdfImportInspection
import com.homoludens.citacknjiga.document.pdf.PdfImportPreview
import com.homoludens.citacknjiga.document.pdf.PdfPage
import com.homoludens.citacknjiga.document.pdf.PdfTextBlock
import com.homoludens.citacknjiga.document.pdf.PageRange
import com.homoludens.citacknjiga.document.pdf.StagedPdfSource
import com.homoludens.citacknjiga.document.pdf.ImportedPdfSource
import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    public fun loadingStateExposesCancellationAction() {
        var canceled = false
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    PdfImportPreviewContent(
                        state = PdfImportUiState.Loading,
                        enabled = true,
                        onSelect = {},
                        onPreview = { _, _ -> },
                        onAccept = {},
                        onCancel = {},
                        onCancelLoading = { canceled = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Припрема PDF прегледа. Можете сачекати или отказати.").assertExists()
        composeRule.onNodeWithText("Откажи PDF увоз").performClick()
        assertTrue(canceled)
    }

    @Test
    public fun errorStateShowsSafePdfMessage() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    PdfImportPreviewContent(
                        state = PdfImportUiState.Error(
                            ImportDiagnostic(
                                ImportDiagnosticCode.PROTECTED_PDF,
                                message = "source uri must not be shown",
                                action = "retry",
                            ),
                        ),
                        enabled = true,
                        onSelect = {},
                        onPreview = { _, _ -> },
                        onAccept = {},
                        onCancel = {},
                        onCancelLoading = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Заштићени PDF није подржан. Изаберите незаштићену датотеку.").assertExists()
        composeRule.onNodeWithText("source uri must not be shown").assertDoesNotExist()
    }

    @Test
    public fun acceptedStateShowsTextWithoutGenerationAction() {
        val acceptedPreview = preview()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    PdfImportPreviewContent(
                        state = PdfImportUiState.Accepted(
                            PdfAcceptanceResult.Published(
                                ImportedPdfSource(
                                    projectId = acceptedPreview.stagedSource.projectId,
                                    sourceUri = acceptedPreview.stagedSource.sourceUri,
                                    fingerprint = acceptedPreview.stagedSource.fingerprint,
                                    sourceFile = acceptedPreview.stagedSource.sourceFile,
                                    sizeBytes = acceptedPreview.stagedSource.sizeBytes,
                                ),
                                PdfDocumentProjector.toIr(acceptedPreview),
                            ),
                        ),
                        enabled = true,
                        onSelect = {},
                        onPreview = { _, _ -> },
                        onAccept = {},
                        onCancel = {},
                        onCancelLoading = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("PDF увоз је прихваћен и сачуван. Генерисање звука није покренуто.").assertExists()
        composeRule.onNodeWithText("Преглед текста").assertExists()
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
