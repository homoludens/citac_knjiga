package com.homoludens.citacknjiga.proof

import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.document.epub.EpubCanonicalTextService
import com.homoludens.citacknjiga.document.epub.EpubDocumentParser
import com.homoludens.citacknjiga.document.epub.EpubImportPreviewService
import com.homoludens.citacknjiga.document.epub.EpubPreviewResult
import com.homoludens.citacknjiga.document.epub.EpubSourceReader
import com.homoludens.citacknjiga.document.epub.EpubAcceptanceResult
import com.homoludens.citacknjiga.document.epub.SafEpubSourceRepository
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Optional native-device gate for the known EPUB one-chapter proof. */
public class EpubChapterProofAndroidTest {
    @Test
    public fun knownEpubChapterGeneratesAndPlaysOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val modelStore = ModelPackageStore(context.filesDir)
        assumeNotNull("Stage a verified model package before the device proof", modelStore.activePackage())
        val storage = AppPrivateStorage(context.filesDir)
        val database = AudiobookDatabase.create(context, "task-7-9-proof.db")
        val dao = database.audiobookDao()
        try {
            val repository = SafEpubSourceRepository(
                sourceReader = EpubSourceReader { testContext.assets.open("serbian-epub3.epub") },
                storage = storage,
                artifactStore = AtomicArtifactStore(storage),
                projectIndex = com.homoludens.citacknjiga.document.epub.RoomEpubProjectIndex(dao),
                projectIdFactory = { "task-7-9-proof" },
            )
            val previewService = EpubImportPreviewService(
                sourceRepository = repository,
                parser = EpubDocumentParser(storage),
                canonicalText = EpubCanonicalTextService(storage, AtomicArtifactStore(storage)),
            )
            val preview = when (val previewResult = previewService.previewSource("asset://serbian-epub3.epub")) {
                is EpubPreviewResult.Ready -> previewResult.preview
                is EpubPreviewResult.Failed -> error("EPUB preview failed: ${previewResult.message}")
                is EpubPreviewResult.Duplicate -> error("EPUB unexpectedly duplicated")
            }
            val accepted = previewService.accept(preview) as EpubAcceptanceResult.Published
            val engine = AndroidTypedTextProofEngine(
                modelStore = modelStore,
                preprocessorFactory = { SerbianPreprocessor.fromAssets(context.assets, context.filesDir) },
                artifactDirectory = storage.typedProofDirectory,
            )
            val result = EpubChapterProofService(dao, storage, AtomicArtifactStore(storage), engine)
                .generate(accepted, chapterOrdinal = 0)

            assertEquals(24_000, result.audio.sampleRateHz)
            assertTrue(result.audio.file.isFile)
            assertEquals(com.homoludens.citacknjiga.core.database.AudioSegmentStatus.READY, result.segment.status)
            val player = LocalWavPlayer()
            val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                player.play(result.audio.file, playbackScope)
                delay(500)
                assertNull(player.lastError)
            } finally {
                player.close()
                playbackScope.cancel()
            }
        } finally {
            database.close()
            context.deleteDatabase("task-7-9-proof.db")
            storage.sourceDocument("task-7-9-proof").delete()
            storage.canonicalChapterText("task-7-9-proof", "task-7-9-proof-chapter-0").delete()
            storage.importWarnings("task-7-9-proof").delete()
            storage.readyChapterWav("task-7-9-proof", "task-7-9-proof-chapter-0").delete()
        }
    }
}
