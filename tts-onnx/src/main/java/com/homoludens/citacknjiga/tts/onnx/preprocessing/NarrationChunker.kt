package com.homoludens.citacknjiga.tts.onnx.preprocessing

import com.homoludens.citacknjiga.core.database.NarrationBlockType

public enum class NarrationBoundary {
    BLOCK,
    SENTENCE,
    CLAUSE,
    WORD,
    GRAPHEME,
}

/** Pause values are metadata only; audio insertion belongs to the generation layer. */
public data class NarrationChunkingConfig(
    public val maxPhonemeSymbols: Int? = null,
    public val sentencePauseMs: Long = 250,
    public val clausePauseMs: Long = 100,
    public val fallbackPauseMs: Long = 0,
    public val paragraphPauseMs: Long = 500,
    public val headingPauseMs: Long = 500,
    public val listItemPauseMs: Long = 250,
    public val quotePauseMs: Long = 500,
    public val poetryPauseMs: Long = 250,
    public val captionPauseMs: Long = 350,
    public val notePauseMs: Long = 500,
    public val sceneBreakPauseMs: Long = 750,
) {
    init {
        require(sentencePauseMs >= 0)
        require(clausePauseMs >= 0)
        require(fallbackPauseMs >= 0)
        require(paragraphPauseMs >= 0)
        require(headingPauseMs >= 0)
        require(listItemPauseMs >= 0)
        require(quotePauseMs >= 0)
        require(poetryPauseMs >= 0)
        require(captionPauseMs >= 0)
        require(notePauseMs >= 0)
        require(sceneBreakPauseMs >= 0)
    }

    internal fun blockPause(type: NarrationBlockType): Long = when (type) {
        NarrationBlockType.HEADING -> headingPauseMs
        NarrationBlockType.PARAGRAPH -> paragraphPauseMs
        NarrationBlockType.LIST_ITEM -> listItemPauseMs
        NarrationBlockType.QUOTE -> quotePauseMs
        NarrationBlockType.POETRY -> poetryPauseMs
        NarrationBlockType.CAPTION -> captionPauseMs
        NarrationBlockType.NOTE -> notePauseMs
        NarrationBlockType.SCENE_BREAK -> sceneBreakPauseMs
        NarrationBlockType.SKIPPED -> 0
    }
}

public data class NarrationChunk(
    public val text: String,
    /** Half-open Unicode code-point range relative to the source block. */
    public val sourceRange: TextRange,
    /** Half-open Unicode code-point ranges relative to the source block. */
    public val protectedSpans: List<TextRange>,
    public val boundary: NarrationBoundary,
    public val pauseAfterMs: Long,
    public val phonemeCount: Int,
)

public class NarrationChunkingException(message: String) : IllegalArgumentException(message)

/**
 * Splits one parsed narration block without asking the model to process an unsafe input.
 *
 * The estimator must return the normalized IPA code-point count for the supplied text. In
 * production it is supplied by [SerbianPreprocessor], rather than approximated from characters.
 */
