package com.homoludens.citacknjiga.tts.onnx.preprocessing

import com.homoludens.citacknjiga.core.database.NarrationBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class NarrationChunkerTest {
    @Test
    public fun prefersSentenceBoundariesAndRetainsPunctuation() {
        val text = "Прва реченица. Друга реченица!"
        val chunks = chunker(20).chunk(NarrationBlockType.PARAGRAPH, text)

        assertEquals(listOf("Прва реченица.", "Друга реченица!"), chunks.map { it.text })
        assertEquals(listOf(NarrationBoundary.SENTENCE, NarrationBoundary.BLOCK), chunks.map { it.boundary })
        assertEquals(listOf(TextRange(0, 14), TextRange(15, 30)), chunks.map { it.sourceRange })
    }

    @Test
    public fun protectsAbbreviationsDecimalsUrlsEmailsAndCitations() {
        val text = "нпр. dr. 3,14 https://primer.rs/a?x=1, email@test.rs [12, 14]. Крај."
        val chunks = chunker(28).chunk(NarrationBlockType.PARAGRAPH, text)
        val protectedText = chunks.flatMap { chunk ->
            chunk.protectedSpans.map { span -> text.sliceByCodePoints(span) }
        }

        assertTrue("protected=$protectedText", protectedText.contains("нпр."))
        assertTrue("protected=$protectedText", protectedText.contains("dr."))
        assertTrue("protected=$protectedText", protectedText.contains("3,14"))
        assertTrue("protected=$protectedText", protectedText.contains("https://primer.rs/a?x=1"))
        assertTrue("protected=$protectedText", protectedText.contains("email@test.rs"))
        assertTrue("protected=$protectedText", protectedText.contains("[12, 14]"))
        assertTrue(chunks.all { it.phonemeCount <= 28 })
        chunks.flatMap { it.protectedSpans }.forEach { span ->
            assertEquals(
                1,
                chunks.count { chunk ->
                    span.start >= chunk.sourceRange.start && span.end <= chunk.sourceRange.end
                },
            )
        }
    }

    @Test
    public fun doesNotSplitSerbianDigraphsCyrillicOrCombiningGraphemes() {
        val text = "lj nj dž љ њ ђ e\u0301"
        val chunks = chunker(2).chunk(NarrationBlockType.PARAGRAPH, text)

        assertEquals(listOf("lj", "nj", "dž", "љ", "њ", "ђ", "e\u0301"), chunks.map { it.text })
        assertTrue(chunks.all { it.phonemeCount <= 2 })
        assertTrue(chunks.flatMap { it.protectedSpans }.any { text.sliceByCodePoints(it) == "lj" })
    }

    @Test
    public fun oversizedParagraphFallsBackToWordsWithoutSentenceBoundary() {
        val text = "Ovo je veoma dug paragraf bez pogodnog kraja recenice koji mora bezbedno da se podeli"
        val chunks = chunker(18).chunk(NarrationBlockType.PARAGRAPH, text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.phonemeCount <= 18 })
        assertTrue(chunks.none { it.text.endsWith(" ") || it.text.startsWith(" ") })
    }

    @Test
    public fun pausesAreConfigurableAndBlockPauseWinsAtBlockEnd() {
        val chunks = SerbianNarrationChunker(
            phonemeCount = { it.codePointCount(0, it.length) },
            modelLimits = SerbianModelLimits(operationalPhonemeSymbols = 20, hardPhonemeSymbols = 20),
            config = NarrationChunkingConfig(
                sentencePauseMs = 11,
                paragraphPauseMs = 77,
            ),
        ).chunk(NarrationBlockType.PARAGRAPH, "Prva rečenica. Druga rečenica.")

        assertEquals(listOf(11L, 77L), chunks.map { it.pauseAfterMs })
    }

    private fun chunker(limit: Int): SerbianNarrationChunker = SerbianNarrationChunker(
        phonemeCount = { it.codePointCount(0, it.length) },
        modelLimits = SerbianModelLimits(operationalPhonemeSymbols = limit, hardPhonemeSymbols = limit),
    )

    private fun String.sliceByCodePoints(range: TextRange): String {
        val start = offsetByCodePoints(0, range.start)
        val end = offsetByCodePoints(0, range.end)
        return substring(start, end)
    }
}
