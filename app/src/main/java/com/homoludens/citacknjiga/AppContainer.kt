package com.homoludens.citacknjiga

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.database.AudiobookDatabase
import com.homoludens.citacknjiga.core.diagnostics.LocalDiagnostics
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.storage.AtomicArtifactStore
import com.homoludens.citacknjiga.core.generation.BoundedGenerationRunner
import com.homoludens.citacknjiga.core.generation.BoundedGenerationResult
import com.homoludens.citacknjiga.core.generation.SegmentGenerator
import com.homoludens.citacknjiga.core.generation.GenerationNotificationController
import com.homoludens.citacknjiga.core.generation.GenerationRunExecutor
import com.homoludens.citacknjiga.core.generation.DurableGenerationCoordinator
import com.homoludens.citacknjiga.core.generation.GenerationWorkContract
import com.homoludens.citacknjiga.core.generation.GenerationStateService
import com.homoludens.citacknjiga.core.generation.GenerationWorkerFactory
import com.homoludens.citacknjiga.core.generation.SelectingGenerationRunExecutor
import com.homoludens.citacknjiga.core.generation.RoomGenerationNotificationDataSource
import com.homoludens.citacknjiga.core.generation.RoomGenerationQueue
import com.homoludens.citacknjiga.core.generation.GenerationWorkScheduler
import com.homoludens.citacknjiga.core.generation.GenerationInvalidationCoordinator
import com.homoludens.citacknjiga.generation.VitsGenerationCoordinator
import com.homoludens.citacknjiga.generation.KokoroGenerationCoordinator
import com.homoludens.citacknjiga.document.epub.ContentResolverEpubSourceReader
import com.homoludens.citacknjiga.document.epub.EpubCanonicalTextService
import com.homoludens.citacknjiga.document.epub.EpubDocumentParser
import com.homoludens.citacknjiga.document.epub.EpubImportPreviewService
import com.homoludens.citacknjiga.document.epub.RoomEpubProjectIndex
import com.homoludens.citacknjiga.document.epub.SafEpubSourceRepository
import com.homoludens.citacknjiga.document.pdf.ContentResolverPdfSourceReader
import com.homoludens.citacknjiga.document.pdf.PdfAcceptanceService
import com.homoludens.citacknjiga.document.pdf.PdfBoxResourceLoaderInitializer
import com.homoludens.citacknjiga.document.pdf.PdfBoxPdfPageImporter
import com.homoludens.citacknjiga.document.pdf.PdfCanonicalTextService
import com.homoludens.citacknjiga.document.pdf.PdfFeatureAvailability
import com.homoludens.citacknjiga.document.pdf.PdfImportPreviewService
import com.homoludens.citacknjiga.document.pdf.PdfOrphanReconciler
import com.homoludens.citacknjiga.document.pdf.RoomPdfProjectIndex
import com.homoludens.citacknjiga.document.pdf.SafPdfSourceRepository
import com.homoludens.citacknjiga.proof.AndroidTypedTextProofEngine
import com.homoludens.citacknjiga.proof.AndroidVitsTypedTextProofEngine
import com.homoludens.citacknjiga.proof.EngineSelectingTypedTextProofEngine
import com.homoludens.citacknjiga.proof.EpubChapterProofService
import com.homoludens.citacknjiga.proof.TypedTextProofEngine
import com.homoludens.citacknjiga.playback.export.AudiobookPlayerController
import com.homoludens.citacknjiga.playback.export.AudiobookPlaybackService
import com.homoludens.citacknjiga.playback.export.ReadyAudioRepository
import com.homoludens.citacknjiga.playback.export.RoomReadyAudioSource
import com.homoludens.citacknjiga.playback.export.MediaExtractorPlaybackAudioFormatValidator
import com.homoludens.citacknjiga.playback.export.RoomPlaybackValidationContextSource
import com.homoludens.citacknjiga.playback.export.RoomAudiobookExportService
import com.homoludens.citacknjiga.playback.export.SafAudiobookExporter
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.TtsEnginePreference
import com.homoludens.citacknjiga.tts.onnx.TtsEngineSelector
import com.homoludens.citacknjiga.tts.onnx.VitsGenerationExecutor
import com.homoludens.citacknjiga.tts.onnx.VitsSegmentGeneratorFactory
import com.homoludens.citacknjiga.tts.onnx.KokoroGenerationExecutor
import com.homoludens.citacknjiga.tts.onnx.KokoroSegmentGeneratorFactory
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor
import com.homoludens.citacknjiga.core.lifecycle.ProjectDeletionCoordinator
import com.homoludens.citacknjiga.core.lifecycle.ProjectPlaybackStopper
import com.homoludens.citacknjiga.core.lifecycle.ProjectWorkCanceller
import com.homoludens.citacknjiga.modeldownload.AppWorkerFactory
import com.homoludens.citacknjiga.modeldownload.HttpsModelDownloadTransport
import com.homoludens.citacknjiga.modeldownload.ModelPackageDownloadInstaller
import com.homoludens.citacknjiga.modeldownload.ModelDownloadWorkScheduler
import com.homoludens.citacknjiga.modeldownload.ModelDownloadWorkerFactory
import androidx.work.WorkerFactory

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
    public val audiobookDao: AudiobookDao? = null,
    public val audiobookDatabase: AudiobookDatabase? = null,
    public val typedTextProofEngine: TypedTextProofEngine? = null,
    public val epubImportPreviewService: EpubImportPreviewService? = null,
    public val pdfImportPreviewService: PdfImportPreviewService? = null,
    public val pdfAcceptanceService: PdfAcceptanceService? = null,
    public val epubChapterProofService: EpubChapterProofService? = null,
    public val playbackController: AudiobookPlayerController? = null,
    public val audiobookExportService: RoomAudiobookExportService? = null,
    public val privateStorage: AppPrivateStorage? = null,
    public val modelPackageStore: ModelPackageStore? = null,
    public val ttsEnginePreference: TtsEnginePreference? = null,
    public val vitsGenerationCoordinator: VitsGenerationCoordinator? = null,
    public val generationCoordinator: DurableGenerationCoordinator? = null,
    public val generationInvalidationCoordinator: GenerationInvalidationCoordinator? = null,
    public val generationWorkerFactory: GenerationWorkerFactory? = null,
    public val workerFactory: WorkerFactory? = null,
    public val modelDownloadScheduler: ModelDownloadWorkScheduler? = null,
    public val projectDeletionCoordinator: ProjectDeletionCoordinator? = null,
) {
    public companion object {
        public fun production(context: Context): AppContainer {
            PdfBoxResourceLoaderInitializer.initialize(context)
            val filesDir = context.filesDir
            val assets = context.assets
            val contentResolver = context.contentResolver
            val privateStorage = AppPrivateStorage(filesDir)
            val modelStore = ModelPackageStore(privateStorage.rootDirectory)
            val engineSelector = TtsEngineSelector(
                vitsStore = modelStore.vitsModelPackageStore,
                apiLevel = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            )
            val enginePreference = TtsEnginePreference(
                selector = engineSelector,
                preferences = context.getSharedPreferences("tts", Context.MODE_PRIVATE),
            )
            val database = AudiobookDatabase.create(context)
            val dao = database.audiobookDao()
            val artifactStore = AtomicArtifactStore(privateStorage)
            val deletionCoordinator = ProjectDeletionCoordinator(
                database = database,
                storage = privateStorage,
                workCanceller = ProjectWorkCanceller { runId ->
                    androidx.work.WorkManager.getInstance(context)
                        .cancelUniqueWork(GenerationWorkContract.uniqueWorkName(runId))
                },
                playbackStopper = ProjectPlaybackStopper { projectId ->
                    context.startService(AudiobookPlaybackService.stopIntent(context, projectId))
                },
            )
            PdfOrphanReconciler(privateStorage, artifactStore).reconcile(
                referencedFiles = emptyList(),
                maxAgeMillis = 24L * 60L * 60L * 1_000L,
            )
            val readyAudio = ReadyAudioRepository(
                source = RoomReadyAudioSource(dao),
                storage = privateStorage,
                formatValidator = MediaExtractorPlaybackAudioFormatValidator(),
                validationContext = RoomPlaybackValidationContextSource(dao),
                validationDispatcher = Dispatchers.IO,
            )
            val sourceRepository = SafEpubSourceRepository(
                sourceReader = ContentResolverEpubSourceReader(contentResolver),
                storage = privateStorage,
                artifactStore = AtomicArtifactStore(privateStorage),
                projectIndex = RoomEpubProjectIndex(dao),
            )
            val pdfServices = if (PdfFeatureAvailability.QUALIFIED) {
                val pdfIndex = RoomPdfProjectIndex(dao)
                val pdfRepository = SafPdfSourceRepository(
                    sourceReader = ContentResolverPdfSourceReader(contentResolver),
                    storage = privateStorage,
                    artifactStore = artifactStore,
                    projectIndex = pdfIndex,
                )
                val canonical = PdfCanonicalTextService(privateStorage, artifactStore)
                val preview = PdfImportPreviewService(
                    repository = pdfRepository,
                    importer = PdfBoxPdfPageImporter(),
                )
                preview to PdfAcceptanceService(
                    repository = pdfRepository,
                    index = pdfIndex,
                    canonical = canonical,
                    storage = privateStorage,
                    artifactStore = artifactStore,
                )
            } else {
                null
            }
            val kokoroProofEngine = AndroidTypedTextProofEngine(
                modelStore = modelStore,
                preprocessorFactory = { SerbianPreprocessor.fromAssets(assets, filesDir) },
                artifactDirectory = privateStorage.typedProofDirectory,
            )
            val proofEngine = EngineSelectingTypedTextProofEngine(
                preference = enginePreference,
                kokoro = kokoroProofEngine,
                vits = {
                    AndroidVitsTypedTextProofEngine(
                        modelStore = modelStore.vitsModelPackageStore,
                        artifactDirectory = privateStorage.typedProofDirectory,
                    )
                },
            )
            val generationState = GenerationStateService(database)
            val vitsFactory = VitsSegmentGeneratorFactory(modelStore.vitsModelPackageStore)
            val runExecutor: suspend (String, SegmentGenerator) -> BoundedGenerationResult = { runId, generator ->
                BoundedGenerationRunner(
                    state = generationState,
                    storage = privateStorage,
                    artifactStore = AtomicArtifactStore(privateStorage),
                    generator = generator,
                ).run(runId)
            }
            val vitsExecutor: GenerationRunExecutor = VitsGenerationExecutor(
                openGenerator = { modelPackageId -> vitsFactory.open(modelPackageId) },
                modelPackageIdForRun = { runId ->
                    database.audiobookDao().findGenerationRunById(runId)?.also { run ->
                        check(run.engine == "vits") { "Only VITS runs are supported by the VITS worker" }
                    }?.modelPackageId
                },
                executeRun = { runId, generator -> runExecutor(runId, generator) },
            )
            val kokoroFactory = KokoroSegmentGeneratorFactory(
                store = modelStore,
                preprocessorFactory = { SerbianPreprocessor.fromAssets(assets, filesDir) },
            )
            val kokoroExecutor: GenerationRunExecutor = KokoroGenerationExecutor(
                openGenerator = { modelPackageId -> kokoroFactory.open(modelPackageId) },
                modelPackageIdForRun = { runId ->
                    database.audiobookDao().findGenerationRunById(runId)?.also { run ->
                        check(run.engine == "kokoro") { "Only Kokoro runs are supported by the Kokoro worker" }
                    }?.modelPackageId
                },
                executeRun = { runId, generator -> runExecutor(runId, generator) },
            )
            val workerFactory = GenerationWorkerFactory(
                executor = SelectingGenerationRunExecutor(
                    database = database,
                    executors = mapOf(
                        com.homoludens.citacknjiga.core.generation.GenerationEngine.KOKORO to kokoroExecutor,
                        com.homoludens.citacknjiga.core.generation.GenerationEngine.VITS to vitsExecutor,
                    ),
                ),
                notifications = GenerationNotificationController(
                    context = context,
                    dataSource = RoomGenerationNotificationDataSource(database),
                ),
            )
            val modelDownloadWorkerFactory = ModelDownloadWorkerFactory(
                storage = privateStorage,
                transport = HttpsModelDownloadTransport(),
                packageInstaller = ModelPackageDownloadInstaller(modelStore),
            )
            val generationQueue = RoomGenerationQueue(database, privateStorage)
            val vitsCoordinator = VitsGenerationCoordinator(
                vitsStore = modelStore.vitsModelPackageStore,
            )
            val kokoroCoordinator = KokoroGenerationCoordinator(
                modelStore = modelStore,
                preprocessorFactory = { SerbianPreprocessor.fromAssets(assets, filesDir) },
            )
            val durableGenerationCoordinator = DurableGenerationCoordinator(
                database = database,
                planners = listOf(kokoroCoordinator, vitsCoordinator),
                enqueue = { runId ->
                    GenerationWorkScheduler(
                        workManager = androidx.work.WorkManager.getInstance(context),
                        queue = generationQueue,
                    ).enqueue(runId)
                },
            )
            val generationInvalidationCoordinator = GenerationInvalidationCoordinator(
                database = database,
                storage = privateStorage,
                generationCoordinator = durableGenerationCoordinator,
            )
            return AppContainer(
                diagnostics = LocalDiagnostics(),
                variant = AppVariant.fromBuildConfig(),
                audiobookDao = dao,
                audiobookDatabase = database,
                typedTextProofEngine = proofEngine,
                epubImportPreviewService = EpubImportPreviewService(
                    sourceRepository = sourceRepository,
                    parser = EpubDocumentParser(privateStorage),
                    canonicalText = EpubCanonicalTextService(privateStorage, AtomicArtifactStore(privateStorage)),
                ),
                pdfImportPreviewService = pdfServices?.first,
                pdfAcceptanceService = pdfServices?.second,
                epubChapterProofService = EpubChapterProofService(
                    dao = dao,
                    storage = privateStorage,
                    artifactStore = AtomicArtifactStore(privateStorage),
                    proofEngine = proofEngine,
                ),
                playbackController = AudiobookPlayerController(context, readyAudio),
                audiobookExportService = RoomAudiobookExportService(
                    dao = dao,
                    exporter = SafAudiobookExporter(privateStorage),
                    contentResolver = contentResolver,
                ),
                privateStorage = privateStorage,
                modelPackageStore = modelStore,
                ttsEnginePreference = enginePreference,
                vitsGenerationCoordinator = vitsCoordinator,
                generationCoordinator = durableGenerationCoordinator,
                generationInvalidationCoordinator = generationInvalidationCoordinator,
                generationWorkerFactory = workerFactory,
                workerFactory = AppWorkerFactory(workerFactory, modelDownloadWorkerFactory),
                modelDownloadScheduler = ModelDownloadWorkScheduler(
                    workManagerProvider = { androidx.work.WorkManager.getInstance(context) },
                ),
                projectDeletionCoordinator = deletionCoordinator,
            )
        }
    }
}
