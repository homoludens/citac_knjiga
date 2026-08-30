package com.homoludens.citacknjiga.document.epub

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.w3c.dom.Document
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/** The sole production EPUB safety profile. Limits are inclusive. */
public object EpubProductionLimits {
    public const val MAX_SOURCE_BYTES: Long = 512L * 1024 * 1024
    public const val MAX_ENTRY_COUNT: Int = 4_096
    public const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 1_073_741_824L
    public const val MAX_ENTRY_UNCOMPRESSED_BYTES: Long = 128L * 1024 * 1024
    public const val MAX_XML_TEXT_BYTES: Long = 8L * 1024 * 1024
    public const val MAX_XML_TEXT_TOTAL_BYTES: Long = 32L * 1024 * 1024
    public const val MAX_COVER_BYTES: Long = 32L * 1024 * 1024
    public const val MAX_XML_NESTING_DEPTH: Int = 64
    public const val MAX_ENTRY_RATIO_NUMERATOR: Long = 250
    public const val MAX_ARCHIVE_RATIO_NUMERATOR: Long = 100
    public const val RATIO_DENOMINATOR: Long = 1
    public const val INDIVIDUAL_RATIO_THRESHOLD_BYTES: Long = 1_048_576

    public const val maxSourceBytes: Long = MAX_SOURCE_BYTES
    public const val maxEntryCount: Int = MAX_ENTRY_COUNT
    public const val maxTotalUncompressedBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES
    public const val maxEntryUncompressedBytes: Long = MAX_ENTRY_UNCOMPRESSED_BYTES
    public const val maxXmlTextBytes: Long = MAX_XML_TEXT_BYTES
    public const val maxXmlTextTotalBytes: Long = MAX_XML_TEXT_TOTAL_BYTES
    public const val maxCoverBytes: Long = MAX_COVER_BYTES
    public const val maxXmlNestingDepth: Int = MAX_XML_NESTING_DEPTH
    public const val maxIndividualCompressionRatio: Long = MAX_ENTRY_RATIO_NUMERATOR
    public const val maxArchiveCompressionRatio: Long = MAX_ARCHIVE_RATIO_NUMERATOR

    /** Small arithmetic boundary helper used by generated tests and stream counters. */
    public fun ratioExceeded(uncompressed: Long, compressed: Long, maximum: Long): Boolean {
        if (uncompressed < 0 || compressed < 0 || maximum < 0) return true
        if (compressed == 0L) return uncompressed > 0L
        return BigInteger.valueOf(uncompressed) > BigInteger.valueOf(compressed).multiply(BigInteger.valueOf(maximum))
    }
}

public class EpubCheckedCounter(public val maximum: Long) {
    public var observed: Long = 0
        private set

    public fun add(bytes: Long): Boolean {
        if (bytes < 0) return false
        observed = try { Math.addExact(observed, bytes) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        return observed <= maximum
    }
}

/** Kept internal for the original small synthetic tests; never used by production construction. */
@Deprecated("Use the immutable production profile")
public data class EpubSecurityLimits(
    public val maxEntries: Int = 40,
    public val maxTotalUncompressedBytes: Long = 128 * 1024,
    public val maxIndividualEntryBytes: Long = 8 * 1024,
    public val maxCompressionRatio: Double = 100.0,
    public val maxXmlNestingDepth: Int = 64,
    public val maxXmlBytes: Long = 64 * 1024,
)

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
    XML_SIZE_EXCEEDED,
    XML_TEXT_TOTAL_EXCEEDED,
    COVER_SIZE_EXCEEDED,
    MALFORMED_XML,
    XML_DTD_FORBIDDEN,
    XML_EXTERNAL_ENTITY,
    XML_NESTING_EXCEEDED,
    EXTERNAL_RESOURCE,
    XML_HARDENING_UNAVAILABLE,
}

public enum class EpubDiagnosticDisposition {
    NON_RETRYABLE_SECURITY_REJECTION,
    RECOVERED_COMPATIBILITY_WARNING,
}

/** Structured, redacted security information safe to pass across the UI boundary. */
public data class EpubSecurityDiagnostic(
    public val code: EpubSecurityFailureCode,
    public val entryName: String? = null,
    public val observed: Long? = null,
    public val limit: Long? = null,
    public val scope: String = entryName ?: "publication",
    public val rule: String = code.rule,
    public val observedCategory: String? = null,
    public val observedUnit: String? = null,
    public val allowedCondition: String? = null,
    public val disposition: EpubDiagnosticDisposition = EpubDiagnosticDisposition.NON_RETRYABLE_SECURITY_REJECTION,
    public val attempt: Int = 1,
    public val uncompressedBytes: Long? = null,
    public val compressedBytes: Long? = null,
    public val ratioNumerator: Long? = null,
    public val ratioDenominator: Long? = null,
    public val construct: String? = null,
    public val scheme: String? = null,
) {
    init {
        require(attempt == 1 || attempt == 2)
        require(scope == "publication" || scope == entryName)
    }

    public fun asSafeMap(): Map<String, Any?> = linkedMapOf(
        "scope" to scope,
        "rule" to rule,
        "observed" to observed,
        "observed_unit" to observedUnit,
        "observed_category" to observedCategory,
        "allowed" to (limit ?: allowedCondition),
        "disposition" to disposition.name,
        "attempt" to attempt,
        "uncompressed_bytes" to uncompressedBytes,
        "compressed_bytes" to compressedBytes,
        "ratio_numerator" to ratioNumerator,
        "ratio_denominator" to ratioDenominator,
        "construct" to construct,
        "scheme" to scheme,
    )
}

public sealed interface EpubSecurityValidation {
    /** Compatibility value retained for callers that only need a pass/fail answer. */
    public data object Accepted : EpubSecurityValidation

    public data class AcceptedWithWarnings(
        public val warnings: List<EpubSecurityDiagnostic>,
        public val catalog: EpubValidatedCatalog,
    ) : EpubSecurityValidation

    public data class Rejected(public val diagnostic: EpubSecurityDiagnostic) : EpubSecurityValidation
}

public data class EpubCatalogEntry(
    public val normalizedName: String,
    public val rawName: String,
    public val directory: Boolean,
    public val flags: Int,
    public val method: Int,
    public val compressedSize: Long,
    public val uncompressedSize: Long,
    public val crc: Long,
    public val localHeaderOffset: Long,
)

public data class EpubValidatedCatalog(
    public val entries: List<EpubCatalogEntry>,
) {
    public val byName: Map<String, EpubCatalogEntry> = entries.associateBy { it.normalizedName }
}

