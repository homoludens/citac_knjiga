package com.homoludens.citacknjiga.tts.onnx

import java.text.Normalizer
import java.util.Locale

public class VitsFrontendException(message: String) : IllegalArgumentException(message)

public data class VitsFrontendOutput(
    val normalizedText: String,
    val tokenIds: List<Int>,
)

/** Small model-owned text adapter; it never invokes Sherpa's Kokoro/Piper frontend. */
public class VitsSerbianFrontend(
    private val vocabulary: Map<Int, Int>,
    private val blankId: Int,
    private val latinPolicy: String = "transliterate-case-aware-v1",
) {
    public fun process(text: String): VitsFrontendOutput {
        if (latinPolicy != LATIN_POLICY) throw VitsFrontendException("Unsupported Latin policy")
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace('\u00a0', ' ')
            .replace(Regex("[\u2010-\u2015]"), "-")
            .replace(Regex("[\u2018\u2019\u201a\u201b\u2032\u2035\u00ab\u00bb]"), "'")
            .replace('\u2026', '.')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) throw VitsFrontendException("VITS input is empty")
        normalized = transliterate(normalized)
        normalized = stripForeignDiacritics(normalized)
        normalized = transliterate(normalized)
        normalized = normalized.lowercase(Locale.ROOT)
        ABBREVIATIONS.forEach { (short, expanded) -> normalized = normalized.replace(short, expanded) }
        if (normalized.any { it.isDigit() }) throw VitsFrontendException("Numbers are unsupported by the VITS model")
        val unsupported = normalized.filter { it.code !in vocabulary }
        if (unsupported.isNotEmpty()) {
            throw VitsFrontendException("Unsupported Serbian input: ${unsupported.toSet().joinToString()}")
        }
        val ids = normalized.map { codePoint ->
            vocabulary[codePoint.code] ?: throw VitsFrontendException("Model vocabulary lacks U+${codePoint.code.toString(16)}")
        }
        return VitsFrontendOutput(normalized, buildList(ids.size * 2 + 1) {
            add(blankId)
            ids.forEach {
                add(it)
                add(blankId)
            }
        })
    }

    private fun transliterate(value: String): String = buildString(value.length) {
        value.forEach { append(LATIN_TO_CYRILLIC[it] ?: it) }
    }

    private fun stripForeignDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .filter { character ->
                Character.getType(character) != Character.NON_SPACING_MARK.toInt() &&
                    Character.getType(character) != Character.COMBINING_SPACING_MARK.toInt() &&
                    Character.getType(character) != Character.ENCLOSING_MARK.toInt()
            }

    private companion object {
        const val LATIN_POLICY = "transliterate-case-aware-v1"
        val ABBREVIATIONS = linkedMapOf("нпр." to "на пример", "тј." to "то јест", "итд." to "и тако даље", "др." to "доктор")
        val LATIN_TO_CYRILLIC = mapOf(
            'A' to 'А', 'B' to 'Б', 'C' to 'Ц', 'Č' to 'Ч', 'Ć' to 'Ћ', 'D' to 'Д', 'Đ' to 'Ђ', 'E' to 'Е',
            'F' to 'Ф', 'G' to 'Г', 'H' to 'Х', 'I' to 'И', 'J' to 'Ј', 'K' to 'К', 'L' to 'Л', 'M' to 'М',
            'N' to 'Н', 'O' to 'О', 'P' to 'П', 'R' to 'Р', 'S' to 'С', 'Š' to 'Ш', 'T' to 'Т', 'U' to 'У',
            'V' to 'В', 'Z' to 'З', 'Ž' to 'Ж', 'a' to 'а', 'b' to 'б', 'c' to 'ц', 'č' to 'ч', 'ć' to 'ћ',
            'd' to 'д', 'đ' to 'ђ', 'e' to 'е', 'f' to 'ф', 'g' to 'г', 'h' to 'х', 'i' to 'и', 'j' to 'ј',
            'k' to 'к', 'l' to 'л', 'm' to 'м', 'n' to 'н', 'o' to 'о', 'p' to 'п', 'r' to 'р', 's' to 'с',
            'š' to 'ш', 't' to 'т', 'u' to 'у', 'v' to 'в', 'z' to 'з', 'ž' to 'ж',
        )
    }
}