public class SerbianNarrationChunker(
    private val phonemeCount: (String) -> Int,
    private val modelLimits: SerbianModelLimits = SerbianModelLimits(),
    private val config: NarrationChunkingConfig = NarrationChunkingConfig(),
) {
    public constructor(
        preprocessor: SerbianPreprocessor,
        modelLimits: SerbianModelLimits? = null,
        config: NarrationChunkingConfig = NarrationChunkingConfig(),
    ) : this(
        phonemeCount = { text ->
            val phonemes = preprocessor.process(text).phonemes
            phonemes.codePointCount(0, phonemes.length)
        },
        modelLimits = modelLimits ?: preprocessor.modelLimits,
        config = config,
    )

    private val maxPhonemeSymbols: Int = (config.maxPhonemeSymbols ?: modelLimits.operationalPhonemeSymbols).also {
        require(it in 1..modelLimits.hardPhonemeSymbols) {
            "Chunk limit must be between 1 and the verified hard model limit"
        }
    }

    public fun chunk(blockType: NarrationBlockType, sourceText: String): List<NarrationChunk> {
        if (blockType == NarrationBlockType.SKIPPED || sourceText.isBlank()) return emptyList()

        val codePoints = sourceText.codePoints().toArray()
        val contentStart = skipWhitespaceForward(codePoints, 0)
        val contentEnd = skipWhitespaceBackward(codePoints, codePoints.size)
        if (contentStart >= contentEnd) return emptyList()

        val protectedSpans = protectedSpans(sourceText, codePoints)
        val result = mutableListOf<NarrationChunk>()
        var start = contentStart
        while (start < contentEnd) {
            val fitting = boundaries(codePoints, start, contentEnd, protectedSpans).asSequence()
                .filter { it.end > start && it.end <= contentEnd }
                .mapNotNull { candidate ->
                    val text = sourceText.slice(start until candidate.end, codePoints)
                    val count = phonemeCount(text)
                    if (count <= maxPhonemeSymbols) candidate.copy(phonemeCount = count) else null
                }
                .toList()
            val selected = fitting
                .minWithOrNull(compareBy<Candidate> { it.priority }.thenByDescending { it.end })
                ?: throw NarrationChunkingException(
                    "No safe chunk boundary fits ${maxPhonemeSymbols} phonemes at source code point $start",
                )
            val nextStart = skipWhitespaceForward(codePoints, selected.end)
            val range = TextRange(start, selected.end)
            val chunkText = sourceText.slice(start until selected.end, codePoints)
            val chunkSpans = protectedSpans.filter { it.start >= range.start && it.end <= range.end }
            result += NarrationChunk(
                text = chunkText,
                sourceRange = range,
                protectedSpans = chunkSpans,
                boundary = selected.kind,
                pauseAfterMs = pauseAfter(selected.kind, blockType, selected.end == contentEnd),
                phonemeCount = selected.phonemeCount,
            )
            start = nextStart
        }
        return result
    }

    private fun pauseAfter(boundary: NarrationBoundary, type: NarrationBlockType, isBlockEnd: Boolean): Long = when {
        isBlockEnd -> config.blockPause(type)
        boundary == NarrationBoundary.SENTENCE -> config.sentencePauseMs
        boundary == NarrationBoundary.CLAUSE -> config.clausePauseMs
        else -> config.fallbackPauseMs
    }

    private fun boundaries(
        codePoints: IntArray,
        start: Int,
        end: Int,
        protectedSpans: List<TextRange>,
    ): List<Candidate> {
        val result = mutableListOf<Candidate>()
        for (boundaryEnd in (start + 1)..end) {
            if (!isSafeBoundary(boundaryEnd, protectedSpans)) continue
            if (Character.isWhitespace(codePoints[boundaryEnd - 1])) continue
            val kind = boundaryKind(codePoints, boundaryEnd, end, protectedSpans)
            result += Candidate(
                end = boundaryEnd,
                kind = kind,
                priority = kind.priority,
                phonemeCount = 0,
            )
        }
        return result
    }

    private fun boundaryKind(
        codePoints: IntArray,
        end: Int,
        contentEnd: Int,
        protectedSpans: List<TextRange>,
    ): NarrationBoundary {
        if (end == contentEnd) return NarrationBoundary.BLOCK
        val sentenceEnd = punctuationBefore(codePoints, end, SENTENCE_CLOSERS)
        if (sentenceEnd != null && !isProtected(sentenceEnd, protectedSpans)) return NarrationBoundary.SENTENCE
        val codePoint = codePoints[end - 1]
        if (codePoint in CLAUSE_PUNCTUATION && !isProtected(end - 1, protectedSpans)) {
            return NarrationBoundary.CLAUSE
        }
        if (end < contentEnd && Character.isWhitespace(codePoints[end])) return NarrationBoundary.WORD
        return NarrationBoundary.GRAPHEME
    }

    private fun protectedSpans(sourceText: String, codePoints: IntArray): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        PROTECTED_PATTERNS.forEachIndexed { patternIndex, pattern ->
            pattern.findAll(sourceText).forEach { match ->
                val start = sourceText.codePointCount(0, match.range.first)
                var end = sourceText.codePointCount(0, match.range.last + 1)
                if (patternIndex == URL_PATTERN_INDEX) {
                    while (end > start && codePoints[end - 1] in TRAILING_URL_PUNCTUATION) end--
                }
                if (start < end) ranges += TextRange(start, end)
            }
        }
        addGraphemeAndDigraphSpans(ranges, codePoints)
        return mergeRanges(ranges)
    }

    private fun addGraphemeAndDigraphSpans(ranges: MutableList<TextRange>, codePoints: IntArray) {
        var index = 0
        while (index < codePoints.size) {
            val next = index + 1
            if (next < codePoints.size && isSerbianDigraph(codePoints[index], codePoints[next])) {
                ranges += TextRange(index, next + 1)
                index = next + 1
                continue
            }
            var clusterEnd = next
            while (clusterEnd < codePoints.size && isCombining(codePoints[clusterEnd])) clusterEnd++
            if (clusterEnd > next) ranges += TextRange(index, clusterEnd)
            index = clusterEnd
        }
    }

    private fun mergeRanges(ranges: List<TextRange>): List<TextRange> = ranges
        .sortedWith(compareBy<TextRange> { it.start }.thenBy { it.end })
        .fold(mutableListOf<TextRange>()) { merged, range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.start <= previous.end) {
                merged[merged.lastIndex] = TextRange(previous.start, maxOf(previous.end, range.end))
            } else {
                merged += range
            }
            merged
        }

    private fun isSafeBoundary(end: Int, protectedSpans: List<TextRange>): Boolean =
        protectedSpans.none { end > it.start && end < it.end }

    private fun isProtected(index: Int, protectedSpans: List<TextRange>): Boolean =
        protectedSpans.any { index in it.start until it.end }

    private fun punctuationBefore(codePoints: IntArray, end: Int, closers: Set<Int>): Int? {
        var index = end - 1
        while (index >= 0 && codePoints[index] in closers) index--
        return index.takeIf { it >= 0 && codePoints[it] in SENTENCE_PUNCTUATION }
    }

    private fun skipWhitespaceForward(codePoints: IntArray, start: Int): Int {
        var index = start
        while (index < codePoints.size && Character.isWhitespace(codePoints[index])) index++
        return index
    }

    private fun skipWhitespaceBackward(codePoints: IntArray, end: Int): Int {
        var index = end
        while (index > 0 && Character.isWhitespace(codePoints[index - 1])) index--
        return index
    }

    private fun String.slice(range: IntRange, codePoints: IntArray): String {
        val start = offsetByCodePoints(0, range.first)
        val end = offsetByCodePoints(0, range.last + 1)
        return substring(start, end)
    }

    private data class Candidate(
        val end: Int,
        val kind: NarrationBoundary,
        val priority: Int,
        val phonemeCount: Int,
    )

    private companion object {
        val SENTENCE_PUNCTUATION = setOf('.', '!', '?', '\u2026').map(Char::code).toSet()
        val SENTENCE_CLOSERS = setOf('"', '\'', '\u00bb', '\u201d', '\u2019', ')', ']', '}').map(Char::code).toSet()
        val CLAUSE_PUNCTUATION = setOf(',', ';', ':', '\u2013', '\u2014').map(Char::code).toSet()
        val TRAILING_URL_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?').map(Char::code).toSet()
        val PROTECTED_PATTERNS = listOf(
            Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\\\"']+"),
            Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"),
            Regex("\\[(?:\\d+)(?:\\s*[,;]\\s*\\d+)*\\]|\\(\\s*(?:стр\\.?\\s*)?\\d+(?:\\s*[,;]\\s*\\d+)*\\s*\\)"),
            Regex("(?<![\\p{L}\\p{N}])\\d+(?:[.,:]\\d+)+(?![\\p{L}\\p{N}])"),
            Regex("(?<![\\p{L}\\p{N}])(?:др|гђа|гђ|нпр|тј|итд|одн|сл|бр|стр|проф|акад|ул|евр|млн|млрд|тзв|г|dr|gđa|gđ|npr|tj|itd|odn|sl|br|str|prof|akad|ul|evr|mln|mlrd|tzv)\\.(?:\\p{L}{1,4}\\.)*", RegexOption.IGNORE_CASE),
            Regex("(?<![\\p{L}\\p{N}])(?:\\p{L}\\.){2,}"),
        )
        const val URL_PATTERN_INDEX = 0

        val NarrationBoundary.priority: Int
            get() = when (this) {
                NarrationBoundary.BLOCK -> 0
                NarrationBoundary.SENTENCE -> 1
                NarrationBoundary.CLAUSE -> 2
                NarrationBoundary.WORD -> 3
                NarrationBoundary.GRAPHEME -> 4
            }

        fun isCombining(codePoint: Int): Boolean {
            val type = Character.getType(codePoint)
            return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                codePoint == 0x200d ||
                codePoint in 0xfe00..0xfe0f
        }

        fun isSerbianDigraph(first: Int, second: Int): Boolean {
            val pair = StringBuilder().appendCodePoint(first).appendCodePoint(second).toString().lowercase()
            return pair == "lj" || pair == "nj" || pair == "dž"
        }
    }
}
