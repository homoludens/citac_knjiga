package com.homoludens.citacknjiga

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.res.Configuration
import com.homoludens.citacknjiga.diagnostics.DiagnosticsAboutScreen
import com.homoludens.citacknjiga.diagnostics.DiagnosticsAboutState
import com.homoludens.citacknjiga.diagnostics.DiagnosticsStatus
import com.homoludens.citacknjiga.tts.onnx.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

public class DiagnosticsAboutUiTest {
    @get:Rule
    public val composeRule = createComposeRule()

    @Test
    public fun displaysVerificationCapabilityVersionLicenseStoragePolicyAndEvidenceFields() {
        val state = DiagnosticsAboutState(
            model = com.homoludens.citacknjiga.diagnostics.DiagnosticsModelState(
                status = DiagnosticsStatus.VERIFIED,
                packageId = "kokoro-serbian-dragana",
                packageVersion = "1.0.0",
                packageSha256 = "a".repeat(64),
                modelSha256 = "b".repeat(64),
                voiceSha256 = "c".repeat(64),
                preprocessingVersion = "kokoro-sr-ca5590d9/contract-1",
                pronunciationVersion = "espeak-ng-1.52.0-sr",
            ),
            device = com.homoludens.citacknjiga.diagnostics.DiagnosticsDeviceState(
                manufacturer = "Test",
                model = "Reader",
                device = "reader",
                apiLevel = 33,
                abis = listOf("arm64-v8a"),
                processorCount = 8,
                supportsTarget = true,
                runtime = com.homoludens.citacknjiga.tts.onnx.DeviceParityRuntimeIdentity(),
            ),
            app = com.homoludens.citacknjiga.diagnostics.DiagnosticsAppState("app", "0.1.0", 1, 2, "standard"),
            attributions = listOf(
                com.homoludens.citacknjiga.diagnostics.DiagnosticsAttributionReference(
                    "source", "Serbian dataset", "CC BY 4.0", "https://example.com/source",
                ),
            ),
            storage = com.homoludens.citacknjiga.diagnostics.DiagnosticsStorageState(
                DiagnosticsStatus.AVAILABLE, 10L, 20L, 30L,
            ),
            evidence = emptyList(),
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(state = state, onExport = {})
                }
            }
        }

        assertEquals(1, composeRule.onAllNodesWithText("проверено", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("kokoro-serbian-dragana", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("arm64-v8a", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("com.microsoft.onnxruntime:onnxruntime-android:1.29.0", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("CC BY 4.0", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("kokoro-sr-ca5590d9/contract-1", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("доступно", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Обрада текста, модел и звук остају на уређају.", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Извези редиговану дијагностику").assert(hasClickAction())
    }

    @Test
    public fun missingDataShowsActionAndExportButtonIsAccessible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(
                        state = DiagnosticsAboutState.missing(),
                        onExport = {},
                    )
                }
            }
        }

        assertEquals(1, composeRule.onAllNodesWithText("Нема провереног модела. Увезите компатибилан пакет пре генерисања.", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Подаци о могућностима уређаја нису доступни. Проверите API и ABI пре генерисања.", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Референце атрибуције нису доступне. Не објављујте извоз док се извори не провере.", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Подаци о меморији нису доступни. Проверите слободан простор пре генерисања.", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Извези редиговану дијагностику").assert(hasClickAction())
    }

    @Test
    public fun modelImportActionIsVisible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(
                        state = DiagnosticsAboutState.missing(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Увези пакет модела").assert(hasClickAction())
    }

    @Test
    public fun busyModelImportActionIsDisabled() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(
                        state = DiagnosticsAboutState.missing(),
                        importEnabled = false,
                        importBusy = true,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Увези пакет модела").assertIsNotEnabled()
    }

    @Test
    public fun separateKokoroAndVitsDownloadActionsExposeTheirStatuses() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(
                        state = DiagnosticsAboutState.missing(),
                    )
                }
            }
        }

        assertEquals(1, composeRule.onAllNodesWithText("Kokoro модел", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Serbian VITS модел", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("Није инсталиран", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("Преузми модел", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    public fun engineSelectionIsAvailableInDiagnostics() {
        var selected = TtsEngine.KOKORO
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(
                        state = DiagnosticsAboutState.missing(),
                        selectedEngine = selected,
                        availableEngines = listOf(TtsEngine.KOKORO, TtsEngine.VITS),
                        onEngineSelected = { selected = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("VITS (Dragana)").performClick()
        assertEquals(TtsEngine.VITS, selected)
    }

    @Test
    public fun unavailableEngineRemainsVisibleButDisabled() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(state = DiagnosticsAboutState.missing())
                }
            }
        }

        composeRule.onNodeWithText("VITS (Dragana)").assertIsNotEnabled()
        composeRule.onNodeWithText("VITS је доступан тек када су пакет и подршка за извршавање доступни.")
    }

    @Test
    public fun unavailableModelReleaseActionIsShownButDisabled() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(
                        state = DiagnosticsAboutState.missing(),
                        releaseConfigured = true,
                        releaseAvailable = false,
                        releaseMessage = "Овај извор није доступан.",
                    )
                }
            }
        }

        composeRule.onNodeWithText("Преузми пакет модела").assertIsNotEnabled()
        composeRule.onNodeWithText("Овај извор није доступан.").assert(hasText("Овај извор није доступан."))
    }

    @Test
    public fun availableModelReleaseActionIsExposedAsExternalAction() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    DiagnosticsAboutScreen(
                        state = DiagnosticsAboutState.missing(),
                        releaseConfigured = true,
                        releaseAvailable = true,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Преузми пакет модела").assert(hasClickAction())
    }

    private fun serbianContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(java.util.Locale("sr"))
        })
    }
}
