package com.homoludens.citacknjiga.document.epub

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Paths
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Strict thresholds for inspecting an untrusted EPUB without extracting it. */
public data class EpubSecurityLimits(
    /** A value at or above this threshold is rejected. */
    public val maxEntries: Int = 40,
    /** A value at or above this threshold is rejected. */
    public val maxTotalUncompressedBytes: Long = 128 * 1024,
    /** A value at or above this threshold is rejected. */
    public val maxIndividualEntryBytes: Long = 8 * 1024,
    /** A value at or above this threshold is rejected. */
    public val maxCompressionRatio: Double = 100.0,
    public val maxXmlNestingDepth: Int = 64,
    public val maxXmlBytes: Long = 64 * 1024,
) {
    init {
        require(maxEntries > 0)
        require(maxTotalUncompressedBytes > 0)
        require(maxIndividualEntryBytes > 0)
        require(maxCompressionRatio > 0.0)
        require(maxXmlNestingDepth > 0)
        require(maxXmlBytes > 0)
    }
}

public enum class EpubSecurityFailureCode {
    MALFORMED_ARCHIVE,
    INVALID_ENTRY_PATH,
    DUPLICATE_ENTRY,
    ENCRYPTED_ENTRY,
    DRM_PROTECTED_CONTENT,
    ENTRY_COUNT_EXCEEDED,
    TOTAL_EXPANSION_EXCEEDED,
    COMPRESSION_RATIO_EXCEEDED,
    INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
    MALFORMED_XML,
    XML_DTD_FORBIDDEN,
    XML_NESTING_EXCEEDED,
    EXTERNAL_RESOURCE,
    XML_HARDENING_UNAVAILABLE,
}

/** Safe-to-display failure identity; it intentionally contains no source text or URI. */
public data class EpubSecurityDiagnostic(
    public val code: EpubSecurityFailureCode,
    public val entryName: String? = null,
    public val observed: Long? = null,
    public val limit: Long? = null,
)

public sealed interface EpubSecurityValidation {
    public data object Accepted : EpubSecurityValidation

    public data class Rejected(public val diagnostic: EpubSecurityDiagnostic) : EpubSecurityValidation
}

/**
 * Inspects ZIP metadata and XML payloads in place. It never extracts an entry and never follows
 * a resource reference, so callers can run it on temporary input before publication.
 */