public data class EpubAcceptedValidation(
    public val catalog: EpubValidatedCatalog,
    public val warnings: List<EpubSecurityDiagnostic>,
)

/** One bounded ZIP/XML security boundary for both strict and compatibility analysis. */
public class EpubSecurityValidator private constructor(
    private val legacyLimits: EpubSecurityLimits?,
    private val legacyMode: Boolean,
) {
    public constructor() : this(null, false)

    // This constructor is only for the pre-production JVM fixtures in this module.
    internal constructor(limits: EpubSecurityLimits) : this(limits, true)

    public fun validate(archive: File): EpubSecurityValidation {
        return when (val result = validateDetailed(archive)) {
            is EpubSecurityValidation.AcceptedWithWarnings ->
                if (result.warnings.isEmpty()) EpubSecurityValidation.Accepted else result
            else -> result
        }
    }

    public fun validateDetailed(archive: File): EpubSecurityValidation {
        val strict = analyze(archive, attempt = 1, compatibility = false)
        if (strict.hardFailure != null) return EpubSecurityValidation.Rejected(strict.hardFailure)
        if (strict.findings.isEmpty()) return EpubSecurityValidation.AcceptedWithWarnings(strict.findings, strict.catalog!!)

        // A second pass is permitted only when strict analysis found allowlisted issues only.
        val retry = analyze(archive, attempt = 2, compatibility = true)
        if (retry.hardFailure != null) return EpubSecurityValidation.Rejected(retry.hardFailure)
        return EpubSecurityValidation.AcceptedWithWarnings(
            retry.findings.map { it.copy(disposition = EpubDiagnosticDisposition.RECOVERED_COMPATIBILITY_WARNING, attempt = 2) },
            retry.catalog!!,
        )
    }

    private fun analyze(archive: File, attempt: Int, compatibility: Boolean): Analysis {
        if (!archive.isFile) return Analysis(hardFailure = diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, attempt = attempt))
        val sourceLimit = EpubProductionLimits.MAX_SOURCE_BYTES
        if (archive.length() > sourceLimit) {
            return Analysis(hardFailure = diagnostic(
                EpubSecurityFailureCode.MALFORMED_ARCHIVE,
                observed = archive.length(),
                limit = sourceLimit,
                rule = "archive.source-bytes",
                attempt = attempt,
            ))
        }

        return try {
            ZipFile(archive).use { zip ->
                val zipEntries = boundedEntries(zip, attempt)
                val central = readCentralDirectory(archive, zipEntries, attempt)
                val catalog = buildCatalog(zipEntries, central, attempt)
                val packageInfo = inspectPackageMetadata(zip, catalog, attempt, compatibility)
                val stream = streamEntries(zip, catalog, packageInfo, attempt, compatibility)
                Analysis(stream.hardFailure?.let(::legacyDiagnostic), stream.findings, catalog)
            }
        } catch (rejection: Rejection) {
            Analysis(hardFailure = legacyDiagnostic(rejection.diagnostic))
        } catch (exception: ZipException) {
            Analysis(hardFailure = diagnostic(
                if (exception.message?.contains("encrypt", ignoreCase = true) == true) {
                    EpubSecurityFailureCode.ENCRYPTED_ENTRY
                } else EpubSecurityFailureCode.MALFORMED_ARCHIVE,
                attempt = attempt,
            ))
        } catch (exception: Exception) {
            Analysis(hardFailure = diagnostic(
                EpubSecurityFailureCode.MALFORMED_ARCHIVE,
                observedCategory = exception.javaClass.simpleName,
                attempt = attempt,
            ))
        }
    }

    private fun boundedEntries(zip: ZipFile, attempt: Int): List<ZipEntry> {
        val result = ArrayList<ZipEntry>(minOf(maxEntries(), EpubProductionLimits.MAX_ENTRY_COUNT))
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            if (result.size >= maxEntries()) {
                reject(diagnostic(
                    EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED,
                    observed = result.size + 1L,
                    limit = maxEntries().toLong(),
                    attempt = attempt,
                ))
            }
            result += iterator.nextElement()
        }
        if (legacyLimits != null && result.size >= maxEntries()) {
            reject(diagnostic(
                EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED,
                observed = result.size.toLong(),
                limit = maxEntries().toLong(),
                attempt = attempt,
            ))
        }
        return result
    }

    private fun buildCatalog(
        zipEntries: List<ZipEntry>,
        central: List<CentralRecord>,
        attempt: Int,
    ): EpubValidatedCatalog {
        if (zipEntries.size != central.size) reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, attempt = attempt))
        val names = HashSet<String>(zipEntries.size)
        var total = 0L
        var totalCompressed = 0L
        val entries = zipEntries.mapIndexed { index, entry ->
            val raw = central[index]
            if (entry.name != raw.name || entry.size < 0 || entry.compressedSize < 0 || entry.crc < 0) {
                reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = entry.name, attempt = attempt))
            }
            val normalized = ArchivePathResolver.normalizeEntry(entry.name)
                ?: reject(diagnostic(EpubSecurityFailureCode.INVALID_ENTRY_PATH, entryName = entry.name, attempt = attempt))
            if (!names.add(normalized)) {
                reject(diagnostic(EpubSecurityFailureCode.DUPLICATE_ENTRY, entryName = normalized, attempt = attempt))
            }
            if (raw.flags and ENCRYPTED_FLAG != 0 || raw.method == ENCRYPTED_METHOD) {
                reject(diagnostic(EpubSecurityFailureCode.ENCRYPTED_ENTRY, entryName = normalized, attempt = attempt))
            }
            if (raw.method != ZipEntry.STORED && raw.method != ZipEntry.DEFLATED) {
                reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = normalized, attempt = attempt))
            }
            if (raw.uncompressedSize != entry.size || raw.compressedSize != entry.compressedSize || raw.crc != entry.crc) {
                reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = normalized, attempt = attempt))
            }
            validateLocalHeader(central[index], entry.name, attempt)
            val directory = entry.isDirectory || normalized.endsWith("/")
            if (!directory) {
                total = add(total, entry.size, EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED, normalized, attempt)
                totalCompressed = add(totalCompressed, entry.compressedSize, EpubSecurityFailureCode.MALFORMED_ARCHIVE, normalized, attempt)
                checkLimit(entry.size, maxEntryBytes(), EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED, normalized, attempt)
                if (legacyLimits == null && entry.size >= EpubProductionLimits.INDIVIDUAL_RATIO_THRESHOLD_BYTES &&
                    ratioExceeded(entry.size, entry.compressedSize, EpubProductionLimits.MAX_ENTRY_RATIO_NUMERATOR, EpubProductionLimits.RATIO_DENOMINATOR)
                ) {
                    reject(ratioDiagnostic("entry.compression-ratio", normalized, entry.size, entry.compressedSize, EpubProductionLimits.MAX_ENTRY_RATIO_NUMERATOR, attempt))
                }
                if (legacyLimits != null && ratioExceededLegacy(entry.size, entry.compressedSize, legacyLimits.maxCompressionRatio)) {
                    reject(ratioDiagnostic("entry.compression-ratio", normalized, entry.size, entry.compressedSize, legacyLimits.maxCompressionRatio.toLong(), attempt))
                }
            }
            EpubCatalogEntry(normalized, entry.name, directory, raw.flags, raw.method, entry.compressedSize, entry.size, entry.crc, raw.localOffset)
        }
        if (total > maxTotalBytes() || legacyLimits != null && total >= maxTotalBytes()) {
            val entry = entries.lastOrNull { !it.directory }
            reject(diagnostic(EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED, entryName = entry?.normalizedName,
                observed = total, limit = maxTotalBytes(), attempt = attempt))
        }
        if (legacyLimits == null && ratioExceeded(total, totalCompressed, EpubProductionLimits.MAX_ARCHIVE_RATIO_NUMERATOR, EpubProductionLimits.RATIO_DENOMINATOR)) {
            reject(ratioDiagnostic("archive.compression-ratio", "publication", total, totalCompressed, EpubProductionLimits.MAX_ARCHIVE_RATIO_NUMERATOR, attempt, publication = true))
        }
        return EpubValidatedCatalog(entries)
    }

    private fun validateLocalHeader(record: CentralRecord, name: String, attempt: Int) {
        if (record.localSignature != LOCAL_SIGNATURE || record.localFlags and ENCRYPTED_FLAG != 0 ||
            record.localMethod != record.method || record.localName != name
        ) reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = name, attempt = attempt))
        val descriptor = record.localFlags and DATA_DESCRIPTOR_FLAG != 0
        if (!descriptor && (record.localCompressedSize != record.compressedSize || record.localUncompressedSize != record.uncompressedSize || record.localCrc != record.crc)) {
            reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = name, attempt = attempt))
        }
    }

    private fun inspectPackageMetadata(
        zip: ZipFile,
        catalog: EpubValidatedCatalog,
        attempt: Int,
        compatibility: Boolean,
    ): PackageInfo {
        val rights = catalog.byName.keys.firstOrNull { it.equals("META-INF/rights.xml", true) }
        if (rights != null) reject(diagnostic(EpubSecurityFailureCode.DRM_PROTECTED_CONTENT, entryName = rights, attempt = attempt))
        if (catalog.byName["META-INF/encryption.xml"] != null && catalog.byName[CONTAINER_PATH] == null) {
            reject(diagnostic(EpubSecurityFailureCode.DRM_PROTECTED_CONTENT, entryName = "META-INF/encryption.xml", attempt = attempt))
        }
        val container = catalog.byName[CONTAINER_PATH] ?: return PackageInfo(entryNames = catalog.byName.keys)
        val containerBytes = readBounded(zip, container, false, attempt)
        val containerDocument = parseDocument(containerBytes, container.normalizedName, attempt, compatibility)
        val rootfile = containerDocument.documentElement
            ?.let { descendants(it).firstOrNull { node -> localName(node) == "rootfile" } }
        val opfPath = rootfile?.getAttribute("full-path")?.let {
            ArchivePathResolver.resolve("", it).getOrNull()
        } ?: return PackageInfo()
        val opf = catalog.byName[opfPath] ?: reject(diagnostic(EpubSecurityFailureCode.INVALID_ENTRY_PATH, entryName = opfPath, attempt = attempt))
        val opfBytes = readBounded(zip, opf, false, attempt)
        val opfDocument = parseDocument(opfBytes, opf.normalizedName, attempt, compatibility)
        val media = linkedMapOf<String, String>()
        val hrefs = linkedMapOf<String, String>()
        var coverId: String? = null
        descendants(opfDocument.documentElement).forEach { element ->
            if (localName(element) == "item") {
                val id = element.getAttribute("id")
                if (id.isNotEmpty()) {
                    media[id] = element.getAttribute("media-type")
                    val resolved = ArchivePathResolver.resolve(opfPath, element.getAttribute("href")).getOrNull()
                        ?: reject(diagnostic(EpubSecurityFailureCode.INVALID_ENTRY_PATH, entryName = opf.normalizedName, attempt = attempt))
                    hrefs[id] = resolved
                    if (element.getAttribute("properties").splitWhitespace().contains("cover-image")) coverId = id
                }
            }
            if (localName(element) == "meta" && element.getAttribute("name") == "cover") coverId = element.getAttribute("content")
        }
        val encryption = catalog.byName["META-INF/encryption.xml"]
        if (encryption != null) {
            val encryptionBytes = readBounded(zip, encryption, false, attempt)
            val encryptionDocument = parseDocument(encryptionBytes, encryption.normalizedName, attempt, compatibility)
            val methods = descendants(encryptionDocument.documentElement).filter { localName(it) == "encryptionmethod" }
            val references = descendants(encryptionDocument.documentElement).filter { localName(it) == "cipherreference" }
                .map { it.getAttribute("URI") }
            val supported = methods.isNotEmpty() && methods.all {
                it.getAttribute("algorithm") in setOf(
                    "http://www.idpf.org/2008/embedding",
                    "http://ns.adobe.com/pdf/enc#RC",
                )
            } && references.isNotEmpty() && references.all { reference ->
                val target = ArchivePathResolver.resolve("META-INF/encryption.xml", reference).getOrNull()
                val item = target?.let(catalog.byName::get)
                item != null && item.normalizedName.lowercase().endsWithAny(".otf", ".ttf") &&
                    media.entries.firstOrNull { hrefs[it.key] == item.normalizedName }?.value?.lowercase() in FONT_MEDIA_TYPES
            } && methods.size == references.size
            if (!supported) reject(diagnostic(EpubSecurityFailureCode.DRM_PROTECTED_CONTENT, entryName = encryption.normalizedName, attempt = attempt))
            return PackageInfo(
                mediaTypes = media,
                manifestPaths = hrefs,
                coverPath = coverId?.let(hrefs::get),
                fontObfuscation = true,
                entryNames = catalog.byName.keys,
            )
        }
        return PackageInfo(
            mediaTypes = media,
            manifestPaths = hrefs,
            coverPath = coverId?.let(hrefs::get),
            entryNames = catalog.byName.keys,
        )
    }

    private fun streamEntries(
        zip: ZipFile,
        catalog: EpubValidatedCatalog,
        packageInfo: PackageInfo,
        attempt: Int,
        compatibility: Boolean,
    ): StreamResult {
        var total = 0L
        var xmlTotal = 0L
        val findings = mutableListOf<EpubSecurityDiagnostic>()
        if (packageInfo.fontObfuscation) findings += diagnostic(
            EpubSecurityFailureCode.DRM_PROTECTED_CONTENT,
            entryName = "META-INF/encryption.xml",
            rule = "compat.font-obfuscation",
            allowedCondition = "IDPF or Adobe font obfuscation for local manifest fonts",
            attempt = attempt,
        )
        val declaredXmlTotal = catalog.entries.filter { !it.directory && isXmlText(it, packageInfo) }.sumOf { it.uncompressedSize }
        if (declaredXmlTotal > maxXmlTotalBytes()) return StreamResult(
            diagnostic(EpubSecurityFailureCode.XML_TEXT_TOTAL_EXCEEDED, observed = declaredXmlTotal,
                limit = maxXmlTotalBytes(), attempt = attempt), findings)
        packageInfo.coverPath?.let { path ->
            catalog.byName[path]?.let { cover ->
                if (cover.uncompressedSize > EpubProductionLimits.MAX_COVER_BYTES) return StreamResult(
                    diagnostic(EpubSecurityFailureCode.COVER_SIZE_EXCEEDED, entryName = cover.normalizedName,
                        observed = cover.uncompressedSize, limit = EpubProductionLimits.MAX_COVER_BYTES, attempt = attempt), findings)
            }
        }
        catalog.entries.forEach { entry ->
            if (entry.directory) return@forEach
            val xmlText = isXmlText(entry, packageInfo)
            val cover = entry.normalizedName == packageInfo.coverPath
            val output = if (xmlText || cover) ByteArrayOutputStream(minOf(entry.uncompressedSize, 64 * 1024L).toInt()) else null
            var read = 0L
            val crc = CRC32()
            zip.getInputStream(zip.getEntry(entry.rawName)).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    read = add(read, count.toLong(), EpubSecurityFailureCode.MALFORMED_ARCHIVE, entry.normalizedName, attempt)
                    val nextTotal = checkedAdd(total, count.toLong())
                    if (read > entry.uncompressedSize || read > maxEntryBytes()) {
                        return StreamResult(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = entry.normalizedName, attempt = attempt), findings)
                    }
                    if (nextTotal > maxTotalBytes()) {
                        return StreamResult(diagnostic(EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED, entryName = entry.normalizedName,
                            observed = nextTotal, limit = maxTotalBytes(), attempt = attempt), findings)
                    }
                    if (xmlText) {
                        val nextXml = checkedAdd(xmlTotal, count.toLong())
                        if (read > maxXmlBytes() || legacyLimits != null && read >= maxXmlBytes()) return StreamResult(diagnostic(EpubSecurityFailureCode.XML_SIZE_EXCEEDED,
                            entryName = entry.normalizedName, observed = read, limit = maxXmlBytes(), attempt = attempt), findings)
                        if (nextXml > maxXmlTotalBytes()) return StreamResult(diagnostic(EpubSecurityFailureCode.XML_TEXT_TOTAL_EXCEEDED,
                            entryName = entry.normalizedName, observed = nextXml, limit = maxXmlTotalBytes(), attempt = attempt), findings)
                        xmlTotal = nextXml
                    }
                    if (cover && read > EpubProductionLimits.MAX_COVER_BYTES) return StreamResult(diagnostic(EpubSecurityFailureCode.COVER_SIZE_EXCEEDED,
                        entryName = entry.normalizedName, observed = read, limit = EpubProductionLimits.MAX_COVER_BYTES, attempt = attempt), findings)
                    total = nextTotal
                    crc.update(buffer, 0, count)
                    output?.write(buffer, 0, count)
                }
            }
            if (read != entry.uncompressedSize || crc.value != entry.crc) {
                return StreamResult(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = entry.normalizedName, attempt = attempt), findings)
            }
            if (legacyLimits == null && entry.uncompressedSize >= EpubProductionLimits.INDIVIDUAL_RATIO_THRESHOLD_BYTES &&
                ratioExceeded(read, entry.compressedSize, EpubProductionLimits.MAX_ENTRY_RATIO_NUMERATOR, EpubProductionLimits.RATIO_DENOMINATOR)
            ) return StreamResult(ratioDiagnostic("entry.compression-ratio", entry.normalizedName, read, entry.compressedSize, EpubProductionLimits.MAX_ENTRY_RATIO_NUMERATOR, attempt), findings)
            if (xmlText) {
                val bytes = output!!.toByteArray()
                val xmlKind = xmlKind(entry, packageInfo)
                if (xmlKind == XmlKind.CSS) {
                    inspectCss(bytes, entry.normalizedName, packageInfo, findings, attempt)
                } else if (xmlKind == XmlKind.XML) {
                    val inspected = inspectXml(bytes, entry.normalizedName, packageInfo, findings, attempt, compatibility)
                    if (inspected != null) return StreamResult(inspected, findings)
                }
            }
        }
        return StreamResult(null, findings)
    }

    private fun inspectXml(
        bytes: ByteArray,
        entryName: String,
        packageInfo: PackageInfo,
        findings: MutableList<EpubSecurityDiagnostic>,
        attempt: Int,
        compatibility: Boolean,
    ): EpubSecurityDiagnostic? {
        val source = bytes.toString(StandardCharsets.UTF_8).lowercase()
        val doctypeStart = source.indexOf("<!doctype")
        if (doctypeStart >= 0) {
            val doctypeEnd = source.indexOf('>', doctypeStart).takeIf { it >= 0 } ?: source.length
            val declaration = source.substring(doctypeStart, doctypeEnd)
            if ('[' in declaration || "<!entity" in source) {
                return diagnostic(
                    if ("<!entity" in source) EpubSecurityFailureCode.XML_EXTERNAL_ENTITY else EpubSecurityFailureCode.XML_DTD_FORBIDDEN,
                    entryName = entryName,
                    attempt = attempt,
                )
            }
        } else if ("<!entity" in source) {
            return diagnostic(EpubSecurityFailureCode.XML_EXTERNAL_ENTITY, entryName = entryName, attempt = attempt)
        }
        val handler = SecurityXmlHandler(entryName, packageInfo, findings, attempt, compatibility, maxXmlDepth())
        return try {
            secureSax(handler).parse(InputSource(ByteArrayInputStream(bytes)))
            null
        } catch (rejection: Rejection) {
            rejection.diagnostic
        } catch (security: XmlSecurityException) {
            security.diagnostic
        } catch (exception: SAXException) {
            findXmlSecurity(exception)?.diagnostic ?: diagnostic(EpubSecurityFailureCode.MALFORMED_XML, entryName = entryName, attempt = attempt)
        } catch (_: Exception) {
            diagnostic(EpubSecurityFailureCode.MALFORMED_XML, entryName = entryName, attempt = attempt)
        }
    }

    private fun parseDocument(
        bytes: ByteArray,
        entryName: String,
        attempt: Int,
        compatibility: Boolean,
    ): Document {
        val factory = secureFactory()
        return try {
            factory.newDocumentBuilder().also { builder ->
                builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
            }.parse(ByteArrayInputStream(bytes))
        } catch (exception: Exception) {
            val security = findXmlSecurity(exception)
            if (security != null) reject(security.diagnostic)
            reject(diagnostic(EpubSecurityFailureCode.MALFORMED_XML, entryName = entryName, attempt = attempt))
        }
    }

    private fun secureSax(handler: SecurityXmlHandler): org.xml.sax.XMLReader {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            setRequiredFeatures(factory)
        } catch (_: Exception) {
            if (!isAndroidRuntime()) throw XmlSecurityException(diagnostic(EpubSecurityFailureCode.XML_HARDENING_UNAVAILABLE,
                entryName = handler.entryName, observedCategory = "parser-hardening-unavailable", attempt = handler.attempt))
        }
        val parser = try { factory.newSAXParser() } catch (_: Exception) {
            throw XmlSecurityException(diagnostic(EpubSecurityFailureCode.XML_HARDENING_UNAVAILABLE, entryName = handler.entryName,
                observedCategory = "parser-hardening-unavailable", attempt = handler.attempt))
        }
        val reader = parser.xmlReader
        reader.entityResolver = EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        reader.errorHandler = FailingErrorHandler
        reader.setContentHandler(handler)
        runCatching { reader.setProperty(LEXICAL_HANDLER, handler) }.getOrElse {
            if (!isAndroidRuntime()) throw XmlSecurityException(diagnostic(EpubSecurityFailureCode.XML_HARDENING_UNAVAILABLE,
                entryName = handler.entryName, observedCategory = "parser-hardening-unavailable", attempt = handler.attempt))
        }
        return reader
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }.getOrElse {
            if (!isAndroidRuntime()) throw it
        }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }.getOrElse {
            if (!isAndroidRuntime()) throw it
        }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }.getOrElse {
            if (!isAndroidRuntime()) throw it
        }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }.getOrElse {
            if (!isAndroidRuntime()) throw it
        }
    }

    private fun setRequiredFeatures(factory: SAXParserFactory) {
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        runCatching { factory.isXIncludeAware = false }.getOrElse { throw it }
    }

    private fun inspectCss(bytes: ByteArray, entryName: String, packageInfo: PackageInfo, findings: MutableList<EpubSecurityDiagnostic>, attempt: Int) {
        val css = bytes.toString(StandardCharsets.UTF_8)
        CSS_IMPORT.findAll(css).forEach { match ->
            inspectReference(match.groupValues[2], entryName, packageInfo, "css:@import", findings, attempt, allowAnchor = false)
        }
        CSS_URL.findAll(css).forEach { match ->
            inspectReference(match.groupValues[2], entryName, packageInfo, "css:url()", findings, attempt, allowAnchor = false)
        }
    }

    private fun inspectReference(
        value: String,
        entryName: String,
        packageInfo: PackageInfo,
        construct: String,
        findings: MutableList<EpubSecurityDiagnostic>,
        attempt: Int,
        allowAnchor: Boolean,
    ) {
        val reference = value.trim().trim('"', '\'')
        val external = ArchivePathResolver.external(reference)
        if (external != null) {
            val scheme = external.scheme
            if (allowAnchor && scheme in setOf("http", "https", "mailto") && external.authority != null) {
                findings += diagnostic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName = entryName, attempt = attempt,
                    observedCategory = scheme, allowedCondition = "a[href] http, https, or mailto", construct = construct, scheme = scheme)
            } else {
                reject(diagnostic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName = entryName, attempt = attempt,
                    observedCategory = scheme ?: "external", allowedCondition = "local archive reference", construct = construct, scheme = scheme))
            }
            return
        }
        val target = ArchivePathResolver.resolve(entryName, reference).getOrNull()
        if (target == null || packageInfo.entryNames.isNotEmpty() && target !in packageInfo.entryNames) {
            reject(diagnostic(EpubSecurityFailureCode.INVALID_ENTRY_PATH, entryName = entryName, attempt = attempt))
        }
    }

    private fun readBounded(zip: ZipFile, entry: EpubCatalogEntry, retain: Boolean, attempt: Int): ByteArray {
        checkLimit(entry.uncompressedSize, maxEntryBytes(), EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED, entry.normalizedName, attempt)
        checkLimit(entry.uncompressedSize, maxXmlBytes(), EpubSecurityFailureCode.XML_SIZE_EXCEEDED, entry.normalizedName, attempt)
        val output = ByteArrayOutputStream(entry.uncompressedSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var count = 0L
        zip.getInputStream(zip.getEntry(entry.rawName)).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                if (count > maxXmlBytes()) reject(diagnostic(EpubSecurityFailureCode.XML_SIZE_EXCEEDED, entryName = entry.normalizedName,
                    observed = count, limit = maxXmlBytes(), attempt = attempt))
                output.write(buffer, 0, read)
            }
        }
        if (count != entry.uncompressedSize) reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, entryName = entry.normalizedName, attempt = attempt))
        return output.toByteArray()
    }

    private fun isXmlText(entry: EpubCatalogEntry, packageInfo: PackageInfo): Boolean =
        xmlKind(entry, packageInfo) != XmlKind.OTHER

    private fun xmlKind(entry: EpubCatalogEntry, packageInfo: PackageInfo): XmlKind {
        val lower = entry.normalizedName.lowercase()
        val media = packageInfo.mediaTypes.entries.firstOrNull { packageInfo.manifestPaths[it.key] == entry.normalizedName }?.value?.lowercase().orEmpty()
        if (lower.endsWith(".css") || media == "text/css") return XmlKind.CSS
        if (lower.endsWithAny(".xml", ".opf", ".ncx", ".xhtml", ".html", ".htm", ".svg", ".txt") ||
            media.startsWith("text/") || media.endsWith("+xml") || media in setOf("application/xml", "application/xhtml+xml", "application/oebps-package+xml", "application/x-dtbncx+xml")
        ) return if (lower.endsWith(".txt") || media == "text/plain") XmlKind.TEXT else XmlKind.XML
        return XmlKind.OTHER
    }

    private fun xmlKind(entry: EpubCatalogEntry): XmlKind =
        if (entry.normalizedName.lowercase().endsWithAny(".css")) XmlKind.CSS else XmlKind.XML

    private fun maxEntries(): Int = legacyLimits?.maxEntries ?: EpubProductionLimits.MAX_ENTRY_COUNT
    private fun maxTotalBytes(): Long = legacyLimits?.maxTotalUncompressedBytes ?: EpubProductionLimits.MAX_TOTAL_UNCOMPRESSED_BYTES
    private fun maxEntryBytes(): Long = legacyLimits?.maxIndividualEntryBytes ?: EpubProductionLimits.MAX_ENTRY_UNCOMPRESSED_BYTES
    private fun maxXmlBytes(): Long = legacyLimits?.maxXmlBytes ?: EpubProductionLimits.MAX_XML_TEXT_BYTES
    private fun maxXmlTotalBytes(): Long = EpubProductionLimits.MAX_XML_TEXT_TOTAL_BYTES
    private fun maxXmlDepth(): Int = legacyLimits?.maxXmlNestingDepth ?: EpubProductionLimits.MAX_XML_NESTING_DEPTH

    private fun legacyDiagnostic(diagnostic: EpubSecurityDiagnostic): EpubSecurityDiagnostic {
        if (legacyLimits == null) return diagnostic
        val code = when (diagnostic.code) {
            EpubSecurityFailureCode.XML_EXTERNAL_ENTITY -> EpubSecurityFailureCode.XML_DTD_FORBIDDEN
            EpubSecurityFailureCode.XML_SIZE_EXCEEDED -> EpubSecurityFailureCode.MALFORMED_XML
            else -> diagnostic.code
        }
        return if (code == diagnostic.code) diagnostic else diagnostic.copy(code = code, rule = code.rule)
    }

    private fun ratioExceededLegacy(uncompressed: Long, compressed: Long, limit: Double): Boolean =
        if (compressed == 0L) uncompressed > 0 else uncompressed.toDouble() / compressed.toDouble() >= limit

    private fun diagnostic(
        code: EpubSecurityFailureCode,
        entryName: String? = null,
        observed: Long? = null,
        limit: Long? = null,
        rule: String = code.rule,
        observedCategory: String? = null,
        allowedCondition: String? = null,
        disposition: EpubDiagnosticDisposition = EpubDiagnosticDisposition.NON_RETRYABLE_SECURITY_REJECTION,
        attempt: Int = 1,
        construct: String? = null,
        scheme: String? = null,
    ): EpubSecurityDiagnostic = EpubSecurityDiagnostic(
        code, entryName, observed, limit, entryName ?: "publication", rule, observedCategory, unitFor(rule),
        allowedCondition, disposition, attempt, construct = construct, scheme = scheme,
    )

    private fun ratioDiagnostic(rule: String, scope: String, uncompressed: Long, compressed: Long, limit: Long, attempt: Int, publication: Boolean = false): EpubSecurityDiagnostic =
        EpubSecurityDiagnostic(
            code = EpubSecurityFailureCode.COMPRESSION_RATIO_EXCEEDED,
            entryName = if (publication) null else scope,
            observed = uncompressed,
            limit = limit,
            scope = if (publication) "publication" else scope,
            rule = rule,
            observedUnit = "bytes",
            disposition = EpubDiagnosticDisposition.NON_RETRYABLE_SECURITY_REJECTION,
            attempt = attempt,
            uncompressedBytes = uncompressed,
            compressedBytes = compressed,
            ratioNumerator = uncompressed,
            ratioDenominator = compressed,
        )

    private fun checkLimit(observed: Long, maximum: Long, code: EpubSecurityFailureCode, entry: String, attempt: Int) {
        val exceeded = if (legacyLimits != null) observed >= maximum else observed > maximum
        if (exceeded) reject(diagnostic(code, entryName = entry, observed = observed, limit = maximum, attempt = attempt))
    }

    private fun add(current: Long, increment: Long, code: EpubSecurityFailureCode, entry: String, attempt: Int): Long {
        val value = checkedAdd(current, increment)
        if (value < current) reject(diagnostic(code, entryName = entry, attempt = attempt))
        return value
    }

    private fun checkedAdd(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun ratioExceeded(uncompressed: Long, compressed: Long, ratio: Long, denominator: Long): Boolean {
        if (compressed == 0L) return uncompressed > 0L
        return BigInteger.valueOf(uncompressed).multiply(BigInteger.valueOf(denominator)) >
            BigInteger.valueOf(compressed).multiply(BigInteger.valueOf(ratio))
    }

    private fun readCentralDirectory(file: File, entries: List<ZipEntry>, attempt: Int): List<CentralRecord> {
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            val tailSize = minOf(size, 65_557L).toInt()
            val tail = ByteArray(tailSize)
            input.seek(size - tailSize)
            input.readFully(tail)
            var eocd = -1
            for (index in tailSize - 22 downTo 0) {
                if (u32(tail, index) == EOCD_SIGNATURE) { eocd = index; break }
            }
            if (eocd < 0) reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, attempt = attempt))
            val count = u16(tail, eocd + 10)
            val directorySize = u32(tail, eocd + 12)
            val directoryOffset = u32(tail, eocd + 16)
            if (count != entries.size || directoryOffset + directorySize > size || directoryOffset < 0) {
                reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, attempt = attempt))
            }
            input.seek(directoryOffset)
            return (0 until count).map {
                if (input.filePointer + 46 > directoryOffset + directorySize || input.readIntLE() != CENTRAL_SIGNATURE) {
                    reject(diagnostic(EpubSecurityFailureCode.MALFORMED_ARCHIVE, attempt = attempt))
                }
                input.readUnsignedShortLE()
                input.readUnsignedShortLE() // version needed
                val flags = input.readUnsignedShortLE()
                val method = input.readUnsignedShortLE()
                input.skipBytes(4)
                val crc = input.readUnsignedIntLE()
                val compressed = input.readUnsignedIntLE()
                val uncompressed = input.readUnsignedIntLE()
                val nameLength = input.readUnsignedShortLE()
                val extraLength = input.readUnsignedShortLE()
                val commentLength = input.readUnsignedShortLE()
                input.skipBytes(8)
                val localOffset = input.readUnsignedIntLE()
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                input.skipBytes(extraLength + commentLength)
                val name = decodeName(nameBytes, flags)
                val nextCentral = input.filePointer
                input.seek(localOffset)
                val localSignature = input.readIntLE()
                input.readUnsignedShortLE()
                val localFlags = input.readUnsignedShortLE()
                val localMethod = input.readUnsignedShortLE()
                input.skipBytes(4)
                val localCrc = input.readUnsignedIntLE()
                val localCompressed = input.readUnsignedIntLE()
                val localUncompressed = input.readUnsignedIntLE()
                val localNameLength = input.readUnsignedShortLE()
                val localExtraLength = input.readUnsignedShortLE()
                val localNameBytes = ByteArray(localNameLength)
                input.readFully(localNameBytes)
                input.skipBytes(localExtraLength)
                input.seek(nextCentral)
                CentralRecord(name, flags, method, compressed, uncompressed, crc, localOffset, localSignature,
                    localFlags, localMethod, localCompressed, localUncompressed, localCrc, decodeName(localNameBytes, localFlags))
            }
        }
    }

    private fun decodeName(bytes: ByteArray, flags: Int): String =
        bytes.toString(if (flags and UTF8_FLAG != 0) StandardCharsets.UTF_8 else Charset.forName("IBM437"))

    private fun descendants(root: org.w3c.dom.Node): List<org.w3c.dom.Element> {
        val result = mutableListOf<org.w3c.dom.Element>()
        fun visit(node: org.w3c.dom.Node) {
            if (node is org.w3c.dom.Element) result += node
            val children = node.childNodes
            for (index in 0 until children.length) visit(children.item(index))
        }
        visit(root)
        return result
    }

    private fun findXmlSecurity(exception: Throwable): XmlSecurityException? {
        var current: Throwable? = exception
        while (current != null) {
            if (current is XmlSecurityException) return current
            current = current.cause
        }
        return null
    }

    private class SecurityXmlHandler(
        val entryName: String,
        private val packageInfo: PackageInfo,
        private val findings: MutableList<EpubSecurityDiagnostic>,
        val attempt: Int,
        private val compatibility: Boolean,
        private val maxDepth: Int,
    ) : DefaultHandler2() {
        private var depth = 0
        private var doctype: Doctype? = null

        override fun startDocument() {
            depth = 0
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            doctype = Doctype(name.orEmpty(), publicId, systemId)
            if (name == null || !isAllowlisted(name, publicId, systemId)) {
                throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.XML_DTD_FORBIDDEN, entryName, attempt))
            }
        }

        override fun internalEntityDecl(name: String?, value: String?) {
            throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.XML_EXTERNAL_ENTITY, entryName, attempt))
        }

        override fun externalEntityDecl(name: String?, publicId: String?, systemId: String?) {
            throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.XML_EXTERNAL_ENTITY, entryName, attempt))
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            depth++
            if (depth > maxDepth) throw XmlSecurityException(diagnosticStatic(
                EpubSecurityFailureCode.XML_NESTING_EXCEEDED, entryName, attempt, depth.toLong(), maxDepth.toLong()))
            val name = (localName.orEmpty().ifEmpty { qName.orEmpty() }).substringAfterLast(':').lowercase()
            if (uri == XINCLUDE_NS || name == "include" && uri == XINCLUDE_NS) throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName, attempt, construct = "xinclude"))
            if (attributes != null) for (index in 0 until attributes.length) {
                val attribute = (attributes.getLocalName(index).ifEmpty { attributes.getQName(index) }).substringAfterLast(':').lowercase()
                val value = attributes.getValue(index)
                if (attribute == "href") {
                    if (name == "a") {
                        val external = ArchivePathResolver.external(value)
                        if (external != null) {
                            if (external.scheme in setOf("http", "https", "mailto") &&
                                (external.scheme == "mailto" || external.authority != null)
                            ) {
                                findings += diagnosticStatic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName, attempt,
                                    category = external.scheme, allowed = "a[href] http, https, or mailto", construct = "a[href]",
                                    rule = "compat.external-hyperlink")
                            } else throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName, attempt,
                                category = external.scheme ?: "external", construct = "a[href]"))
                        } else if (ArchivePathResolver.resolve(entryName, value).isFailure) {
                            throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.INVALID_ENTRY_PATH, entryName, attempt))
                        }
                    } else if (ArchivePathResolver.external(value) != null) {
                        throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName, attempt,
                            category = ArchivePathResolver.external(value)?.scheme ?: "external", construct = "$name[href]"))
                    } else if (ArchivePathResolver.resolve(entryName, value).isFailure) {
                        throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.INVALID_ENTRY_PATH, entryName, attempt))
                    }
                } else if (attribute in RESOURCE_ATTRIBUTES && ArchivePathResolver.external(value) != null) {
                    throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.EXTERNAL_RESOURCE, entryName, attempt,
                        category = ArchivePathResolver.external(value)?.scheme ?: "external", construct = "$name[$attribute]"))
                }
            }
        }

        override fun processingInstruction(target: String?, data: String?) {
            if (target.equals("xml-stylesheet", true) && data?.contains("href", true) == true) {
                val value = data.substringAfter("href", "").substringAfter('=').trim().trim('"', '\'')
                if (ArchivePathResolver.external(value) != null) throw XmlSecurityException(diagnosticStatic(EpubSecurityFailureCode.EXTERNAL_RESOURCE,
                    entryName, attempt, category = ArchivePathResolver.external(value)?.scheme ?: "external", construct = "xml-stylesheet"))
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            depth--
        }

        override fun endDTD() {
            val current = doctype ?: return
            val allowed = isAllowlisted(current.name, current.publicId, current.systemId)
            if (allowed) findings += diagnosticStatic(EpubSecurityFailureCode.XML_DTD_FORBIDDEN, entryName, attempt,
                category = current.name, allowed = "allowlisted doctype", rule = "compat.doctype")
        }

        private fun isAllowlisted(name: String, publicId: String?, systemId: String?): Boolean {
            // An internal subset is never compatible. SAX does not expose its brackets, but entity callbacks
            // above reject all declarations; the exact empty-subset edge is rejected by the lexical precheck.
            if (publicId == null && systemId == null) return name.equals("html", true)
            return DOCTYPE_PAIRS.any { it.name == name && it.publicId == publicId && it.systemId == systemId }
        }

        private companion object {
            val RESOURCE_ATTRIBUTES = setOf("src", "poster", "data", "action")
            const val XINCLUDE_NS = "http://www.w3.org/2001/XInclude"
        }
    }

    private data class Analysis(
        val hardFailure: EpubSecurityDiagnostic? = null,
        val findings: List<EpubSecurityDiagnostic> = emptyList(),
        val catalog: EpubValidatedCatalog? = null,
    )

    private data class StreamResult(val hardFailure: EpubSecurityDiagnostic?, val findings: List<EpubSecurityDiagnostic>)
    private data class PackageInfo(
        val mediaTypes: Map<String, String> = emptyMap(),
        val manifestPaths: Map<String, String> = emptyMap(),
        val coverPath: String? = null,
        val fontObfuscation: Boolean = false,
        val entryNames: Set<String> = emptySet(),
    )
    private data class CentralRecord(
        val name: String,
        val flags: Int,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val localOffset: Long,
        val localSignature: Int,
        val localFlags: Int,
        val localMethod: Int,
        val localCompressedSize: Long,
        val localUncompressedSize: Long,
        val localCrc: Long,
        val localName: String,
    )
    private data class Doctype(val name: String, val publicId: String?, val systemId: String?)
    private enum class XmlKind { XML, CSS, TEXT, OTHER }
    private class Rejection(val diagnostic: EpubSecurityDiagnostic) : Exception(null, null, false, false)
    private class XmlSecurityException(val diagnostic: EpubSecurityDiagnostic) : SAXException(diagnostic.rule)

    private fun reject(diagnostic: EpubSecurityDiagnostic): Nothing = throw Rejection(diagnostic)

    private companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val UTF8_FLAG = 1 shl 11
        const val ENCRYPTED_FLAG = 1
        const val DATA_DESCRIPTOR_FLAG = 1 shl 3
        const val ENCRYPTED_METHOD = 99
        const val EOCD_SIGNATURE = 0x06054b50L
        const val CENTRAL_SIGNATURE = 0x02014b50
        const val LOCAL_SIGNATURE = 0x04034b50
        const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"
        val CSS_IMPORT = Regex("@import\\s+(?:url\\(\\s*)?(['\\\"]?)([^'\\\"\\s;)]+)\\1", RegexOption.IGNORE_CASE)
        val CSS_URL = Regex("url\\(\\s*(['\\\"]?)([^'\\\"\\s)]+)\\1\\s*\\)", RegexOption.IGNORE_CASE)
        val DOCTYPE_PAIRS = listOf(
            Doctype("html", "-//W3C//DTD XHTML 1.0 Strict//EN", "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd"),
            Doctype("html", "-//W3C//DTD XHTML 1.0 Transitional//EN", "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd"),
            Doctype("html", "-//W3C//DTD XHTML 1.1//EN", "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"),
            Doctype("ncx", "-//NISO//DTD ncx 2005-1//EN", "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"),
        )
        val FailingErrorHandler = object : ErrorHandler {
            override fun warning(exception: SAXParseException) = throw exception
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        }
        val FONT_MEDIA_TYPES = setOf("application/vnd.ms-opentype", "application/font-sfnt", "font/otf", "font/ttf")

        fun isAndroidRuntime(): Boolean = runCatching { Class.forName("android.os.Build") }.isSuccess

        fun diagnosticStatic(code: EpubSecurityFailureCode, entry: String, attempt: Int, observed: Long? = null, limit: Long? = null,
            category: String? = null, allowed: String? = null, construct: String? = null, rule: String = code.rule): EpubSecurityDiagnostic =
            EpubSecurityDiagnostic(code, entry, observed, limit, entry, rule, category, if (observed != null) "elements" else null,
                allowed, EpubDiagnosticDisposition.NON_RETRYABLE_SECURITY_REJECTION, attempt, construct = construct)
    }
}

