package com.homoludens.citacknjiga.document.pdf

import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import java.io.File

/** Startup hook for aged, unreferenced candidate files; Room references are protected. */
public class PdfOrphanReconciler(
    private val storage: AppPrivateStorage,
    private val artifactStore: AtomicArtifactStore = AtomicArtifactStore(storage),
) {
    public fun reconcile(referencedFiles: Collection<File>, maxAgeMillis: Long): Int =
        storage.temporaryDirectory.listFiles { file -> file.isDirectory && file.name.startsWith("pdf-") }
            ?.sumOf { directory -> artifactStore.cleanupOrphanFiles(directory, referencedFiles, maxAgeMillis) }
            ?: 0
}
