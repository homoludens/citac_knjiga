package com.homoludens.citacknjiga

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

public class MainNavigationUiTest {
    @get:Rule
    public val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    public fun bottomNavigationExposesPrimaryScreensAndSettingsOwnsDiagnostics() {
        val context = composeRule.activity

        composeRule.onNodeWithTag("nav-import").performClick()
        composeRule.onNodeWithText(context.getString(R.string.import_book_title)).assertIsDisplayed()

        composeRule.onNodeWithTag("nav-synthesize").performClick()
        composeRule.onNodeWithText(context.getString(R.string.engine_kokoro)).assertIsDisplayed()

        composeRule.onNodeWithTag("nav-player").performClick()
        composeRule.onNodeWithText(context.getString(R.string.no_active_book)).assertIsDisplayed()

        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.onNodeWithText(context.getString(R.string.settings_speech_voice)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.diagnostics_about_title)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.diagnostics_about_title)).assertExists()
    }
}
