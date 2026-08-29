package com.homoludens.citacknjiga.document.epub

import com.homoludens.citacknjiga.core.database.NarrationBlockType
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubDocumentParserTest {
    @Test
    public fun epub3MapsMetadataCoverNavigationAndDeclaredSpine() {
        val staged = stageFixture("serbian-epub3.epub", "epub3")

        val result = EpubDocumentParser(staged.storage).parse(staged.source)

        val document = (result as EpubParseResult.Parsed).document
        assertEquals("Мала књига за проверу", document.metadata.title)
        assertEquals(listOf("Ана Тест"), document.metadata.authors)
        assertEquals("sr", document.metadata.language)
        assertEquals("OEBPS/images/cover.svg", document.cover?.sourcePath)
        assertEquals("image/svg+xml", document.cover?.mediaType)
        assertTrue((document.cover?.bytes?.size ?: 0) > 0)
        assertEquals(listOf("Поглавље Б", "Поглавље А"), document.tableOfContents.map { it.title })
        assertEquals(
            listOf("OEBPS/text/b.xhtml", "OEBPS/text/a.xhtml"),
            document.chapters.map { it.sourcePath },
        )
        assertEquals(listOf("Поглавље Б", "Поглавље А"), document.chapters.map { it.title })
        assertEquals(
            "OEBPS/text/b.xhtml#/html[1]/body[1]/h1[1]",
            document.chapters.first().blocks.first().sourceLocator,
        )
        assertEquals("epub3-chapter-0", document.chapters.first().id)
    }

    @Test
    public fun epub2KeepsDeclaredSpineAndUnreachableContentExplicitlySkipped() {
        val staged = stageFixture("serbian-epub2.epub", "epub2")

        val result = EpubDocumentParser(staged.storage).parse(staged.source)

        val document = (result as EpubParseResult.Parsed).document
        assertEquals(listOf("Други лист", "Први лист"), document.chapters.map { it.title })
        assertEquals(listOf("OEBPS/OEBPS/chapters/zeta.xhtml", "OEBPS/OEBPS/chapters/alpha.xhtml"), document.chapters.map { it.sourcePath })
        assertEquals(listOf(NarrationBlockType.SKIPPED, NarrationBlockType.SKIPPED), document.chapters.map { it.blocks.single().type })
        assertTrue(document.chapters.first().blocks.single().skippedReason!!.contains("unavailable"))
    }

    @Test
    public fun typedBlocksAndStableLocatorsPreserveRichBodyContent() {
        val staged = stageArchive("rich", richEntries())

        val result = EpubDocumentParser(staged.storage).parse(staged.source)

        val document = (result as EpubParseResult.Parsed).document
        val blocks = document.chapters.first().blocks
        assertEquals(
            listOf(
                NarrationBlockType.HEADING,
                NarrationBlockType.HEADING,
                NarrationBlockType.PARAGRAPH,
                NarrationBlockType.LIST_ITEM,
                NarrationBlockType.LIST_ITEM,
                NarrationBlockType.QUOTE,
                NarrationBlockType.POETRY,
                NarrationBlockType.SKIPPED,
                NarrationBlockType.CAPTION,
                NarrationBlockType.NOTE,
                NarrationBlockType.SCENE_BREAK,
                NarrationBlockType.SKIPPED,
            ),
            blocks.map { it.type },
        )
        assertEquals("Тиха река\nноси светлост", blocks[6].sourceText)
        assertEquals("Бела слика", blocks[8].sourceText)
        assertEquals("OEBPS/chapter.xhtml#/html[1]/body[1]/h1[1]", blocks[0].sourceLocator)
        assertEquals((0 until blocks.size).toList(), blocks.map { it.ordinal })
        assertEquals(blocks.size, blocks.map { it.sourceLocator }.toSet().size)
    }

    @Test
    public fun missingManifestItemBecomesAnExplicitSkippedChapterAndProjectsToRoom() {
        val staged = stageArchive("ambiguous", richEntries(includeMissingSpineItem = true))

        val result = EpubDocumentParser(staged.storage).parse(staged.source)

        val document = (result as EpubParseResult.Parsed).document
        val projection = document.toRoomProjection(staged.source, now = 123L)
        assertEquals(2, projection.chapters.size)
        assertEquals(NarrationBlockType.SKIPPED, projection.narrationBlocks.last().blockType)
        assertEquals("ambiguous-chapter-0-block-0", projection.narrationBlocks.first().id)
        assertEquals("ambiguous-chapter-1", projection.chapters.last().id)
        assertEquals("missing manifest item", document.chapters.last().blocks.single().skippedReason)
    }

    @Test
    public fun parserRepeatsSecurityGateAndRejectsOutsidePrivateSource() {
        val malformed = stageFixture("malformed-content.epub", "malformed")
        val rejection = EpubDocumentParser(malformed.storage).parse(malformed.source) as EpubParseResult.Rejected
        assertEquals(EpubSecurityFailureCode.MALFORMED_XML, rejection.diagnostic.code)

        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val outside = File(root.parentFile, "outside.epub").apply { writeBytes(fixtureBytes("serbian-epub3.epub")) }
        val source = ImportedEpubSource("outside", "content://outside", "fingerprint", outside, outside.length())
        val result = EpubDocumentParser(storage).parse(source)
        assertEquals(EpubParseFailureCode.SOURCE_NOT_PRIVATE, (result as EpubParseResult.Failed).code)
        outside.delete()
    }

    private fun stageFixture(name: String, projectId: String): StagedSource =
        stageArchive(projectId, fixtureBytes(name))

    private fun stageArchive(projectId: String, entries: ByteArray): StagedSource {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val sourceFile = storage.sourceDocument(projectId).apply {
            parentFile!!.mkdirs()
            writeBytes(entries)
        }
        return StagedSource(
            storage = storage,
            source = ImportedEpubSource(projectId, "content://$projectId", projectId, sourceFile, sourceFile.length()),
        )
    }

    private fun stageArchive(projectId: String, entries: List<Pair<String, ByteArray>>): StagedSource {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val sourceFile = storage.sourceDocument(projectId).apply { parentFile!!.mkdirs() }
        ZipOutputStream(FileOutputStream(sourceFile)).use { output ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return StagedSource(
            storage = storage,
            source = ImportedEpubSource(projectId, "content://$projectId", projectId, sourceFile, sourceFile.length()),
        )
    }

    private fun richEntries(includeMissingSpineItem: Boolean = false): List<Pair<String, ByteArray>> {
        val missing = if (includeMissingSpineItem) "<itemref idref=\"missing\"/>" else ""
        val packageXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Богат садржај</dc:title><dc:language>sr</dc:language></metadata>
              <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="cover" href="cover.svg" media-type="image/svg+xml" properties="cover-image"/></manifest>
              <spine><itemref idref="chapter"/>$missing</spine>
            </package>
        """.trimIndent().toByteArray()
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Богат садржај</title></head><body>
            <h1>Наслов</h1><h2>Поднаслов</h2><p>Обичан пасус.</p>
            <ul><li>Прва ставка</li><li>Друга ставка</li></ul>
            <blockquote><p>Цитирани текст.</p></blockquote>
            <div class="poetry"><p>Тиха река<br/>носи светлост</p></div>
            <figure><img src="image.png"/><figcaption>Бела слика</figcaption></figure>
            <aside epub:type="footnote"><p>Ауторова белешка.</p></aside><hr/><script>не изговарај</script>
            </body></html>
        """.trimIndent().toByteArray()
        return listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>
            """.trimIndent().toByteArray(),
            "OEBPS/content.opf" to packageXml,
            "OEBPS/chapter.xhtml" to body,
            "OEBPS/cover.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(),
        )
    }

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).use { it.readBytes() }

    private data class StagedSource(val storage: AppPrivateStorage, val source: ImportedEpubSource)
}
