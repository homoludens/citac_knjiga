package com.homoludens.citacknjiga.core.generation

import org.junit.Assert.assertEquals
import org.junit.Test

public class ApproximateWordCounterTest {
    @Test
    public fun countsSerbianUnicodeWordsAcrossPunctuationAndWhitespace() {
        assertEquals(
            7,
            ApproximateWordCounter.count("Čitam, Љубав — њежно; у\u00a0Београду… 2026! cafe\u0301"),
        )
    }

    @Test
    public fun ignoresPunctuationOnlyAndWhitespaceOnlyText() {
        assertEquals(0, ApproximateWordCounter.count(" \t\n—…?! "))
    }
}
