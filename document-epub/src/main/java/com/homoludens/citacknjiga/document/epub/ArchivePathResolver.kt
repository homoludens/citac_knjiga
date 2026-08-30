package com.homoludens.citacknjiga.document.epub

import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** URI-free lexical resolution used for ZIP names and publication references. */
public object ArchivePathResolver {
    public data class ExternalReference(public val scheme: String?, public val authority: String?)

    public fun normalizeEntry(raw: String): String? {
        if (raw.isEmpty() || '\u0000' in raw || '\\' in raw) return null
        if (raw.startsWith('/') || raw.startsWith("//") || raw.matches(Regex("^[A-Za-z]:.*"))) return null
        return normalizeSegments(raw.split('/'), rejectAboveRoot = true)
    }

    public fun resolve(baseEntry: String, reference: String): Result<String> {
        if ('\u0000' in reference || '\\' in reference) return Result.failure(InvalidArchivePath)
        if (reference.startsWith('/') || reference.startsWith("//") || reference.matches(Regex("^[A-Za-z]:.*"))) {
            return Result.failure(InvalidArchivePath)
        }
        val external = external(reference)
        if (external != null) return Result.failure(ExternalArchiveReference)
        val split = try {
            URI(reference)
        } catch (_: URISyntaxException) {
            return Result.failure(InvalidArchivePath)
        }
        val decoded = percentDecode(split.rawPath ?: return Result.failure(InvalidArchivePath))
            ?: return Result.failure(InvalidArchivePath)
        if ('\u0000' in decoded || '\\' in decoded) return Result.failure(InvalidArchivePath)
        val base = baseEntry.substringBeforeLast('/', "")
        return normalizeSegments((if (base.isEmpty()) decoded else "$base/$decoded").split('/'), true)
            ?.let(Result.Companion::success)
            ?: Result.failure(InvalidArchivePath)
    }

    public fun external(reference: String): ExternalReference? {
        if (reference.startsWith("//")) return ExternalReference(null, "")
        val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(reference) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val authority = runCatching { URI(reference).rawAuthority }.getOrNull()
        return ExternalReference(scheme, authority)
    }

    private fun normalizeSegments(parts: List<String>, rejectAboveRoot: Boolean): String? {
        val result = ArrayDeque<String>()
        parts.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (result.isEmpty()) {
                    if (rejectAboveRoot) return null
                } else result.removeLast()
                else -> result.addLast(part)
            }
        }
        return result.joinToString("/").takeIf(String::isNotEmpty)
    }

    private fun percentDecode(value: String): String? {
        val bytes = ByteArray(value.length)
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') {
                output.append(value[index++])
                continue
            }
            var count = 0
            while (index < value.length && value[index] == '%') {
                if (index + 2 >= value.length) return null
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return null
                bytes[count++] = ((high shl 4) or low).toByte()
                index += 3
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                output.append(decoder.decode(ByteBuffer.wrap(bytes, 0, count)))
            } catch (_: Exception) {
                return null
            }
        }
        return output.toString()
    }

    private object InvalidArchivePath : Exception("invalid archive path")
    private object ExternalArchiveReference : Exception("external archive reference")
}