public class EpubSecurityValidator(
    private val limits: EpubSecurityLimits = EpubSecurityLimits(),
) {
    public fun validate(archive: File): EpubSecurityValidation = try {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            validateArchiveMetadata(entries)

            var totalRead = 0L
            entries.forEach { entry ->
                val payload = readEntry(zip, entry, totalRead)
                totalRead = addExact(totalRead, entry.size, entry.name, EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED)
                if (isXmlEntry(entry.name)) {
                    validateXml(entry.name, payload)
                }
            }
        }
        EpubSecurityValidation.Accepted
    } catch (rejection: Rejection) {
        EpubSecurityValidation.Rejected(rejection.diagnostic)
    } catch (exception: ZipException) {
        val code = if (exception.message?.contains("encrypt", ignoreCase = true) == true) {
            EpubSecurityFailureCode.ENCRYPTED_ENTRY
        } else {
            EpubSecurityFailureCode.MALFORMED_ARCHIVE
        }
        EpubSecurityValidation.Rejected(EpubSecurityDiagnostic(code))
    } catch (_: Exception) {
        EpubSecurityValidation.Rejected(
            EpubSecurityDiagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE),
        )
    }

    private fun validateArchiveMetadata(entries: List<ZipEntry>) {
        if (entries.size >= limits.maxEntries) {
            reject(
                EpubSecurityDiagnostic(
                    code = EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED,
                    observed = entries.size.toLong(),
                    limit = limits.maxEntries.toLong(),
                ),
            )
        }
        val names = HashSet<String>(entries.size)
        var total = 0L
        entries.forEach { entry ->
            validateEntryPath(entry.name)
            if (!names.add(entry.name)) {
                reject(
                    EpubSecurityDiagnostic(
                        code = EpubSecurityFailureCode.DUPLICATE_ENTRY,
                        entryName = entry.name,
                    ),
                )
            }
            if (entry.name.equals("META-INF/encryption.xml", ignoreCase = true) ||
                entry.name.equals("META-INF/rights.xml", ignoreCase = true)
            ) {
                reject(
                    EpubSecurityDiagnostic(
                        code = EpubSecurityFailureCode.DRM_PROTECTED_CONTENT,
                        entryName = entry.name,
                    ),
                )
            }
            if (entry.size < 0L || entry.compressedSize < 0L) {
                reject(EpubSecurityDiagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entry.name))
            }
            if (entry.size >= limits.maxIndividualEntryBytes) {
                reject(
                    EpubSecurityDiagnostic(
                        code = EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
                        entryName = entry.name,
                        observed = entry.size,
                        limit = limits.maxIndividualEntryBytes,
                    ),
                )
            }
            total = addExact(total, entry.size, entry.name, EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED)
            if (total >= limits.maxTotalUncompressedBytes) {
                reject(
                    EpubSecurityDiagnostic(
                        code = EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED,
                        entryName = entry.name,
                        observed = total,
                        limit = limits.maxTotalUncompressedBytes,
                    ),
                )
            }
            val ratio = if (entry.compressedSize == 0L) {
                if (entry.size == 0L) 0.0 else Double.POSITIVE_INFINITY
            } else {
                entry.size.toDouble() / entry.compressedSize.toDouble()
            }
            if (ratio >= limits.maxCompressionRatio) {
                reject(
                    EpubSecurityDiagnostic(
                        code = EpubSecurityFailureCode.COMPRESSION_RATIO_EXCEEDED,
                        entryName = entry.name,
                        observed = ratio.toLong().coerceAtMost(Long.MAX_VALUE),
                        limit = limits.maxCompressionRatio.toLong(),
                    ),
                )
            }
        }
    }

    private fun validateEntryPath(name: String) {
        val normalizedName = name.replace('\\', '/')
        val parts = normalizedName.split('/')
        val invalid = name.isEmpty() || '\u0000' in name || normalizedName.startsWith('/') ||
            normalizedName.startsWith("//") || normalizedName.matches(Regex("^[A-Za-z]:.*")) ||
            parts.any { it == ".." }
        if (invalid) {
            reject(
                EpubSecurityDiagnostic(
                    code = EpubSecurityFailureCode.INVALID_ENTRY_PATH,
                    entryName = name,
                ),
            )
        }

        try {
            val sandbox = Paths.get("epub-sandbox").toAbsolutePath().normalize()
            val candidate = sandbox.resolve(normalizedName).normalize()
            if (!candidate.startsWith(sandbox)) {
                reject(
                    EpubSecurityDiagnostic(
                        code = EpubSecurityFailureCode.INVALID_ENTRY_PATH,
                        entryName = name,
                    ),
                )
            }
        } catch (_: RuntimeException) {
            reject(
                EpubSecurityDiagnostic(
                    code = EpubSecurityFailureCode.INVALID_ENTRY_PATH,
                    entryName = name,
                ),
            )
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, totalRead: Long): ByteArray? {
        val xml = isXmlEntry(entry.name)
        val output = if (xml) {
            ByteArrayOutputStream(
                minOf(entry.size, limits.maxXmlBytes, Int.MAX_VALUE.toLong()).toInt(),
            )
        } else {
            null
        }
        var entryRead = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                entryRead = addExact(entryRead, count.toLong(), entry.name, EpubSecurityFailureCode.MALFORMED_ARCHIVE)
                if (entryRead > entry.size) {
                    reject(EpubSecurityDiagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entry.name))
                }
                if (entryRead >= limits.maxIndividualEntryBytes) {
                    reject(
                        EpubSecurityDiagnostic(
                            code = EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED,
                            entryName = entry.name,
                            observed = entryRead,
                            limit = limits.maxIndividualEntryBytes,
                        ),
                    )
                }
                if (totalRead > limits.maxTotalUncompressedBytes - entryRead) {
                    reject(
                        EpubSecurityDiagnostic(
                            code = EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED,
                            entryName = entry.name,
                            observed = limits.maxTotalUncompressedBytes,
                            limit = limits.maxTotalUncompressedBytes,
                        ),
                    )
                }
                if (output != null) {
                    if (entryRead >= limits.maxXmlBytes) {
                        reject(
                            EpubSecurityDiagnostic(
                                code = EpubSecurityFailureCode.MALFORMED_XML,
                                entryName = entry.name,
                                observed = entryRead,
                                limit = limits.maxXmlBytes,
                            ),
                        )
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
        if (entryRead != entry.size) {
            reject(
                EpubSecurityDiagnostic(
                    code = EpubSecurityFailureCode.MALFORMED_ARCHIVE,
                    entryName = entry.name,
                ),
            )
        }
        return output?.toByteArray()
    }

    private fun validateXml(entryName: String, payload: ByteArray?) {
        val xml = payload ?: return
        if (containsMarkup(xml, "<!doctype") || containsMarkup(xml, "<!entity")) {
            reject(EpubSecurityDiagnostic(EpubSecurityFailureCode.XML_DTD_FORBIDDEN, entryName))
        }

        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // DTD/entity markup is rejected above. The resolver below blocks external resource access;
        // this avoids parser features that are unavailable on Android's XML implementation.
        try {
            factory.isXIncludeAware = false
        } catch (_: UnsupportedOperationException) {
            // Android's parser does not support XInclude and keeps it disabled by default.
        }

        try {
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ ->
                throw XmlSecurityException(
                    EpubSecurityDiagnostic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName),
                )
            }
            reader.errorHandler = object : ErrorHandler {
                override fun warning(exception: SAXParseException) = throw exception

                override fun error(exception: SAXParseException) = throw exception

                override fun fatalError(exception: SAXParseException) = throw exception
            }
            reader.contentHandler = XmlLimitHandler(entryName, limits.maxXmlNestingDepth)
            reader.parse(InputSource(ByteArrayInputStream(xml)))
        } catch (rejection: Rejection) {
            throw rejection
        } catch (security: XmlSecurityException) {
            reject(security.diagnostic)
        } catch (exception: SAXException) {
            val security = findXmlSecurityException(exception)
            if (security != null) reject(security.diagnostic)
            reject(EpubSecurityDiagnostic(EpubSecurityFailureCode.MALFORMED_XML, entryName))
        } catch (_: Exception) {
            reject(EpubSecurityDiagnostic(EpubSecurityFailureCode.MALFORMED_XML, entryName))
        }
    }

    private fun isXmlEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".xml") || lower.endsWith(".opf") || lower.endsWith(".ncx") ||
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".svg")
    }

    private fun containsMarkup(bytes: ByteArray, marker: String): Boolean =
        bytes.decodeToString().lowercase().contains(marker)

    private fun addExact(current: Long, increment: Long, entryName: String, code: EpubSecurityFailureCode): Long =
        try {
            Math.addExact(current, increment)
        } catch (_: ArithmeticException) {
            reject(EpubSecurityDiagnostic(code, entryName))
        }

    private fun reject(diagnostic: EpubSecurityDiagnostic): Nothing = throw Rejection(diagnostic)

    private class Rejection(val diagnostic: EpubSecurityDiagnostic) : Exception(null, null, false, false)

    private class XmlSecurityException(val diagnostic: EpubSecurityDiagnostic) : SAXException(diagnostic.code.name)

    private class XmlLimitHandler(
        private val entryName: String,
        private val maxDepth: Int,
    ) : DefaultHandler() {
        private var depth = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
            depth++
            if (depth > maxDepth) {
                throw XmlSecurityException(
                    EpubSecurityDiagnostic(
                        code = EpubSecurityFailureCode.XML_NESTING_EXCEEDED,
                        entryName = entryName,
                        observed = depth.toLong(),
                        limit = maxDepth.toLong(),
                    ),
                )
            }
            if (attributes != null) {
                for (index in 0 until attributes.length) {
                    val name = (attributes.getLocalName(index).ifEmpty { attributes.getQName(index) }).substringAfter(':')
                    if (name in RESOURCE_ATTRIBUTES && isExternalReference(attributes.getValue(index))) {
                        throw XmlSecurityException(
                            EpubSecurityDiagnostic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName),
                        )
                    }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            depth--
        }

        override fun processingInstruction(target: String?, data: String?) {
            if (target.equals("xml-stylesheet", ignoreCase = true) && data != null &&
                isExternalReference(data.substringAfter("href=", "").trim().trim('"', '\''))
            ) {
                throw XmlSecurityException(
                    EpubSecurityDiagnostic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName),
                )
            }
        }

        private companion object {
            val RESOURCE_ATTRIBUTES = setOf("src", "href", "url", "poster", "action", "data")

            fun isExternalReference(value: String): Boolean {
                val reference = value.trim()
                return reference.startsWith("/") || reference.startsWith("\\") ||
                    reference.startsWith("//") || reference.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
            }
        }
    }

    private fun findXmlSecurityException(exception: Throwable): XmlSecurityException? {
        var current: Throwable? = exception
        while (current != null) {
            if (current is XmlSecurityException) return current
            current = current.cause
        }
        return null
    }
}
