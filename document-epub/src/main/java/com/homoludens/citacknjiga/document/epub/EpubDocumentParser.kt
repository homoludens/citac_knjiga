package com.homoludens.citacknjiga.document.epub

import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.BookProjectStatus
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockStatus
import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

public data class EpubPublicationMetadata(
    public val title: String,
    public val authors: List<String>,
    public val language: String?,
    public val identifier: String?,
    public val additional: Map<String, List<String>> = emptyMap(),
    public val missingFields: Set<String> = emptySet(),
)

public data class EpubCover(
    public val sourcePath: String,
    public val mediaType: String,
    public val bytes: ByteArray,
)

public data class EpubTocEntry(
    public val title: String,
    public val target: String?,
    public val sourceLocator: String,
    public val children: List<EpubTocEntry> = emptyList(),
)

public data class EpubNavigationIssue(
    public val message: String,
    public val sourceLocator: String,
)

public data class EpubNarrationBlock(
    public val ordinal: Int,
    public val type: NarrationBlockType,
    public val sourceText: String,
    public val sourceLocator: String,
    public val headingLevel: Int? = null,
    public val skippedReason: String? = null,
)

public data class EpubChapter(
    public val id: String,
    public val ordinal: Int,
    public val title: String,
    public val sourcePath: String?,
    public val sourceLocator: String,
    public val blocks: List<EpubNarrationBlock>,
)

