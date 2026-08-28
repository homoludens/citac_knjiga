package com.homoludens.citacknjiga.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class AudiobookSchemaTest {
    private val converters = RoomEnumConverters()

    @Test
    public fun enumValuesPersistByStableName() {
        assertEquals(
            BookProjectStatus.entries.map { it.name },
            BookProjectStatus.entries.map { converters.bookProjectStatusToStorage(it) },
        )
        assertEquals(
            AudioSegmentStatus.entries.toSet(),
            AudioSegmentStatus.entries.map { converters.storageToAudioSegmentStatus(it.name) }.toSet(),
        )
        assertEquals(
            ModelPackageStatus.entries.toSet(),
            ModelPackageStatus.entries.map { converters.storageToModelPackageStatus(it.name) }.toSet(),
        )
    }

    @Test
    public fun relationsExposeTheOwnershipTree() {
        val chapter = ChapterEntity(
            id = "chapter",
            bookProjectId = "book",
            ordinal = 0,
            title = "Chapter",
            createdAt = 1,
            updatedAt = 1,
        )
        val contents = ChapterWithRelations(chapter, emptyList(), emptyList())
        assertEquals("book", contents.chapter.bookProjectId)
        assertTrue(contents.narrationBlocks.isEmpty())
        assertTrue(contents.audioSegments.isEmpty())
    }

    @Test
    public fun persistedDefaultsKeepNewWorkRecoverable() {
        val segment = AudioSegmentEntity(
            id = "segment",
            chapterId = "chapter",
            narrationBlockId = "block",
            sequence = 0,
            chunkOrdinal = 0,
            createdAt = 1,
            updatedAt = 1,
        )
        assertEquals(AudioSegmentStatus.PENDING, segment.status)
        assertEquals(24_000, segment.sampleRate)
        assertEquals(1, segment.channels)
        assertEquals(0, segment.attemptCount)
    }
}
