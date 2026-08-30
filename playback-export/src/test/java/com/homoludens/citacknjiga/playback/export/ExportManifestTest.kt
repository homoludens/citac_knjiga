package com.homoludens.citacknjiga.playback.export

import com.google.gson.JsonParser
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import com.homoludens.citacknjiga.core.database.ChapterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class ExportManifestTest {
    @Test
    public fun fixtureIsVersionedAndRoundTripsDeterministically() {
        val manifest = ExportManifestCodec.decode(fixture())

        val encoded = ExportManifestCodec.encode(manifest)

        assertEquals(encoded, ExportManifestCodec.encode(ExportManifestCodec.decode(encoded)))
        assertTrue(encoded.indexOf("\"schema\"") < encoded.indexOf("\"book\""))
        assertTrue(encoded.indexOf("\"chapters\"") < encoded.indexOf("\"attribution_refs\""))
        assertFalse(encoded.contains("source_uri"))
        assertFalse(encoded.contains("audio_path"))
        assertFalse(encoded.contains("source_text"))
    }

    @Test
    public fun schemaFixturePinsIdentityAndVersion() {
        val schema = checkNotNull(javaClass.getResourceAsStream("/export-manifest-v1.schema.json"))
            .bufferedReader()
            .use { JsonParser.parseReader(it).asJsonObject }

        assertEquals("https://citac-knjiga.local/schemas/export-manifest-v1.schema.json", schema.get("\$id").asString)
        assertEquals("citac-knjiga-export-manifest", schema.getAsJsonObject("\$defs")
            .getAsJsonObject("schema").getAsJsonObject("properties").get("id").asJsonObject.get("const").asString)
        assertEquals(1, schema.getAsJsonObject("\$defs").getAsJsonObject("schema")
            .getAsJsonObject("properties").get("version").asJsonObject.get("const").asInt)
    }

    @Test
    public fun missingRequiredFieldIsRejected() {
        val root = JsonParser.parseString(fixture()).asJsonObject
        root.remove("source")

        val failure = runCatching { ExportManifestCodec.decode(root.toString()) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    public fun inconsistentDurationHashOrderAndPathAreRejected() {
        val root = JsonParser.parseString(fixture()).asJsonObject
        root.getAsJsonArray("chapters").first().asJsonObject.addProperty("duration_ms", 999L)
        assertRejected(root)

        val pathRoot = JsonParser.parseString(fixture()).asJsonObject
        pathRoot.getAsJsonArray("chapters").first().asJsonObject
            .getAsJsonArray("files").first().asJsonObject.addProperty("path", "content://private/book.m4a")
        assertRejected(pathRoot)

        val hashRoot = JsonParser.parseString(fixture()).asJsonObject
        hashRoot.getAsJsonArray("chapters").first().asJsonObject
            .getAsJsonArray("files").first().asJsonObject.addProperty("sha256", "not-a-hash")
        assertRejected(hashRoot)
    }

    @Test
    public fun readyRoomSegmentProjectionUsesPortablePathAndProvenance() {
        val segment = readySegment()
        val file = ExportManifestFile.fromReadySegment(segment, "chapters/0001.wav", "audio/wav")
        val manifest = ExportManifestFactory.fromRoom(
            project = BookProjectEntity(
                id = "book-1",
                title = "Book",
                author = "Author",
                sourceUri = "content://private/document-with-sensitive-path",
                sourceFingerprint = "1111111111111111111111111111111111111111111111111111111111111111",
                status = com.homoludens.citacknjiga.core.database.BookProjectStatus.COMPLETED,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            chapters = listOf(ChapterEntity("chapter-1", "book-1", 0, "Chapter", status = ChapterStatus.READY, createdAt = 1L, updatedAt = 1L)),
            filesByChapter = mapOf("chapter-1" to listOf(file)),
            attributionRefs = listOf(ExportAttributionReference("project", "Project", "https://example.com/project", "license-project", true)),
        )

        val encoded = ExportManifestCodec.encode(manifest)

        assertTrue(encoded.contains("chapters/0001.wav"))
        assertFalse(encoded.contains("content://private/document-with-sensitive-path"))
        assertFalse(encoded.contains("/data/user/0"))
    }

    @Test
    public fun nonReadySegmentCannotEnterManifest() {
        val failure = runCatching {
            ExportManifestFile.fromReadySegment(readySegment().copy(status = AudioSegmentStatus.PENDING), "chapter.m4a", "audio/mp4")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun assertRejected(root: com.google.gson.JsonObject) {
        assertTrue(runCatching { ExportManifestCodec.decode(root.toString()) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun readySegment(): AudioSegmentEntity = AudioSegmentEntity(
        id = "segment-1",
        chapterId = "chapter-1",
        narrationBlockId = "block-1",
        sequence = 0,
        chunkOrdinal = 0,
        generationKey = HASH_3,
        generationRunId = "run-1",
        modelPackageId = "model-1",
        modelPackageSha256 = HASH_4,
        voiceSha256 = HASH_5,
        preprocessingVersion = "preprocessing-v1",
        pronunciationVersion = "pronunciation-v1",
        inferenceSettingsHash = HASH_6,
        audioProcessingVersion = "audio-v1",
        status = AudioSegmentStatus.READY,
        audioPath = "/data/user/0/app/files/ready-audio/private-document-text.m4a",
        audioSha256 = HASH_2,
        sizeBytes = 100L,
        durationMs = 100L,
        sampleRate = 24_000,
        channels = 1,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun fixture(): String = checkNotNull(javaClass.getResourceAsStream("/export-manifest-v1.json"))
        .bufferedReader()
        .use { it.readText() }

    private companion object {
        const val HASH_2 = "2222222222222222222222222222222222222222222222222222222222222222"
        const val HASH_3 = "3333333333333333333333333333333333333333333333333333333333333333"
        const val HASH_4 = "4444444444444444444444444444444444444444444444444444444444444444"
        const val HASH_5 = "5555555555555555555555555555555555555555555555555555555555555555"
        const val HASH_6 = "6666666666666666666666666666666666666666666666666666666666666666"
    }
}
