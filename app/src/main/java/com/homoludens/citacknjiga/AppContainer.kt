package com.homoludens.citacknjiga

import android.content.Context
import com.homoludens.citacknjiga.core.diagnostics.LocalDiagnostics
import com.homoludens.citacknjiga.core.database.createAudiobookDao
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.document.epub.ContentResolverEpubSourceReader
import com.homoludens.citacknjiga.document.epub.EpubCanonicalTextService
import com.homoludens.citacknjiga.document.epub.EpubDocumentParser
import com.homoludens.citacknjiga.document.epub.EpubImportPreviewService
import com.homoludens.citacknjiga.document.epub.RoomEpubProjectIndex
import com.homoludens.citacknjiga.document.epub.SafEpubSourceRepository
import com.homoludens.citacknjiga.proof.AndroidTypedTextProofEngine
import com.homoludens.citacknjiga.proof.EpubChapterProofService
import com.homoludens.citacknjiga.proof.TypedTextProofEngine
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor

public enum class AppDistribution(public val id: String) {
    STANDARD("standard"),
    FDROID("fdroid"),
    ;

    public companion object {
        public fun fromId(id: String): AppDistribution =
            entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

public data class AppVariant(
    val distribution: AppDistribution,
    val verboseDiagnostics: Boolean,
) {
    public companion object {
        public fun fromBuildConfig(): AppVariant = AppVariant(
            distribution = AppDistribution.fromId(BuildConfig.DISTRIBUTION),
            verboseDiagnostics = BuildConfig.VERBOSE_DIAGNOSTICS,
        )
    }
}

/** Manual composition root. Feature modules depend on core, never on this container. */
public class AppContainer(
    public val diagnostics: LocalDiagnostics,
    public val variant: AppVariant,
    public val typedTextProofEngine: TypedTextProofEngine? = null,
    public val epubImportPreviewService: EpubImportPreviewService? = null,
    public val epubChapterProofService: EpubChapterProofService? = null,
) {
    public companion object {
        public fun production(context: Context): AppContainer {
            val filesDir = context.filesDir
            val assets = context.assets
            val contentResolver = context.contentResolver
            val privateStorage = AppPrivateStorage(filesDir)
            val modelStore = ModelPackageStore(privateStorage.rootDirectory)
            val dao = createAudiobookDao(context)
            val sourceRepository = SafEpubSourceRepository(
                sourceReader = ContentResolverEpubSourceReader(contentResolver),
                storage = privateStorage,
                artifactStore = AtomicArtifactStore(privateStorage),
                projectIndex = RoomEpubProjectIndex(dao),
            )
            val proofEngine = AndroidTypedTextProofEngine(
                modelStore = modelStore,
                preprocessorFactory = { SerbianPreprocessor.fromAssets(assets, filesDir) },
                artifactDirectory = privateStorage.typedProofDirectory,
            )
            return AppContainer(
                diagnostics = LocalDiagnostics(),
                variant = AppVariant.fromBuildConfig(),
                typedTextProofEngine = proofEngine,
                epubImportPreviewService = EpubImportPreviewService(
                    sourceRepository = sourceRepository,
                    parser = EpubDocumentParser(privateStorage),
                    canonicalText = EpubCanonicalTextService(privateStorage, AtomicArtifactStore(privateStorage)),
                ),
                epubChapterProofService = EpubChapterProofService(
                    dao = dao,
                    storage = privateStorage,
                    artifactStore = AtomicArtifactStore(privateStorage),
                    proofEngine = proofEngine,
                ),
            )
        }
    }
}
