package com.homoludens.citacknjiga.core.storage

import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.AudioSegmentStatus
import com.homoludens.citacknjiga.core.database.BookProjectEntity
import com.homoludens.citacknjiga.core.database.ChapterEntity
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

public class ProjectArtifactCleanupTest {
    @Test
    public fun inventoryAndCleanupCoverCanonicalAndTemporaryProjectArtifacts() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val source = storage.sourceDocument("book").apply { parentFile!!.mkdirs(); writeText("source") }
        val canonical = storage.canonicalChapterText("book", "chapter").apply {
            parentFile!!.mkdirs()
            writeText("canonical")
        }
        val cover = storage.coverImage("book").apply { parentFile!!.mkdirs(); writeText("cover") }
        val audio = storage.readySegmentAudio("book", "chapter", "segment").apply {
            parentFile!!.mkdirs()
            writeText("audio")
        }
        val warning = storage.importWarnings("book").apply { parentFile!!.mkdirs(); writeText("warning") }
        val temporary = storage.temporaryFile("epub-book", "source.epub").apply {
            parentFile!!.mkdirs()
            writeText("temporary")
        }
        val project = project(source.path, cover.path)
        val chapter = ChapterEntity("chapter", project.id, 0, "Chapter", canonicalMarkdownPath = canonical.path, createdAt = 1, updatedAt = 1)
        val segment = AudioSegmentEntity(
            id = "segment",
            chapterId = chapter.id,
            narrationBlockId = "block",
            sequence = 0,
            chunkOrdinal = 0,
            status = AudioSegmentStatus.READY,
            audioPath = audio.path,
            createdAt = 1,
            updatedAt = 1,
        )
        val externalExport = File(root.parentFile, "export-${root.name}.m4a").apply { writeText("keep") }

        val inventory = storage.projectArtifactInventory(project, listOf(chapter), listOf(segment))
        assertTrue(source in inventory.sourceFiles)
        assertTrue(canonical in inventory.canonicalTextFiles)
        assertTrue(temporary in inventory.temporaryFiles)
        assertFalse(inventory.allFiles.any { it.path == project.sourceUri })
        assertFalse(inventory.allFiles.any { it == externalExport })

        val result = ProjectArtifactCleanupPolicy(storage).cleanup(inventory)

        assertEquals(setOf(source, canonical, cover, audio, warning, temporary), result.deletedFiles)
        assertFalse(source.exists())
        assertFalse(canonical.exists())
        assertFalse(cover.exists())
        assertFalse(audio.exists())
        assertFalse(warning.exists())
        assertFalse(temporary.exists())
        assertTrue(externalExport.exists())
    }

    @Test
    public fun cleanupRejectsTraversalAndOutsideRootBeforeDeleting() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val keep = storage.sourceDocument("book").apply { parentFile!!.mkdirs(); writeText("keep") }
        val outside = File(root.parentFile, "outside-${root.name}").apply { writeText("outside") }
        val inventory = ProjectArtifactInventory(
            sourceFiles = setOf(keep),
            temporaryFiles = setOf(File(root, "temporary/../../${outside.name}")),
        )

        val failure = runCatching { ProjectArtifactCleanupPolicy(storage).cleanup(inventory) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(keep.exists())
        assertTrue(outside.exists())
    }

    private fun project(sourcePath: String, coverPath: String) = BookProjectEntity(
        id = "book",
        title = "Book",
        sourceUri = "content://provider/original.pdf",
        sourceFingerprint = "a".repeat(64),
        sourcePath = sourcePath,
        coverPath = coverPath,
        createdAt = 1,
        updatedAt = 1,
    )
}
