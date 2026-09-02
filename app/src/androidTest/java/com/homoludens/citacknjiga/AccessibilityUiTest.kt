package com.homoludens.citacknjiga

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.GenerationRunStatus
import com.homoludens.citacknjiga.library.ChapterDisplay
import com.homoludens.citacknjiga.library.GenerationAction
import com.homoludens.citacknjiga.library.LibraryBookDisplay
import com.homoludens.citacknjiga.library.LibraryScreen
import com.homoludens.citacknjiga.library.LibraryViewState
import com.homoludens.citacknjiga.library.ProgressDisplay
import com.homoludens.citacknjiga.library.BookDetailScreen
import com.homoludens.citacknjiga.library.RegenerationFeedback
import com.homoludens.citacknjiga.library.RegenerationResultStatus
import com.homoludens.citacknjiga.core.generation.GenerationScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

public class AccessibilityUiTest {
    @get:Rule
    public val composeRule = createComposeRule()

    @Test
    public fun generationProgressAnnouncesStateAndExposesActions() {
        val actions = mutableListOf<Pair<String, GenerationAction>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    LibraryScreen(
                        state = LibraryViewState(listOf(runningBook())),
                        onBookClick = {},
                        onGenerationAction = { runId, action -> actions += runId to action },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Генерисање: 1/2 делова", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Генерисање је у току. Можете паузирати или отказати.",
                ),
            )
        composeRule.onNodeWithText("Паузирај").assert(hasClickAction()).performClick()
        composeRule.onNodeWithText("Откажи генерисање").assert(hasClickAction()).performClick()
        assertEquals(listOf("run-1" to GenerationAction.PAUSE, "run-1" to GenerationAction.CANCEL), actions)
    }

    @Test
    public fun failureTextDoesNotExposeStoredPathAndOffersRetry() {
        val failed = runningBook().copy(
            generationStatus = GenerationRunStatus.FAILED,
            failures = listOf("STORAGE: /home/user/private-book/secret.epub"),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme { LibraryScreen(LibraryViewState(listOf(failed)), onBookClick = {}) }
            }
        }

        assertEquals(0, composeRule.onAllNodesWithText("/home/user/private-book/secret.epub").fetchSemanticsNodes().size)
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Нема довољно простора за звук. Ослободите простор и покушајте поново.")
                .fetchSemanticsNodes()
                .size,
        )
        composeRule.onNodeWithText("Понови генерисање").assert(hasClickAction())
    }

    @Test
    public fun playerActionsHaveMeaningfulDescriptionsAndTouchSemantics() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    com.homoludens.citacknjiga.player.AudiobookPlayerControls(
                        state = com.homoludens.citacknjiga.playback.export.PlayerControlState(durationMs = 10_000L),
                        onPlayPause = {},
                        onSeek = {},
                        onPreviousChapter = { true },
                        onNextChapter = { true },
                        onJumpBackward = {},
                        onJumpForward = {},
                        onSelectChapter = { true },
                        onSetJumps = { _, _ -> },
                        onSetSpeed = { true },
                    )
                }
            }
        }

        assertEquals(1, composeRule.onAllNodesWithContentDescription("Позиција у поглављу").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Премотајте назад за 15s").assert(hasClickAction())
        composeRule.onNodeWithText("Пусти").assert(hasClickAction())
    }

    @Test
    public fun largeFontScaleKeepsLibraryStructureReachable() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides serbianContext(),
                LocalDensity provides androidx.compose.ui.unit.Density(1f, fontScale = 2f),
            ) {
                MaterialTheme {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LibraryScreen(state = LibraryViewState(listOf(runningBook())), onBookClick = {})
                    }
                }
            }
        }

        assertEquals(1, composeRule.onAllNodesWithText("Библиотека").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Поглавља: 1/1 спремно").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Генерисање: 1/2 делова").fetchSemanticsNodes().size)
    }

    @Test
    public fun deleteActionRequiresConfirmation() {
        val deleted = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    LibraryScreen(
                        state = LibraryViewState(listOf(runningBook())),
                        onBookClick = {},
                        onDeleteBook = { deleted += it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("delete-book-book-1").performClick()
        assertEquals(0, deleted.size)
        composeRule.onNodeWithTag("confirm-delete-book-book-1").performClick()
        assertEquals(listOf("book-1"), deleted)
    }

    @Test
    public fun regenerationCancelDoesNotQueueAndBookActionUsesCompleteScope() {
        val scopes = mutableListOf<GenerationScope>()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    BookDetailScreen(
                        book = runningBook(),
                        onRegenerate = { _, scope -> scopes += scope },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("regenerate-chapter-chapter-1").performClick()
        composeRule.onNodeWithTag("regenerate-chapter-warning-chapter-1").assertExists()
        composeRule.onNodeWithTag("cancel-regenerate-chapter-chapter-1").performClick()
        assertEquals(emptyList<GenerationScope>(), scopes)

        composeRule.onNodeWithTag("regenerate-book-book-1").performClick()
        composeRule.onNodeWithTag("confirm-regenerate-book-book-1").performClick()
        assertEquals(listOf(GenerationScope.CompleteBook), scopes)
    }

    @Test
    public fun regenerationFeedbackExposesSuccessFailureAndRetry() {
        val retried = mutableListOf<GenerationScope>()
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides serbianContext()) {
                MaterialTheme {
                    BookDetailScreen(
                        book = runningBook(),
                        regenerationFeedback = RegenerationFeedback(
                            projectId = "book-1",
                            scope = GenerationScope.Chapter("chapter-1"),
                            status = RegenerationResultStatus.FAILED,
                        ),
                        onRegenerate = { _, scope -> retried += scope },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Поновно генерисање није успело. Изворни текст и остала поглавља нису промењени. Покушајте поново.").assertExists()
        composeRule.onNodeWithTag("retry-regeneration-book-1").performClick()
        assertEquals(listOf(GenerationScope.Chapter("chapter-1")), retried)
    }

    @Test
    public fun englishResourcesProvideFallbackForImportantStates() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        }
        val english = base.createConfigurationContext(configuration).resources

        assertEquals("Serbian text to speech", english.getString(R.string.start_title))
        assertEquals("Generation is running. You can pause or cancel it.", english.getString(R.string.generation_running))
        assertEquals("The export destination is no longer available. Choose another folder.", english.getString(R.string.export_destination_unavailable))
    }

    private fun runningBook(): LibraryBookDisplay = LibraryBookDisplay(
        project = BookProjectEntity(
            id = "book-1",
            title = "Књига",
            author = "Аутор",
            sourceUri = "content://book",
            sourceFingerprint = "a".repeat(64),
            status = BookProjectStatus.GENERATING,
            createdAt = 1,
            updatedAt = 1,
        ),
        chapters = listOf(
            ChapterDisplay(
                chapter = ChapterEntity(
                    id = "chapter-1",
                    bookProjectId = "book-1",
                    ordinal = 0,
                    title = "Поглавље",
                    status = ChapterStatus.GENERATING,
                    createdAt = 1,
                    updatedAt = 1,
                ),
                progress = ProgressDisplay(1, 1),
                durationMs = 1_000,
                storageBytes = 1,
            ),
        ),
        generationProgress = ProgressDisplay(1, 2),
        readyChapterCount = 1,
        storageBytes = 1,
        listeningProgress = null,
        failures = emptyList(),
        generationRunId = "run-1",
        generationStatus = GenerationRunStatus.RUNNING,
    )

    private fun serbianContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(java.util.Locale("sr"))
        })
    }
}
