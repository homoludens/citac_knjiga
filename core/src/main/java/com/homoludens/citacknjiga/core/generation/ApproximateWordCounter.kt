package com.homoludens.citacknjiga.core.generation

/** Counts contiguous Unicode letters, numbers, and combining marks as approximate words. */
public object ApproximateWordCounter {
    public fun count(text: String): Int {
        var words = 0
        var inWord = false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val wordCharacter = Character.isLetterOrDigit(codePoint) || (inWord && isMark(codePoint))
            if (wordCharacter && !inWord) words++
            inWord = wordCharacter
            offset += Character.charCount(codePoint)
        }
        return words
    }

    private fun isMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
