package com.homoludens.citacknjiga.document.epub

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class EpubSourceRecoveryAndroidTest {
    @Test
    public fun unavailableSourceProviderDoesNotPublishPrivateState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "source-recovery-${UUID.randomUUID()}")
        val storage = AppPrivateStorage(root)
        val index = RecordingProjectIndex()
        val repository = SafEpubSourceRepository(
            sourceReader = EpubSourceReader { null },
            storage = storage,
            artifactStore = AtomicArtifactStore(storage),
            projectIndex = index,
            projectIdFactory = { "unavailable-source" },
        )

        val result = repository.importSelected(Uri.parse("content://books/missing"))

        assertEquals(EpubImportError.SOURCE_UNAVAILABLE, (result as EpubImportResult.Failed).error)
        assertTrue(index.sources.isEmpty())
        assertFalse(storage.sourceDocumentsDirectory.walkTopDown().any { it.isFile })
        assertFalse(storage.temporaryDirectory.walkTopDown().any { it.isFile })
        root.deleteRecursively()
    }

    private class RecordingProjectIndex : EpubProjectIndex {
        val sources = mutableListOf<ImportedEpubSource>()

        override fun findByFingerprint(fingerprint: String): ExistingEpubProject? = null

        override fun recordImportedSource(source: ImportedEpubSource) {
            sources += source
        }
    }
}
