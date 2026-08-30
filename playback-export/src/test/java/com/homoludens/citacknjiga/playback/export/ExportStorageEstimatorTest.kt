package com.homoludens.citacknjiga.playback.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class ExportStorageEstimatorTest {
    @Test
    public fun estimateUsesKnownSizesCodecOverheadMetadataAndMargin() {
        val estimate = ExportStorageEstimator.estimate(
            chapters = listOf(
                ExportStorageChapterInput(
                    sourceFileSizes = listOf(1_004L),
                    segmentDurationsMs = listOf(20L),
                    format = ExportAudioFormat.WAV,
                ),
            ),
            coverBytes = 100L,
            attributionCount = 2,
        )

        assertEquals(1_048L, estimate.privateTemporaryBytes)
        assertEquals(0L, estimate.privateDecodeScratchBytes)
        assertEquals(8_192L, estimate.metadataBytes)
        assertEquals(100L, estimate.coverBytes)
        assertEquals(6_144L, estimate.manifestBytes)
        assertEquals(15_484L, estimate.targetBytes)
        assertEquals(6_144L, estimate.providerTemporaryBytes)
        assertEquals(65_536L, estimate.safetyMarginBytes)
        assertEquals(87_164L, estimate.providerRequiredBytes)
    }

    @Test
    public fun preflightFailsClosedForUnknownProviderAndReportsInsufficientCapacity() {
        val estimate = ExportStorageEstimator.estimate(
            chapters = listOf(ExportStorageChapterInput(listOf(100L), listOf(1_000L), ExportAudioFormat.M4A)),
        )
        val unknown = TestTree(SafProviderCapabilities(supportsDocumentRename = true))
        val unknownFailure = runCatching {
            ExportStoragePreflight { Long.MAX_VALUE }.requireCapacity(unknown, estimate)
        }.exceptionOrNull()
        assertTrue(unknownFailure is ExportProviderCapacityUnknownException)

        val insufficient = TestTree(SafProviderCapabilities(supportsDocumentRename = true, availableBytes = estimate.providerRequiredBytes - 1L))
        val insufficientFailure = runCatching {
            ExportStoragePreflight { Long.MAX_VALUE }.requireCapacity(insufficient, estimate)
        }.exceptionOrNull()
        assertTrue(insufficientFailure is InsufficientExportStorageException)
        assertEquals(ExportStorageScope.PROVIDER, (insufficientFailure as InsufficientExportStorageException).scope)
    }

    private class TestTree(
        override val capabilities: SafProviderCapabilities,
    ) : SafDocumentTree {
        override fun listChildren(): List<SafDocument> = emptyList()
        override fun createFile(name: String, mimeType: String) = null
        override fun openForWrite(uri: android.net.Uri) = null
        override fun delete(uri: android.net.Uri) = true
    }
}