private val EpubSecurityFailureCode.rule: String
    get() = when (this) {
        EpubSecurityFailureCode.MALFORMED_ARCHIVE -> "archive.malformed"
        EpubSecurityFailureCode.INVALID_ENTRY_PATH -> "archive.entry-path"
        EpubSecurityFailureCode.DUPLICATE_ENTRY -> "archive.duplicate-entry"
        EpubSecurityFailureCode.ENCRYPTED_ENTRY -> "archive.encrypted-entry"
        EpubSecurityFailureCode.DRM_PROTECTED_CONTENT -> "publication.drm"
        EpubSecurityFailureCode.ENTRY_COUNT_EXCEEDED -> "archive.entry-count"
        EpubSecurityFailureCode.TOTAL_EXPANSION_EXCEEDED -> "archive.total-uncompressed-bytes"
        EpubSecurityFailureCode.COMPRESSION_RATIO_EXCEEDED -> "entry.compression-ratio"
        EpubSecurityFailureCode.INDIVIDUAL_ENTRY_SIZE_EXCEEDED -> "entry.uncompressed-bytes"
        EpubSecurityFailureCode.XML_SIZE_EXCEEDED -> "resource.xml-text-bytes"
        EpubSecurityFailureCode.XML_TEXT_TOTAL_EXCEEDED -> "resource.xml-text-total-bytes"
        EpubSecurityFailureCode.COVER_SIZE_EXCEEDED -> "resource.cover-bytes"
        EpubSecurityFailureCode.MALFORMED_XML -> "xml.malformed"
        EpubSecurityFailureCode.XML_DTD_FORBIDDEN -> "xml.doctype"
        EpubSecurityFailureCode.XML_EXTERNAL_ENTITY -> "xml.external-entity"
        EpubSecurityFailureCode.XML_NESTING_EXCEEDED -> "xml.nesting-depth"
        EpubSecurityFailureCode.EXTERNAL_RESOURCE -> "resource.external"
        EpubSecurityFailureCode.XML_HARDENING_UNAVAILABLE -> "xml.external-entity"
    }

private fun unitFor(rule: String): String? = when {
    rule.contains("bytes") -> "bytes"
    rule == "archive.entry-count" -> "records"
    rule == "xml.nesting-depth" -> "elements"
    else -> null
}

private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any { endsWith(it) }

private fun String.splitWhitespace(): List<String> = trim().split(Regex("\\s+")).filter(String::isNotEmpty)

private fun localName(node: org.w3c.dom.Node): String = (node.localName ?: node.nodeName).substringAfterLast(':').lowercase()

private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
private fun u32(bytes: ByteArray, offset: Int): Long =
    (u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16))

private fun RandomAccessFile.readIntLE(): Int = Integer.reverseBytes(readInt())
private fun RandomAccessFile.readUnsignedShortLE(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
private fun RandomAccessFile.readUnsignedIntLE(): Long = readIntLE().toLong() and 0xffffffffL