public data class EpubDocument(
    public val projectId: String,
    public val sourceUri: String,
    public val sourceFingerprint: String,
    public val sourcePath: String,
    public val metadata: EpubPublicationMetadata,
    public val cover: EpubCover?,
    public val tableOfContents: List<EpubTocEntry>,
    public val chapters: List<EpubChapter>,
    public val navigationIssues: List<EpubNavigationIssue> = emptyList(),
) {
    /** Projection for the Room rows already defined by the persistent core. */
    public fun toRoomProjection(source: ImportedEpubSource, now: Long): EpubRoomProjection {
        require(source.projectId == projectId) { "Source and document project IDs differ" }
        val project = BookProjectEntity(
            id = projectId,
            title = metadata.title,
            author = metadata.authors.joinToString(", ").ifEmpty { null },
            sourceUri = source.sourceUri,
            sourceFingerprint = source.fingerprint,
            sourcePath = source.sourceFile.path,
            status = BookProjectStatus.IMPORTING,
            language = metadata.language ?: "sr",
            createdAt = now,
            updatedAt = now,
        )
        val chapterRows = chapters.map { chapter ->
            ChapterEntity(
                id = chapter.id,
                bookProjectId = projectId,
                ordinal = chapter.ordinal,
                title = chapter.title,
                sourceLocator = chapter.sourceLocator,
                status = ChapterStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        }
        val blockRows = chapters.flatMap { chapter ->
            chapter.blocks.map { block ->
                NarrationBlockEntity(
                    id = "${chapter.id}-block-${block.ordinal}",
                    chapterId = chapter.id,
                    ordinal = block.ordinal,
                    blockType = block.type,
                    sourceText = block.sourceText,
                    sourceLocator = block.sourceLocator,
                    status = if (block.type == NarrationBlockType.SKIPPED) {
                        NarrationBlockStatus.PROCESSED
                    } else {
                        NarrationBlockStatus.PENDING
                    },
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
        return EpubRoomProjection(project, chapterRows, blockRows)
    }
}

public data class EpubRoomProjection(
    public val project: BookProjectEntity,
    public val chapters: List<ChapterEntity>,
    public val narrationBlocks: List<NarrationBlockEntity>,
)

public enum class EpubParseFailureCode {
    SOURCE_NOT_PRIVATE,
    SOURCE_MISSING,
    SOURCE_CHANGED,
    INVALID_CONTAINER,
    INVALID_PACKAGE,
}

public sealed interface EpubParseResult {
    public data class Parsed(public val document: EpubDocument) : EpubParseResult

    public data class Rejected(public val diagnostic: EpubSecurityDiagnostic) : EpubParseResult

    public data class Failed(
        public val code: EpubParseFailureCode,
        public val entryPath: String? = null,
    ) : EpubParseResult
}

/**
 * Maps an already imported EPUB into the structured document IR. The parser only accepts the
 * exact source path owned by AppPrivateStorage and repeats security validation before ZipFile is
 * opened, so callers cannot hand it an arbitrary unvalidated path.
 */
public class EpubDocumentParser(
    private val storage: AppPrivateStorage,
    private val securityValidator: EpubSecurityValidator = EpubSecurityValidator(),
) {
    public fun parse(source: ImportedEpubSource): EpubParseResult {
        val expected = try {
            storage.sourceDocument(source.projectId).canonicalFile
        } catch (_: IllegalArgumentException) {
            return EpubParseResult.Failed(EpubParseFailureCode.SOURCE_NOT_PRIVATE)
        }
        val actual = source.sourceFile.canonicalFile
        if (actual != expected) return EpubParseResult.Failed(EpubParseFailureCode.SOURCE_NOT_PRIVATE)
        if (!actual.isFile) return EpubParseResult.Failed(EpubParseFailureCode.SOURCE_MISSING)
        if (actual.length() != source.sizeBytes) return EpubParseResult.Failed(EpubParseFailureCode.SOURCE_CHANGED)

        when (val validation = securityValidator.validate(actual)) {
            EpubSecurityValidation.Accepted -> Unit
            is EpubSecurityValidation.Rejected -> return EpubParseResult.Rejected(validation.diagnostic)
        }

        return try {
            ZipFile(actual).use { zip -> EpubParseResult.Parsed(parseArchive(zip, source)) }
        } catch (failure: ParseFailure) {
            EpubParseResult.Failed(failure.code, failure.entryPath)
        } catch (_: Exception) {
            EpubParseResult.Failed(EpubParseFailureCode.INVALID_PACKAGE)
        }
    }

    private fun parseArchive(zip: ZipFile, source: ImportedEpubSource): EpubDocument {
        val names = zip.entries().asSequence().map { it.name }.toSet()
        val containerPath = "META-INF/container.xml"
        val container = parseXml(readEntry(zip, names, containerPath), containerPath)
        val rootfile = elements(container).firstOrNull { localName(it) == "rootfile" }
            ?: fail(EpubParseFailureCode.INVALID_CONTAINER, containerPath)
        val opfPath = resolveEntry("", rootfile.getAttribute("full-path"))
            ?: fail(EpubParseFailureCode.INVALID_CONTAINER, containerPath)
        val packageDocument = parseXml(readEntry(zip, names, opfPath), opfPath)
        val metadata = parseMetadata(packageDocument)
        val manifest = elements(packageDocument)
            .filter { localName(it) == "item" && it.parentNode?.let(::localName) == "manifest" }
            .associateBy { it.getAttribute("id") }
        val spine = elements(packageDocument)
            .firstOrNull { localName(it) == "spine" }
        val itemRefs = spine?.childElements()?.filter { localName(it) == "itemref" }.orEmpty()
        val cover = parseCover(zip, names, opfPath, manifest)
        val navigation = parseNavigation(zip, names, opfPath, manifest, spine)
        val chapters = itemRefs.mapIndexed { index, itemRef ->
            parseChapter(
                zip = zip,
                names = names,
                opfPath = opfPath,
                manifest = manifest,
                itemRef = itemRef,
                ordinal = index,
                toc = navigation.entries,
                projectId = source.projectId,
            )
        }
        return EpubDocument(
            projectId = source.projectId,
            sourceUri = source.sourceUri,
            sourceFingerprint = source.fingerprint,
            sourcePath = source.sourceFile.path,
            metadata = metadata,
            cover = cover,
            tableOfContents = navigation.entries,
            chapters = chapters,
            navigationIssues = navigation.issues,
        )
    }

    private fun parseMetadata(packageDocument: Document): EpubPublicationMetadata {
        val metadata = elements(packageDocument).firstOrNull { localName(it) == "metadata" }
        val values = linkedMapOf<String, MutableList<String>>()
        metadata?.childElements()?.forEach { element ->
            val key = localName(element)
            val value = textOf(element)
            if (value.isNotEmpty()) values.getOrPut(key) { mutableListOf() }.add(value)
        }
        val title = values["title"]?.firstOrNull().orEmpty().ifEmpty { "Untitled EPUB" }
        return EpubPublicationMetadata(
            title = title,
            authors = values["creator"].orEmpty(),
            language = values["language"]?.firstOrNull(),
            identifier = values["identifier"]?.firstOrNull(),
            additional = values
                .filterKeys { it !in setOf("title", "creator", "language", "identifier") }
                .mapValues { it.value.toList() },
            missingFields = listOf("title", "creator", "language")
                .filter { values[it].isNullOrEmpty() }
                .toSet(),
        )
    }

    private fun parseCover(
        zip: ZipFile,
        names: Set<String>,
        opfPath: String,
        manifest: Map<String, Element>,
    ): EpubCover? {
        val packageDocument = manifest.values.firstOrNull()?.ownerDocument ?: return null
        val metadataElement = elements(packageDocument).firstOrNull { localName(it) == "metadata" }
        val coverIds = manifest.values
            .filter { it.getAttribute("properties").splitWhitespace().contains("cover-image") }
            .map { it.getAttribute("id") }
            .toMutableSet()
        metadataElement?.childElements()?.filter { localName(it) == "meta" }?.forEach { meta ->
            if (meta.getAttribute("name") == "cover") coverIds += meta.getAttribute("content")
        }
        val item = coverIds.asSequence().mapNotNull(manifest::get).firstOrNull() ?: return null
        val path = resolveEntry(opfPath, item.getAttribute("href")) ?: return null
        if (path !in names) return null
        return EpubCover(path, item.getAttribute("media-type"), readEntry(zip, names, path))
    }

    private fun parseNavigation(
        zip: ZipFile,
        names: Set<String>,
        opfPath: String,
        manifest: Map<String, Element>,
        spine: Element?,
    ): NavigationResult {
        val issues = mutableListOf<EpubNavigationIssue>()
        val navItem = manifest.values.firstOrNull {
            it.getAttribute("properties").splitWhitespace().contains("nav")
        }
        val ncxId = spine?.getAttribute("toc").orEmpty()
        val ncxItem = manifest[ncxId]
        if (navItem != null) {
            val path = resolveEntry(opfPath, navItem.getAttribute("href"))
            if (path != null && path in names) {
                val document = runCatching { parseXml(readEntry(zip, names, path), path) }.getOrNull()
                val toc = document?.let {
                    elements(it).firstOrNull { element ->
                        localName(element) == "nav" && semanticTypes(element).any { type -> type == "toc" }
                    }
                }
                val list = toc?.childElements()?.firstOrNull { localName(it) == "ol" }
                if (list != null) {
                    val parsed = parseTocList(list, path)
                    return NavigationResult(parsed.entries, issues + parsed.issues)
                }
                issues += navigationIssue(path, "EPUB 3 table of contents is missing its ordered list")
            } else {
                issues += navigationIssue(opfPath, "EPUB 3 navigation resource is unavailable")
            }
        }
        if (ncxItem != null) {
            val path = resolveEntry(opfPath, ncxItem.getAttribute("href"))
            if (path != null && path in names) {
                val document = runCatching { parseXml(readEntry(zip, names, path), path) }.getOrNull()
                val map = document?.let { elements(it).firstOrNull { element -> localName(element) == "navmap" } }
                if (map != null) {
                    val parsed = parseNcxPoints(map, path)
                    if (parsed.entries.isEmpty()) {
                        issues += navigationIssue(path, "The EPUB 2 navigation map contains no entries")
                    }
                    return NavigationResult(parsed.entries, issues + parsed.issues)
                }
                issues += navigationIssue(path, "EPUB 2 navigation map is missing")
            } else {
                issues += navigationIssue(opfPath, "EPUB 2 navigation resource is unavailable")
            }
        }
        return NavigationResult(emptyList(), issues)
    }

    private fun parseTocList(list: Element, navigationPath: String): NavigationResult {
        val issues = mutableListOf<EpubNavigationIssue>()
        val entries = list.childElements().filter { localName(it) == "li" }.map { li ->
            val link = li.childElements().firstOrNull { localName(it) == "a" }
            val target = link?.getAttribute("href")?.let { resolveTarget(navigationPath, it) }
            val childList = li.childElements().firstOrNull { localName(it) == "ol" || localName(it) == "ul" }
            if (link == null || target == null) {
                issues += navigationIssue(navigationPath, "A table-of-contents entry has no valid target")
            }
            val children = childList?.let { parseTocList(it, navigationPath) }
            if (children != null) issues += children.issues
            EpubTocEntry(
                title = directText(li),
                target = target,
                sourceLocator = sourceLocator(navigationPath, link ?: li),
                children = children?.entries.orEmpty(),
            )
        }
        if (entries.isEmpty()) issues += navigationIssue(navigationPath, "The table of contents contains no entries")
        return NavigationResult(entries, issues)
    }

    private fun parseNcxPoints(map: Element, navigationPath: String): NavigationResult {
        val issues = mutableListOf<EpubNavigationIssue>()
        val entries = map.childElements().filter { localName(it) == "navpoint" }.map { point ->
            val label = point.childElements().firstOrNull { localName(it) == "navlabel" }
            val content = point.childElements().firstOrNull { localName(it) == "content" }
            val target = content?.getAttribute("src")?.let { resolveTarget(navigationPath, it) }
            if (content == null || target == null) {
                issues += navigationIssue(navigationPath, "A navigation point has no valid content target")
            }
            val children = parseNcxPoints(point, navigationPath)
            issues += children.issues
            EpubTocEntry(
                title = label?.let(::textOf).orEmpty(),
                target = target,
                sourceLocator = sourceLocator(navigationPath, point),
                children = children.entries,
            )
        }
        return NavigationResult(entries, issues)
    }

    private fun navigationIssue(path: String, message: String): EpubNavigationIssue =
        EpubNavigationIssue(message, "$path#/navigation")

    private fun parseChapter(
        zip: ZipFile,
        names: Set<String>,
        opfPath: String,
        manifest: Map<String, Element>,
        itemRef: Element,
        ordinal: Int,
        toc: List<EpubTocEntry>,
        projectId: String,
    ): EpubChapter {
        val itemId = itemRef.getAttribute("idref")
        val item = manifest[itemId]
        val chapterId = "$projectId-chapter-$ordinal"
        val itemLocator = "$opfPath#/package/spine/itemref[${ordinal + 1}]"
        if (item == null) {
            return skippedChapter(chapterId, ordinal, tocTitle(toc, ordinal), null, itemLocator, "missing manifest item")
        }
        val path = resolveEntry(opfPath, item.getAttribute("href"))
        if (path == null || path !in names || item.getAttribute("media-type") != "application/xhtml+xml" &&
            item.getAttribute("media-type") != "text/html"
        ) {
            return skippedChapter(
                chapterId,
                ordinal,
                tocTitle(toc, ordinal, path).ifEmpty { itemId },
                path,
                itemLocator,
                "content is unavailable or unsupported",
            )
        }
        val document = try {
            parseXml(readEntry(zip, names, path), path)
        } catch (failure: ParseFailure) {
            return skippedChapter(
                chapterId,
                ordinal,
                tocTitle(toc, ordinal, path).ifEmpty { itemId },
                path,
                path,
                "content could not be parsed",
            )
        }
        val body = elements(document).firstOrNull { localName(it) == "body" } ?: document.documentElement
        val blocks = mapBlocks(body, path)
        val firstHeading = blocks.firstOrNull { it.type == NarrationBlockType.HEADING }?.sourceText
        val title = tocTitle(toc, ordinal, path).ifEmpty { firstHeading.orEmpty().ifEmpty { itemId } }
        val finalBlocks = if (blocks.isEmpty()) {
            listOf(skippedBlock(0, path, "chapter contains no narratable blocks"))
        } else {
            blocks.mapIndexed { index, block -> block.copy(ordinal = index) }
        }
        return EpubChapter(chapterId, ordinal, title, path, path, finalBlocks)
    }

    private fun mapBlocks(container: Element, entryPath: String): List<EpubNarrationBlock> {
        val result = mutableListOf<EpubNarrationBlock>()
        val inline = StringBuilder()
        fun flushInline() {
            val value = normalize(inline.toString())
            if (value.isNotEmpty()) {
                result += EpubNarrationBlock(0, NarrationBlockType.PARAGRAPH, value, sourceLocator(entryPath, container))
            }
            inline.clear()
        }
        fun visit(element: Element) {
            val name = localName(element)
            when {
                name in SKIPPED_ELEMENTS || isNavigation(element) ->
                    result += skippedBlock(
                        ordinal = 0,
                        locator = sourceLocator(entryPath, element),
                        reason = "unsupported or non-narrative content",
                        sourceText = textOf(element),
                    )
                isHeading(name) -> result += block(element, entryPath, NarrationBlockType.HEADING, textOf(element), name.drop(1).toInt())
                name == "p" -> result += block(element, entryPath, NarrationBlockType.PARAGRAPH, textOf(element))
                name == "blockquote" || semanticTypes(element).any { it in QUOTE_TYPES } ->
                    result += block(element, entryPath, NarrationBlockType.QUOTE, textOf(element))
                name == "li" -> result += block(element, entryPath, NarrationBlockType.LIST_ITEM, textOf(element, ignored = LIST_ELEMENTS))
                name == "ul" || name == "ol" -> element.childElements().filter { localName(it) == "li" }.forEach(::visit)
                isPoetry(element) -> result += block(
                    element,
                    entryPath,
                    NarrationBlockType.POETRY,
                    textOf(element, preserveBreaks = true),
                    preserveBreaks = true,
                )
                isNote(element) -> result += block(element, entryPath, NarrationBlockType.NOTE, textOf(element))
                isCaption(element) -> result += block(element, entryPath, NarrationBlockType.CAPTION, textOf(element))
                isSceneBreak(element) -> result += block(element, entryPath, NarrationBlockType.SCENE_BREAK, textOf(element))
                name in UNSUPPORTED_BLOCK_ELEMENTS ->
                    result += skippedBlock(
                        ordinal = 0,
                        locator = sourceLocator(entryPath, element),
                        reason = "unsupported block construct",
                        sourceText = textOf(element),
                    )
                else -> mapChildren(element, entryPath, ::visit, inline, ::flushInline)
            }
        }
        visitChildren(container, ::visit, inline, ::flushInline)
        flushInline()
        return result
    }

    private fun mapChildren(
        element: Element,
        entryPath: String,
        visit: (Element) -> Unit,
        inline: StringBuilder,
        flushInline: () -> Unit,
    ) {
        childNodes(element).forEach { child ->
            if (child.nodeType == Node.TEXT_NODE) {
                inline.append(child.nodeValue)
            } else if (child is Element && localName(child) in INLINE_ELEMENTS) {
                inline.append(textOf(child))
            } else if (child is Element) {
                flushInline()
                visit(child)
            }
        }
    }

    private fun visitChildren(
        element: Element,
        visit: (Element) -> Unit,
        inline: StringBuilder,
        flushInline: () -> Unit,
    ) {
        childNodes(element).forEach { child ->
            when {
                child.nodeType == Node.TEXT_NODE -> inline.append(child.nodeValue)
                child is Element -> {
                    if (localName(child) in INLINE_ELEMENTS) inline.append(textOf(child))
                    else {
                        flushInline()
                        visit(child)
                    }
                }
            }
        }
    }

    private fun block(
        element: Element,
        entryPath: String,
        type: NarrationBlockType,
        text: String,
        headingLevel: Int? = null,
        preserveBreaks: Boolean = false,
    ): EpubNarrationBlock = EpubNarrationBlock(
        ordinal = 0,
        type = type,
        sourceText = if (preserveBreaks) text else normalize(text),
        sourceLocator = sourceLocator(entryPath, element),
        headingLevel = headingLevel,
    )

    private fun skippedChapter(
        id: String,
        ordinal: Int,
        title: String,
        path: String?,
        locator: String,
        reason: String,
    ): EpubChapter = EpubChapter(
        id = id,
        ordinal = ordinal,
        title = title.ifEmpty { "Chapter ${ordinal + 1}" },
        sourcePath = path,
        sourceLocator = locator,
        blocks = listOf(skippedBlock(0, locator, reason)),
    )

    private fun skippedBlock(
        ordinal: Int,
        locator: String,
        reason: String,
        sourceText: String = "",
    ): EpubNarrationBlock =
        EpubNarrationBlock(ordinal, NarrationBlockType.SKIPPED, sourceText, locator, skippedReason = reason)

    private fun tocTitle(toc: List<EpubTocEntry>, ordinal: Int, path: String? = null): String =
        flattenToc(toc).firstOrNull { entry ->
            path != null && entry.target?.substringBefore('#') == path
        }?.title ?: flattenToc(toc).getOrNull(ordinal)?.title.orEmpty()

    private fun flattenToc(entries: List<EpubTocEntry>): List<EpubTocEntry> =
        entries.flatMap { listOf(it) + flattenToc(it.children) }

    private fun parseXml(bytes: ByteArray, entryPath: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: Exception) {
            fail(EpubParseFailureCode.INVALID_PACKAGE, entryPath)
        }
        return try {
            val builder = factory.newDocumentBuilder()
            builder.parse(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            fail(EpubParseFailureCode.INVALID_PACKAGE, entryPath)
        }
    }

    private fun readEntry(zip: ZipFile, names: Set<String>, path: String): ByteArray {
        if (path !in names) fail(EpubParseFailureCode.INVALID_CONTAINER, path)
        return zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
    }

    private fun resolveTarget(base: String, href: String): String? {
        val fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val path = resolveEntry(base, href.substringBefore('#')) ?: return null
        return if (fragment == null) path else "$path#$fragment"
    }

    private fun resolveEntry(base: String, href: String): String? {
        if (href.isEmpty()) return base
        if (href.startsWith("/") || href.contains("://")) return null
        val decoded = try {
            URI(null, null, href, null).path
        } catch (_: Exception) {
            return null
        }
        val parts = (base.substringBeforeLast('/', "") + "/" + decoded).split('/')
        val resolved = ArrayDeque<String>()
        parts.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (resolved.isEmpty()) return null else resolved.removeLast()
                else -> resolved.addLast(part)
            }
        }
        return resolved.joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun elements(document: Document): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(node: Node) {
            if (node is Element) result += node
            childNodes(node).forEach(::visit)
        }
        visit(document)
        return result
    }

    private fun Element.childElements(): List<Element> =
        childNodes(this).filterIsInstance<Element>().toList()

    private fun childNodes(node: Node): Sequence<Node> =
        (0 until node.childNodes.length).asSequence().map(node.childNodes::item)

    private fun textOf(element: Element, preserveBreaks: Boolean = false, ignored: Set<String> = emptySet()): String {
        val output = StringBuilder()
        fun append(node: Node) {
            if (node is Element) {
                val name = localName(node)
                if (name in ignored) return
                if (name == "br") {
                    if (preserveBreaks) output.append('\n') else output.append(' ')
                    return
                }
            }
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) output.append(node.nodeValue)
            childNodes(node).forEach(::append)
        }
        append(element)
        return if (preserveBreaks) output.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n")
        else normalize(output.toString())
    }

    private fun directText(element: Element): String {
        val output = StringBuilder()
        childNodes(element).forEach { child ->
            when {
                child.nodeType == Node.TEXT_NODE -> output.append(child.nodeValue)
                child is Element && localName(child) !in LIST_ELEMENTS -> output.append(textOf(child))
            }
        }
        return normalize(output.toString())
    }

    private fun sourceLocator(entryPath: String, element: Element): String {
        val parts = mutableListOf<String>()
        var current: Node? = element
        while (current is Element) {
            var index = 0
            var sibling = current.previousSibling
            while (sibling != null) {
                if (sibling is Element && localName(sibling) == localName(current)) index++
                sibling = sibling.previousSibling
            }
            parts += "/${localName(current)}[${index + 1}]"
            current = current.parentNode
        }
        return "$entryPath#${parts.asReversed().joinToString("")}"
    }

    private fun semanticTypes(element: Element): Set<String> =
        (element.getAttributeNS(EPUB_NS, "type").ifEmpty { element.getAttribute("epub:type") })
            .splitWhitespace()
            .map { it.substringAfterLast(':') }
            .toSet()

    private fun isNavigation(element: Element): Boolean = localName(element) == "nav" &&
        semanticTypes(element).contains("toc")

    private fun isPoetry(element: Element): Boolean =
        semanticTypes(element).any { it in POETRY_TYPES } ||
            element.getAttribute("class").splitWhitespace().any { it in POETRY_TYPES }

    private fun isNote(element: Element): Boolean =
        semanticTypes(element).any { it in NOTE_TYPES } ||
            element.getAttribute("class").splitWhitespace().any { it in NOTE_TYPES }

    private fun isCaption(element: Element): Boolean = localName(element) in setOf("caption", "figcaption") ||
        semanticTypes(element).contains("caption")

    private fun isSceneBreak(element: Element): Boolean = localName(element) == "hr" ||
        semanticTypes(element).any { it in SCENE_TYPES } ||
            element.getAttribute("class").splitWhitespace().any { it in SCENE_TYPES }

    private fun isHeading(name: String): Boolean = name.length == 2 && name[0] == 'h' && name[1] in '1'..'6'

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfterLast(':').lowercase()

    private fun fail(code: EpubParseFailureCode, entryPath: String? = null): Nothing =
        throw ParseFailure(code, entryPath)

    private class ParseFailure(val code: EpubParseFailureCode, val entryPath: String?) : Exception(null, null, false, false)

    private data class NavigationResult(
        val entries: List<EpubTocEntry>,
        val issues: List<EpubNavigationIssue>,
    )

    private companion object {
        const val EPUB_NS = "http://www.idpf.org/2007/ops"
        val INLINE_ELEMENTS = setOf("a", "abbr", "b", "cite", "code", "em", "i", "q", "small", "span", "strong", "sub", "sup")
        val LIST_ELEMENTS = setOf("ul", "ol")
        val SKIPPED_ELEMENTS = setOf("script", "style", "link", "img", "svg", "video", "audio", "canvas", "iframe", "object")
        val UNSUPPORTED_BLOCK_ELEMENTS = setOf("table")
        val QUOTE_TYPES = setOf("quote", "epigraph")
        val POETRY_TYPES = setOf("poem", "poetry", "verse")
        val NOTE_TYPES = setOf("footnote", "endnote", "rearnote", "note")
        val SCENE_TYPES = setOf("separator", "scene-break", "scenebreak")

        fun String.splitWhitespace(): List<String> = trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    }
}
