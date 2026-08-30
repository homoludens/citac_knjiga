package com.homoludens.citacknjiga.playback.export

import java.text.Normalizer
import java.util.Locale

public object ExportFileNaming {
    public fun chapterFileName(
        chapterOrdinal: Int,
        chapterTitle: String?,
        extension: String,
    ): String {
        require(chapterOrdinal >= 0) { "Chapter ordinal cannot be negative" }
        val title = sanitize(chapterTitle.orEmpty(), "chapter")
        val safeExtension = sanitizeExtension(extension)
        return "%04d-%s.%s".format(
            Locale.ROOT,
            chapterOrdinal + 1,
            title,
            safeExtension,
        )
    }

    public fun collisionSafeName(baseName: String, occupiedNames: Collection<String>): String {
        require(baseName.isNotBlank()) { "Export filename cannot be blank" }
        val occupied = occupiedNames.map(String::lowercase).toHashSet()
        if (baseName.lowercase() !in occupied) return baseName
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val extension = if (dot > 0) baseName.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = "$stem-$suffix$extension"
            if (candidate.lowercase() !in occupied) return candidate
            suffix++
        }
    }

    public fun sanitize(value: String, fallback: String = "item"): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .map { character ->
                when {
                    character.isLetterOrDigit() || character == '_' || character == '-' || character == '.' -> character
                    else -> '_'
                }
            }
            .joinToString("")
            .trim('.', '_', '-')
            .replace(Regex("_+"), "_")
        return normalized.ifBlank { fallback }
            .take(96)
            .trim('.', '_', '-')
            .ifBlank { fallback }
    }

    private fun sanitizeExtension(extension: String): String =
        sanitize(extension.lowercase(Locale.ROOT).removePrefix("."), "bin")
            .take(8)
}
