package com.homoludens.citacknjiga.core.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class AtomicArtifactStoreTest {
    @Test
    public fun publicationValidatesAndReturnsChecksum() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val destination = storage.readySegmentAudio("book", "chapter", "segment")
        val payload = "verified audio".toByteArray()

        val artifact = store.publish(
            ownerId = "generation",
            destination = destination,
            writer = { it.write(payload) },
            validator = { file -> assertEquals(payload.toList(), file.readBytes().toList()) },
        )

        assertEquals(destination, artifact.file)
        assertEquals(payload.size.toLong(), artifact.sizeBytes)
        assertEquals("08d24debeaf73ae8fb0c06a38263ccb83ae266fa28bf984536d525915e3022bf", artifact.sha256)
        assertEquals(payload.toList(), destination.readBytes().toList())
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
    }

    @Test
    public fun validatorFailureDoesNotPublishReadyArtifact() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val destination = storage.readySegmentAudio("book", "chapter", "bad")
        checkNotNull(destination.parentFile).mkdirs()
        destination.writeText("previously verified audio")

        val failure = runCatching {
            store.publish(
                ownerId = "generation",
                destination = destination,
                writer = { it.write("invalid".toByteArray()) },
                validator = { error("audio validation failed") },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("previously verified audio", destination.readText())
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
    }

    @Test
    public fun cleanupRemovesStaleTemporaryAndOrphanFiles() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val store = AtomicArtifactStore(storage)
        val staleTemporary = storage.temporaryFile("old", "copy.tmp").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("old")
            setLastModified(1_000)
        }
        val recentTemporary = storage.temporaryFile("new", "copy.tmp").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("new")
            setLastModified(9_000)
        }
        val kept = storage.readySegmentAudio("book", "chapter", "kept").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("kept")
            setLastModified(1_000)
        }
        val orphan = storage.readySegmentAudio("book", "chapter", "orphan").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("orphan")
            setLastModified(1_000)
        }

        assertEquals(1, store.cleanupStaleTemporaryFiles(5_000, nowMillis = 10_000))
        assertFalse(staleTemporary.exists())
        assertTrue(recentTemporary.exists())
        assertEquals(
            1,
            store.cleanupOrphanFiles(
                storage.readyAudioDirectory,
                referencedFiles = listOf(kept),
                maxAgeMillis = 5_000,
                nowMillis = 10_000,
            ),
        )
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
    }

    @Test
    public fun publicationRejectsPathsOutsidePrivateRoot() {
        val root = createTempDirectory().toFile()
        val store = AtomicArtifactStore(AppPrivateStorage(root))
        val outside = File(root.parentFile, "outside-${root.name}")

        val failure = runCatching {
            store.publish("owner", outside, writer = { it.write(1) })
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(outside.exists())
    }
}
