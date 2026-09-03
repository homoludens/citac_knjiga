package com.homoludens.citacknjiga.core.generation

import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class GenerationProgressStoreTest {
    @Test
    public fun reportsCurrentWordsAndActualStagingWavBytes() {
        val storage = AppPrivateStorage(createTempDirectory().toFile())
        val store = GenerationProgressStore(storage)
        store.wavFile("run").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1_024))
        }

        store.update("run", "segment", completedWords = 12, totalWords = 30)

        assertEquals(ActiveGenerationProgress("run", "segment", 12, 30, 1_024), store.snapshot("run"))
        store.clear("run")
        assertNull(store.snapshot("run"))
    }
}
