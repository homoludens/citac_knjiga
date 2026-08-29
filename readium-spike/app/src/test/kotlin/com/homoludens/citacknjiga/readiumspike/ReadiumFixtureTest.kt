package com.homoludens.citacknjiga.readiumspike

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.flatten
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.logging.ListWarningLogger
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadiumFixtureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixtureRoot = File(
        System.getProperty("fixtureRoot")
            ?: error("fixtureRoot test property is required")
    )

    @Test
    fun opensEpub2AndPreservesMetadataSpineAndNcx() = runBlocking {
        val result = open("serbian-epub2.epub")

        assertEquals("Мала књига за проверу", result.publication.metadata.title)
        assertEquals(listOf("Ана Тест"), result.publication.metadata.authors.map { it.name })
        assertEquals(listOf("sr"), result.publication.metadata.languages)
        assertTrue(result.publication.resources.any { it.rels.contains("cover") })
        assertEquals(
            listOf("OEBPS/OEBPS/chapters/zeta.xhtml", "OEBPS/OEBPS/chapters/alpha.xhtml"),
            result.publication.readingOrder.map { it.href.toString() }
        )
        assertEquals(
            listOf("Други лист", "Први лист"),
            result.publication.tableOfContents.flatten().map { it.title }
        )
        println("READIUM_SPIKE_CONTENT fixture=serbian-epub2 parsed=${result.publication.readingOrder.map { readContent(result.publication, it) }}")
        report("serbian-epub2", result)
        result.publication.close()
    }

    @Test
    fun opensEpub3AndPreservesMetadataSpineAndNav() = runBlocking {
        val result = open("serbian-epub3.epub")

        assertEquals("Мала књига за проверу", result.publication.metadata.title)
        assertEquals(listOf("Ана Тест"), result.publication.metadata.authors.map { it.name })
        assertEquals(listOf("sr"), result.publication.metadata.languages)
        assertTrue(result.publication.resources.any { it.rels.contains("cover") })
        assertEquals(
            listOf("OEBPS/text/b.xhtml", "OEBPS/text/a.xhtml"),
            result.publication.readingOrder.map { it.href.toString() }
        )
        assertEquals(
            listOf("Поглавље Б", "Поглавље А"),
            result.publication.tableOfContents.flatten().map { it.title }
        )
        val content = result.publication.readingOrder.map { readContent(result.publication, it) }
        assertTrue(content.all { it != null && it.contains("<h1>") && it.contains("<p>") })
        assertTrue(content[0]!!.contains("Бета текст."))
        assertTrue(content[1]!!.contains("Алфа текст."))
        println("READIUM_SPIKE_CONTENT fixture=serbian-epub3 parsed=${content.map { it != null }}")
        report("serbian-epub3", result)
        result.publication.close()
    }

    @Test
    fun observesMalformedFixturesWithoutClaimingRecovery() = runBlocking {
        listOf("malformed-content.epub", "malformed-navigation.epub").forEach { name ->
            val result = runCatching { open(name) }
            println(
                "READIUM_SPIKE fixture=$name " +
                    "outcome=${if (result.isSuccess) "opened" else "failed"} " +
                    "error=${result.exceptionOrNull()?.message ?: "none"}"
            )
            result.getOrNull()?.let {
                report(name.removeSuffix(".epub"), it)
                it.publication.close()
            }
        }
    }

    private suspend fun open(name: String): Opened {
        val httpClient = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, httpClient)
        val parser = DefaultPublicationParser(context, httpClient, retriever, null)
        val warnings = ListWarningLogger()
        val asset = retriever.retrieve(File(fixtureRoot, name)).getOrElse {
            error("retrieve $name: $it")
        }
        val publication = PublicationOpener(parser).open(
            asset,
            allowUserInteraction = false,
            warnings = warnings
        ).getOrElse {
            error("open $name: $it")
        }
        return Opened(publication, warnings.warnings.map { it.message })
    }

    private fun report(name: String, result: Opened) {
        assertNotNull(result.publication.metadata.title)
        println(
            "READIUM_SPIKE fixture=$name " +
                "title=${result.publication.metadata.title} " +
                "authors=${result.publication.metadata.authors.map { it.name }} " +
                "languages=${result.publication.metadata.languages} " +
                "cover=${result.publication.resources.any { it.rels.contains("cover") }} " +
                "spine=${result.publication.readingOrder.map { it.href }} " +
                "toc=${result.publication.tableOfContents.flatten().map { it.title }} " +
                "warnings=${result.warnings.size}"
        )
    }

    private suspend fun readContent(
        publication: org.readium.r2.shared.publication.Publication,
        link: org.readium.r2.shared.publication.Link,
    ): String? {
        val resource = publication.get(link) ?: return null
        val length = resource.length().getOrElse { return null }
        if (length == 0L) return ""
        return resource.read(0L..(length - 1)).getOrElse { return null }.toString(Charsets.UTF_8)
    }

    private data class Opened(
        val publication: org.readium.r2.shared.publication.Publication,
        val warnings: List<String>,
    )
}
