package com.homoludens.citacknjiga.core.storage

import com.homoludens.citacknjiga.core.generation.GenerationFailureCategory
import com.homoludens.citacknjiga.core.generation.GenerationFailureException
import com.homoludens.citacknjiga.core.generation.GenerationFailurePhase
import com.homoludens.citacknjiga.core.generation.GenerationFailurePolicy
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class GenerationStoragePolicyTest {
    @Test
    public fun estimateUsesLargestTemporaryFileAndAllReadyAudio() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val policy = policy(storage, available = 10_000)

        val estimate = policy.estimate(
            listOf(
                GenerationStorageRequest("first", 100),
                GenerationStorageRequest("second", 300),
            ),
        )

        assertEquals(300, estimate.temporaryBytes)
        assertEquals(400, estimate.readyAudioBytes)
        assertEquals(70, estimate.safetyMarginBytes)
        assertEquals(770, estimate.requiredBytes)
    }

    @Test
    public fun minimumSafetyMarginAppliesWhenPercentageWouldBeSmaller() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val policy = GenerationStoragePolicy(
            storage = storage,
            safetyMarginPercent = 10,
            minimumSafetyMarginBytes = 64,
            availableBytes = { 1_000 },
        )

        val estimate = policy.estimate(listOf(GenerationStorageRequest("one", 2)))

        assertEquals(64, estimate.safetyMarginBytes)
        assertEquals(68, estimate.requiredBytes)
    }

    @Test
    public fun insufficientCapacityIsAStableStorageFailure() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val policy = policy(storage, available = 769)

        val failure = runCatching {
            policy.requireCapacity(
                listOf(
                    GenerationStorageRequest("first", 100),
                    GenerationStorageRequest("second", 300),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is GenerationFailureException)
        val typed = failure as GenerationFailureException
        assertEquals(GenerationFailureCategory.STORAGE, typed.category)
        assertEquals("INSUFFICIENT_STORAGE", typed.stableCode)
        assertTrue(typed.message!!.contains("cleanup"))
        assertFalse(GenerationFailurePolicy.classify(typed, GenerationFailurePhase.PUBLICATION).retryable)
    }

    @Test
    public fun cleanupChoiceOnlyRemovesSelectedGenerationArtifacts() {
        val root = createTempDirectory().toFile()
        val storage = AppPrivateStorage(root)
        val cleanup = GenerationStorageCleanup(storage, availableBytes = { 123 })
        val source = storage.sourceDocument("book").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("keep source")
        }
        val metadata = storage.canonicalChapterText("book", "chapter").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("keep metadata")
        }
        val staleTemporary = storage.temporaryFile("run", "stale.tmp").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("temporary")
            setLastModified(1_000)
        }
        val referenced = storage.readySegmentAudio("book", "chapter", "referenced").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("keep audio")
            setLastModified(1_000)
        }
        val orphan = storage.readySegmentAudio("book", "chapter", "orphan").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("orphan audio")
            setLastModified(1_000)
        }

        val temporaryResult = cleanup.cleanup(
            choice = GenerationCleanupChoice.STALE_TEMPORARY,
            maxAgeMillis = 5_000,
            nowMillis = 10_000,
        )
        val audioResult = cleanup.cleanup(
            choice = GenerationCleanupChoice.ORPHAN_READY_AUDIO,
            referencedReadyAudio = listOf(referenced),
            maxAgeMillis = 5_000,
            nowMillis = 10_000,
        )

        assertEquals(1, temporaryResult.deletedFileCount)
        assertEquals(1, audioResult.deletedFileCount)
        assertFalse(staleTemporary.exists())
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertTrue(source.exists())
        assertTrue(metadata.exists())
        assertTrue(temporaryResult.preservesProjectData)
        assertEquals(123, audioResult.availableBytesAfter)
    }

    @Test
    public fun noneCleanupChoiceDoesNotDeleteAnything() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val file = storage.temporaryFile("run", "keep.tmp").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("keep")
        }

        val result = GenerationStorageCleanup(storage).cleanup(
            choice = GenerationCleanupChoice.NONE,
            maxAgeMillis = 0,
        )

        assertEquals(0, result.deletedFileCount)
        assertTrue(file.exists())
    }

    private fun policy(storage: AppPrivateStorage, available: Long) = GenerationStoragePolicy(
        storage = storage,
        safetyMarginPercent = 10,
        minimumSafetyMarginBytes = 0,
        availableBytes = { available },
    )
}
