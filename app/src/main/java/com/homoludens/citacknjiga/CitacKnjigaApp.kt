package com.homoludens.citacknjiga

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.core.document.ImportDiagnostic
import com.homoludens.citacknjiga.core.document.ImportDiagnosticCode
import com.homoludens.citacknjiga.core.diagnostics.LocalDiagnostics
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.core.lifecycle.ProjectDeletionCoordinator
import com.homoludens.citacknjiga.core.generation.GenerationNotificationActionReceiver
import com.homoludens.citacknjiga.core.generation.GenerationNotificationController
import com.homoludens.citacknjiga.document.epub.EpubAcceptanceResult
import com.homoludens.citacknjiga.document.epub.EpubImportPreview
import com.homoludens.citacknjiga.document.epub.EpubPreviewResult
import com.homoludens.citacknjiga.document.epub.EpubImportPreviewService
import com.homoludens.citacknjiga.document.pdf.PdfAcceptanceResult
import com.homoludens.citacknjiga.document.pdf.PdfAcceptanceService
import com.homoludens.citacknjiga.document.pdf.PdfDocumentProjector
import com.homoludens.citacknjiga.document.pdf.PdfFeatureAvailability
import com.homoludens.citacknjiga.document.pdf.PdfImportPreview
import com.homoludens.citacknjiga.document.pdf.PdfImportPreviewService
import com.homoludens.citacknjiga.document.pdf.PdfPreviewResult
import com.homoludens.citacknjiga.document.pdf.PageRange
import com.homoludens.citacknjiga.document.pdf.PdfDiagnosticFormatter
import com.homoludens.citacknjiga.proof.LocalWavPlayer
import com.homoludens.citacknjiga.proof.EpubChapterGenerationResult
import com.homoludens.citacknjiga.proof.EpubChapterProofService
import com.homoludens.citacknjiga.proof.TypedTextProofController
import com.homoludens.citacknjiga.proof.TypedTextProofDiagnostics
import com.homoludens.citacknjiga.proof.TypedTextProofEngine
import com.homoludens.citacknjiga.proof.TypedTextProofState
import com.homoludens.citacknjiga.proof.TypedTextProofStatus
import com.homoludens.citacknjiga.playback.export.AudiobookPlayerController
import com.homoludens.citacknjiga.playback.export.ExportPlan
import com.homoludens.citacknjiga.playback.export.RoomAudiobookExportService
import com.homoludens.citacknjiga.playback.export.SafDocumentTreePermissions
import com.homoludens.citacknjiga.playback.export.PlayerControlState
import com.homoludens.citacknjiga.player.AudiobookPlayerControls
import com.homoludens.citacknjiga.library.LibraryController
import com.homoludens.citacknjiga.library.LibraryScreen
import com.homoludens.citacknjiga.library.LibraryViewState
import com.homoludens.citacknjiga.library.BookDetailScreen
import com.homoludens.citacknjiga.library.DocumentTextPreviewScreen
import com.homoludens.citacknjiga.library.GenerationAction
import com.homoludens.citacknjiga.library.LibraryRegenerationController
import com.homoludens.citacknjiga.library.RegenerationFeedback
import com.homoludens.citacknjiga.library.RegenerationResult
import com.homoludens.citacknjiga.library.RegenerationResultStatus
import com.homoludens.citacknjiga.core.generation.GenerationInvalidationCoordinator
import com.homoludens.citacknjiga.core.generation.GenerationScope
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import android.content.Intent
import kotlinx.coroutines.CancellationException
import com.homoludens.citacknjiga.playback.export.DestinationUnavailableException
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.TtsEngine
import com.homoludens.citacknjiga.tts.onnx.TtsEnginePreference
import com.homoludens.citacknjiga.diagnostics.DiagnosticsAboutRoute
import com.homoludens.citacknjiga.diagnostics.EpubImportDiagnosticFormatter
import com.homoludens.citacknjiga.modeldownload.ModelDownloadWorkScheduler

@Composable
public fun CitacKnjigaApp(
    variant: AppVariant,
    audiobookDao: AudiobookDao? = null,
    proofEngine: TypedTextProofEngine? = null,
    epubImportPreviewService: EpubImportPreviewService? = null,
    pdfImportPreviewService: PdfImportPreviewService? = null,
    pdfAcceptanceService: PdfAcceptanceService? = null,
    epubChapterProofService: EpubChapterProofService? = null,
    playbackController: AudiobookPlayerController? = null,
    audiobookExportService: RoomAudiobookExportService? = null,
    diagnostics: LocalDiagnostics = LocalDiagnostics(),
    privateStorage: AppPrivateStorage? = null,
    modelPackageStore: ModelPackageStore? = null,
    ttsEnginePreference: TtsEnginePreference? = null,
    modelDownloadScheduler: ModelDownloadWorkScheduler? = null,
    projectDeletionCoordinator: ProjectDeletionCoordinator? = null,
    generationInvalidationCoordinator: GenerationInvalidationCoordinator? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val deletionScope = rememberCoroutineScope()
    val regenerationScope = rememberCoroutineScope()
    val regenerationController = remember(audiobookDao, generationInvalidationCoordinator, ttsEnginePreference) {
        if (audiobookDao != null && generationInvalidationCoordinator != null) {
            LibraryRegenerationController(
                findProject = audiobookDao::findProjectById,
                findChapters = audiobookDao::findAllChapters,
                findNarrationBlocks = audiobookDao::findAllNarrationBlocks,
                findRun = audiobookDao::findGenerationRunById,
                findSegments = audiobookDao::findAllAudioSegments,
                invalidateAndQueue = generationInvalidationCoordinator::invalidateAndQueue,
                selectedEngine = { ttsEnginePreference?.selected ?: TtsEngine.KOKORO },
            )
        } else {
            null
        }
    }
    var regenerationFeedback by remember { mutableStateOf<RegenerationFeedback?>(null) }

    fun applyRegenerationResult(result: RegenerationResult) {
        regenerationFeedback = RegenerationFeedback(
            projectId = result.projectId,
            scope = result.scope,
            status = result.status,
            runId = result.queued?.runId,
        )
    }

    val onRegenerate: (String, GenerationScope) -> Unit = { projectId, scope ->
        val controller = regenerationController
        if (controller != null) {
            regenerationFeedback = RegenerationFeedback(projectId, scope, RegenerationResultStatus.QUEUING)
            regenerationScope.launch(Dispatchers.IO) {
                val result = controller.regenerate(projectId, scope)
                withContext(Dispatchers.Main.immediate) { applyRegenerationResult(result) }
            }
        }
    }

    val onGenerationAction: (String, GenerationAction) -> Unit = { runId, action ->
        val controller = regenerationController
        if (action == GenerationAction.RETRY && controller != null) {
            regenerationScope.launch(Dispatchers.IO) {
                val result = controller.retry(runId)
                if (result == null) {
                    sendGenerationAction(context, runId, action)
                } else {
                    withContext(Dispatchers.Main.immediate) { applyRegenerationResult(result) }
                }
            }
        } else {
            sendGenerationAction(context, runId, action)
        }
    }
    val onDeleteBook: (String) -> Unit = { projectId ->
        projectDeletionCoordinator?.let { coordinator ->
            deletionScope.launch(Dispatchers.IO) {
                try {
                    coordinator.deleteProject(projectId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The deleting marker keeps the operation recoverable on the next launch.
                }
            }
        }
    }
    MaterialTheme {
        NavHost(
            navController = navController,
            startDestination = AppRoute.Start.path,
            modifier = modifier,
        ) {
            composable(AppRoute.Start.path) {
                StartScreen(
                    variant = variant,
                    audiobookDao = audiobookDao,
                    proofEngine = proofEngine,
                    epubImportPreviewService = epubImportPreviewService,
                    pdfImportPreviewService = pdfImportPreviewService,
                    pdfAcceptanceService = pdfAcceptanceService,
                    epubChapterProofService = epubChapterProofService,
                    onOpenBook = { id -> navController.navigate(AppRoute.Book.forId(id)) },
                    onOpenDiagnostics = { navController.navigate(AppRoute.Diagnostics.path) },
                    onGenerationAction = onGenerationAction,
                    onDeleteBook = onDeleteBook,
                    onRegenerate = onRegenerate,
                    regenerationFeedback = regenerationFeedback,
                    ttsEnginePreference = ttsEnginePreference,
                    modelDownloadScheduler = modelDownloadScheduler,
                )
            }
            composable(AppRoute.Diagnostics.path) {
                DiagnosticsAboutRoute(
                    diagnostics = diagnostics,
                    modelPackageStore = modelPackageStore,
                    vitsModelPackageStore = modelPackageStore?.vitsModelPackageStore,
                    modelDownloadScheduler = modelDownloadScheduler,
                    privateStorage = privateStorage,
                    variant = variant,
                    onBack = navController::popBackStack,
                )
            }
            composable(
                route = AppRoute.Book.path,
                arguments = listOf(navArgument(AppRoute.Book.argument) { type = NavType.StringType }),
            ) { entry ->
                BookRoute(
                    audiobookDao = audiobookDao,
                    playbackController = playbackController,
                    audiobookExportService = audiobookExportService,
                    bookId = entry.arguments?.getString(AppRoute.Book.argument),
                    onBack = navController::popBackStack,
                    onOpenTextPreview = { id -> navController.navigate(AppRoute.TextPreview.forId(id)) },
                    onGenerationAction = onGenerationAction,
                    onDeleteBook = { projectId ->
                        onDeleteBook(projectId)
                        navController.popBackStack()
                    },
                    onRegenerate = onRegenerate,
                    regenerationFeedback = regenerationFeedback,
                )
            }
            composable(
                route = AppRoute.TextPreview.path,
                arguments = listOf(navArgument(AppRoute.TextPreview.argument) { type = NavType.StringType }),
            ) { entry ->
                TextPreviewRoute(
                    audiobookDao = audiobookDao,
                    bookId = entry.arguments?.getString(AppRoute.TextPreview.argument),
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StartScreen(
    variant: AppVariant,
    audiobookDao: AudiobookDao?,
    proofEngine: TypedTextProofEngine?,
    epubImportPreviewService: EpubImportPreviewService?,
    pdfImportPreviewService: PdfImportPreviewService?,
    pdfAcceptanceService: PdfAcceptanceService?,
    epubChapterProofService: EpubChapterProofService?,
    onOpenBook: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onGenerationAction: (String, GenerationAction) -> Unit,
    onDeleteBook: (String) -> Unit,
    onRegenerate: (String, GenerationScope) -> Unit,
    regenerationFeedback: RegenerationFeedback?,
    ttsEnginePreference: TtsEnginePreference?,
    modelDownloadScheduler: ModelDownloadWorkScheduler?,
) {
    val libraryController = remember(audiobookDao) { audiobookDao?.let(::LibraryController) }
    val libraryFlow: Flow<LibraryViewState> = libraryController?.state ?: flowOf(LibraryViewState())
    val libraryState by libraryFlow.collectAsState(initial = LibraryViewState())
    val controller = remember(proofEngine) {
        TypedTextProofController(proofEngine ?: MissingProofEngine())
    }
    var selectedEngine by remember(ttsEnginePreference) {
        mutableStateOf(ttsEnginePreference?.selected ?: TtsEngine.KOKORO)
    }
    val availableEngines = ttsEnginePreference?.available() ?: listOf(TtsEngine.KOKORO)
    androidx.compose.runtime.LaunchedEffect(ttsEnginePreference) {
        ttsEnginePreference?.refresh()
        selectedEngine = ttsEnginePreference?.selected ?: TtsEngine.KOKORO
    }
    val player = remember { LocalWavPlayer() }
    val playbackScope = rememberCoroutineScope()
    var importState by remember(epubImportPreviewService) {
        mutableStateOf<ImportPreviewUiState>(ImportPreviewUiState.Idle)
    }
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pdfImportState by remember(pdfImportPreviewService) {
        mutableStateOf<PdfImportUiState>(PdfImportUiState.Idle)
    }
    var pdfImportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var chapterGeneration by remember { mutableStateOf<ChapterGenerationUiState>(ChapterGenerationUiState.Idle) }
    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri: Uri? ->
        if (uri != null && epubImportPreviewService != null) {
            importState = ImportPreviewUiState.Loading
            importJob = playbackScope.launch(Dispatchers.IO) {
                val result = epubImportPreviewService.previewSelected(uri)
                withContext(Dispatchers.Main.immediate) {
                    importState = result.toUiState()
                    chapterGeneration = ChapterGenerationUiState.Idle
                }
            }
        }
    }
    val pdfImportLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri: Uri? ->
        if (uri != null && PdfFeatureAvailability.QUALIFIED && pdfImportPreviewService != null) {
            (pdfImportState as? PdfImportUiState.Ready)?.let { pdfImportPreviewService.discard(it.preview) }
            pdfImportState = PdfImportUiState.Loading
            pdfImportJob = playbackScope.launch(Dispatchers.IO) {
                val result = pdfImportPreviewService.previewSource(uri.toString(), 1, 1)
                withContext(Dispatchers.Main.immediate) {
                    pdfImportState = result.toUiState()
                }
            }
        }
    }
    val latestPdfState by rememberUpdatedState(pdfImportState)
    val latestPdfJob by rememberUpdatedState(pdfImportJob)
    DisposableEffect(controller, player) {
        onDispose {
            player.close()
            controller.close()
        }
    }
    DisposableEffect(libraryController) {
        onDispose { libraryController?.close() }
    }
    DisposableEffect(pdfImportPreviewService) {
        onDispose {
            latestPdfJob?.cancel()
            (latestPdfState as? PdfImportUiState.Ready)?.let { pdfImportPreviewService?.discard(it.preview) }
        }
    }
    val state by controller.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.start_title)) },
                actions = {
                    TextButton(onClick = onOpenDiagnostics) {
                        Text(stringResource(R.string.open_diagnostics))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            LibraryScreen(
                state = libraryState,
                onBookClick = onOpenBook,
                onGenerationAction = onGenerationAction,
                onDeleteBook = onDeleteBook,
                onRegenerate = onRegenerate,
                regenerationFeedback = regenerationFeedback,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
            EpubImportPreviewContent(
                state = importState,
                enabled = epubImportPreviewService != null,
                onSelect = { importLauncher.launch(arrayOf("application/epub+zip", "application/zip")) },
                onAccept = { preview ->
                    importState = ImportPreviewUiState.Loading
                    importJob = playbackScope.launch(Dispatchers.IO) {
                        val result = epubImportPreviewService?.accept(preview)
                        withContext(Dispatchers.Main.immediate) {
                            importState = result.toUiState()
                            chapterGeneration = ChapterGenerationUiState.Idle
                        }
                    }
                },
                onCancel = { preview ->
                    epubImportPreviewService?.discard(preview)
                    importState = ImportPreviewUiState.Idle
                    chapterGeneration = ChapterGenerationUiState.Idle
                },
                onCancelLoading = {
                    importJob?.cancel()
                    importState = ImportPreviewUiState.Idle
                    chapterGeneration = ChapterGenerationUiState.Idle
                },
                generation = chapterGeneration,
                onGenerate = { acceptedPreview, chapterOrdinal ->
                    if (epubChapterProofService != null) {
                        chapterGeneration = ChapterGenerationUiState.Generating(chapterOrdinal)
                        playbackScope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                epubChapterProofService.generate(acceptedPreview, chapterOrdinal)
                            }
                            withContext(Dispatchers.Main.immediate) {
                                chapterGeneration = result.fold(
                                    onSuccess = { ChapterGenerationUiState.Success(it) },
                                    onFailure = { ChapterGenerationUiState.Error(chapterOrdinal) },
                                )
                            }
                        }
                    }
                },
                onPlayGenerated = { result -> player.play(result.audio.file, playbackScope) },
                onStopGenerated = player::stop,
            )
            PdfImportPreviewContent(
                state = if (PdfFeatureAvailability.QUALIFIED) {
                    pdfImportState
                } else {
                    PdfImportUiState.Error(pdfUnavailableDiagnostic())
                },
                enabled = PdfFeatureAvailability.QUALIFIED &&
                    pdfImportPreviewService != null && pdfAcceptanceService != null,
                onSelect = { pdfImportLauncher.launch(arrayOf("application/pdf")) },
                onPreview = { startPage, endPage ->
                    (pdfImportState as? PdfImportUiState.Ready)?.let { ready ->
                        val service = pdfImportPreviewService ?: return@PdfImportPreviewContent
                        pdfImportState = PdfImportUiState.Loading
                        pdfImportJob = playbackScope.launch(Dispatchers.IO) {
                            val result = service.previewStaged(ready.preview.stagedSource, startPage, endPage)
                            withContext(Dispatchers.Main.immediate) {
                                pdfImportState = result.toUiState()
                            }
                        }
                    }
                },
                onAccept = { preview ->
                    val acceptance = pdfAcceptanceService ?: return@PdfImportPreviewContent
                    pdfImportState = PdfImportUiState.Accepting
                    pdfImportJob = playbackScope.launch(Dispatchers.IO) {
                        val result = try {
                            acceptance.accept(preview, PdfDocumentProjector.toIr(preview))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            PdfAcceptanceResult.Failed(
                                ImportDiagnostic(
                                    ImportDiagnosticCode.ACCEPTANCE_FAILED,
                                    message = "The PDF import could not be completed.",
                                    action = "Select the PDF again and retry.",
                                ),
                            )
                        }
                        withContext(Dispatchers.Main.immediate) {
                            pdfImportState = result.toUiState()
                        }
                    }
                },
                onCancel = { preview ->
                    pdfImportPreviewService?.discard(preview)
                    pdfImportState = PdfImportUiState.Idle
                },
                onCancelLoading = {
                    pdfImportJob?.cancel()
                    pdfImportState = PdfImportUiState.Idle
                },
            )
            TypedTextProofContent(
                paddingValues = PaddingValues(0.dp),
                variant = variant,
                state = state,
                onTextChanged = controller::setText,
                onGenerate = controller::generate,
                onCancel = controller::cancel,
                onPlay = { state.wav?.file?.let { player.play(it, playbackScope) } },
                onStop = player::stop,
                selectedEngine = selectedEngine,
                availableEngines = availableEngines,
                onEngineSelected = {
                    ttsEnginePreference?.select(it)
                    selectedEngine = ttsEnginePreference?.selected ?: TtsEngine.KOKORO
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BookRoute(
    audiobookDao: AudiobookDao?,
    playbackController: AudiobookPlayerController?,
    audiobookExportService: RoomAudiobookExportService?,
    bookId: String?,
    onBack: () -> Unit,
    onOpenTextPreview: (String) -> Unit,
    onGenerationAction: (String, GenerationAction) -> Unit,
    onDeleteBook: (String) -> Unit,
    onRegenerate: (String, GenerationScope) -> Unit,
    regenerationFeedback: RegenerationFeedback?,
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var exportPlan by remember { mutableStateOf<ExportPlan?>(null) }
    var activeExportPlan by remember { mutableStateOf<ExportPlan?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun showExportFailure(failure: Throwable) {
        exportFailed = true
        exportMessage = context.getString(
            if (failure is DestinationUnavailableException) {
                R.string.export_destination_unavailable
            } else {
                R.string.export_failed
            },
        )
    }

    fun startExport(plan: ExportPlan, overwriteConfirmed: Boolean = false) {
        activeExportPlan = plan
        exportPlan = null
        exportBusy = true
        exportFailed = false
        exportMessage = null
        exportJob = exportScope.launch(Dispatchers.IO) {
            try {
                val exported = audiobookExportService!!.export(plan, overwriteConfirmed)
                withContext(Dispatchers.Main.immediate) {
                    exportBusy = false
                    exportMessage = context.getString(
                        if (overwriteConfirmed) R.string.export_success_new_names_format else R.string.export_success_format,
                        exported.writtenNames.size,
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(Dispatchers.Main.immediate) {
                    exportBusy = false
                    exportFailed = false
                    exportMessage = context.getString(R.string.export_cancelled)
                }
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    exportBusy = false
                    showExportFailure(failure)
                }
            }
        }
    }

    fun startPlanning(uri: Uri) {
        exportBusy = true
        exportFailed = false
        exportMessage = null
        exportJob = exportScope.launch(Dispatchers.IO) {
            try {
                val plan = audiobookExportService!!.planForProject(uri, bookId!!)
                withContext(Dispatchers.Main.immediate) {
                    exportBusy = false
                    activeExportPlan = plan
                    if (plan.hasCollisions) exportPlan = plan else startExport(plan)
                }
            } catch (cancelled: CancellationException) {
                withContext(Dispatchers.Main.immediate) {
                    exportBusy = false
                    exportMessage = context.getString(R.string.export_cancelled)
                }
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    exportBusy = false
                    showExportFailure(failure)
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null && bookId != null && audiobookExportService != null) {
            SafDocumentTreePermissions.persistWritePermission(context.contentResolver, uri)
            startPlanning(uri)
        }
    }
    val libraryController = remember(audiobookDao) { audiobookDao?.let(::LibraryController) }
    val libraryFlow: Flow<LibraryViewState> = libraryController?.state ?: flowOf(LibraryViewState())
    val libraryState by libraryFlow.collectAsState(initial = LibraryViewState())
    val playerState by playbackController?.state?.collectAsState() ?: remember { mutableStateOf(PlayerControlState()) }
    val book = libraryState.books.firstOrNull { it.project.id == bookId }
    androidx.compose.runtime.LaunchedEffect(book?.project?.id, book?.chapters) {
        book?.let { selected ->
            playbackController?.bindBook(
                selected.project.id,
                selected.chapters.map { chapter -> chapter.chapter },
            )
        }
    }
    DisposableEffect(libraryController) {
        onDispose { libraryController?.close() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            BookDetailScreen(
                book = book,
                onOpenTextPreview = { book?.project?.id?.let(onOpenTextPreview) },
                onGenerationAction = onGenerationAction,
                onDeleteBook = onDeleteBook,
                onRegenerate = onRegenerate,
                regenerationFeedback = regenerationFeedback,
                modifier = Modifier.weight(1f),
            )
            if (book != null && audiobookExportService != null && !exportBusy) {
                Button(
                    onClick = { exportLauncher.launch(null) },
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) { Text(stringResource(R.string.export_audio)) }
            }
            if (exportBusy) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.export_running))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(0f, 0f..1f) },
                        )
                        OutlinedButton(onClick = {
                            activeExportPlan?.jobId?.let { jobId ->
                                exportScope.launch(Dispatchers.IO) { audiobookExportService?.cancel(jobId) }
                            }
                            exportJob?.cancel()
                        }) { Text(stringResource(R.string.export_cancel)) }
                    }
                }
            }
            exportMessage?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .semantics {
                            liveRegion = if (exportFailed) LiveRegionMode.Assertive else LiveRegionMode.Polite
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(message, color = if (exportFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (exportFailed) {
                        OutlinedButton(onClick = { exportLauncher.launch(null) }) {
                            Text(stringResource(R.string.choose_other_destination))
                        }
                        activeExportPlan?.let { plan ->
                            OutlinedButton(onClick = { startExport(plan) }) {
                                Text(stringResource(R.string.export_retry))
                            }
                        }
                    }
                }
            }
            if (book != null && playbackController != null) {
                AudiobookPlayerControls(
                    state = playerState,
                    onPlayPause = playbackController::playPause,
                    onSeek = playbackController::seek,
                    onPreviousChapter = { playbackController.previousChapter() },
                    onNextChapter = { playbackController.nextChapter() },
                    onJumpBackward = playbackController::jumpBackward,
                    onJumpForward = playbackController::jumpForward,
                    onSelectChapter = playbackController::selectChapter,
                    onSetJumps = playbackController::setJumpValues,
                    onSetSpeed = playbackController::setSpeed,
                    onRegenerate = { segmentId -> playbackController.requestRegeneration(segmentId) },
                )
            }
        }
    }
    exportPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { exportPlan = null },
            title = { Text(stringResource(R.string.existing_export_title)) },
            text = { Text(stringResource(R.string.existing_export_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    startExport(plan.withOverwriteConfirmation(), overwriteConfirmed = true)
                }) { Text(stringResource(R.string.replace_existing)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    startExport(plan)
                }) { Text(stringResource(R.string.save_new_names)) }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TextPreviewRoute(
    audiobookDao: AudiobookDao?,
    bookId: String?,
    onBack: () -> Unit,
) {
    val libraryController = remember(audiobookDao) { audiobookDao?.let(::LibraryController) }
    val libraryFlow: Flow<LibraryViewState> = libraryController?.state ?: flowOf(LibraryViewState())
    val libraryState by libraryFlow.collectAsState(initial = LibraryViewState())
    val book = libraryState.books.firstOrNull { it.project.id == bookId }
    val blocks by androidx.compose.runtime.produceState<List<NarrationBlockEntity>?>(
        initialValue = null,
        key1 = audiobookDao,
        key2 = bookId,
    ) {
        value = withContext(Dispatchers.IO) {
            if (audiobookDao == null || bookId == null) {
                emptyList()
            } else {
                val chapterIds = audiobookDao.findAllChapters()
                    .filter { chapter -> chapter.bookProjectId == bookId }
                    .map { chapter -> chapter.id }
                    .toSet()
                audiobookDao.findAllNarrationBlocks().filter { block ->
                    block.chapterId in chapterIds
                }
            }
        }
    }
    DisposableEffect(libraryController) {
        onDispose { libraryController?.close() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.document_preview_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { paddingValues ->
        DocumentTextPreviewScreen(
            book = book,
            blocks = blocks.orEmpty(),
            loading = blocks == null,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

private sealed interface ImportPreviewUiState {
    data object Idle : ImportPreviewUiState
    data object Loading : ImportPreviewUiState
    data class Ready(val preview: EpubImportPreview) : ImportPreviewUiState
    data class Accepted(val accepted: EpubAcceptanceResult.Published) : ImportPreviewUiState {
        val preview: EpubImportPreview get() = accepted.preview
    }
    data class Error(
        val error: com.homoludens.citacknjiga.document.epub.EpubImportError? = null,
        val duplicate: Boolean = false,
        val diagnostics: List<com.homoludens.citacknjiga.document.epub.EpubSecurityDiagnostic> = emptyList(),
    ) : ImportPreviewUiState
}

internal sealed interface PdfImportUiState {
    data object Idle : PdfImportUiState
    data object Loading : PdfImportUiState
    data object Accepting : PdfImportUiState
    data class Ready(val preview: PdfImportPreview) : PdfImportUiState
    data class Accepted(val result: PdfAcceptanceResult.Published) : PdfImportUiState
    data class Error(val diagnostic: ImportDiagnostic) : PdfImportUiState
}

private sealed interface ChapterGenerationUiState {
    data object Idle : ChapterGenerationUiState
    data class Generating(val chapterOrdinal: Int) : ChapterGenerationUiState
    data class Success(val result: EpubChapterGenerationResult) : ChapterGenerationUiState
    data class Error(val chapterOrdinal: Int, val message: String? = null) : ChapterGenerationUiState
}

private fun EpubPreviewResult.toUiState(): ImportPreviewUiState = when (this) {
    is EpubPreviewResult.Ready -> ImportPreviewUiState.Ready(preview)
    is EpubPreviewResult.Duplicate -> ImportPreviewUiState.Error(duplicate = true)
    is EpubPreviewResult.Failed -> ImportPreviewUiState.Error(error, diagnostics = securityDiagnostics)
}

private fun EpubAcceptanceResult?.toUiState(): ImportPreviewUiState = when (this) {
    is EpubAcceptanceResult.Published -> ImportPreviewUiState.Accepted(this)
    is EpubAcceptanceResult.Failed -> ImportPreviewUiState.Error(error, diagnostics = securityDiagnostics)
    null -> ImportPreviewUiState.Error()
}

private fun PdfPreviewResult.toUiState(): PdfImportUiState = when (this) {
    is PdfPreviewResult.Ready -> PdfImportUiState.Ready(preview)
    is PdfPreviewResult.Duplicate -> PdfImportUiState.Error(
        ImportDiagnostic(
            ImportDiagnosticCode.ACCEPTANCE_FAILED,
            message = "This PDF has already been imported.",
            action = "Open the existing book instead.",
        ),
    )
    is PdfPreviewResult.Failed -> PdfImportUiState.Error(diagnostic)
}

private fun PdfAcceptanceResult.toUiState(): PdfImportUiState = when (this) {
    is PdfAcceptanceResult.Published -> PdfImportUiState.Accepted(this)
    is PdfAcceptanceResult.Failed -> PdfImportUiState.Error(diagnostic)
}

private fun pdfUnavailableDiagnostic() = ImportDiagnostic(
    ImportDiagnosticCode.PDF_FEATURE_UNAVAILABLE,
    message = "PDF import is unavailable because parser qualification did not pass.",
    action = "Use EPUB import or wait for a qualified offline PDF parser.",
)

@Composable
private fun epubErrorMessage(state: ImportPreviewUiState.Error): String = when {
    state.duplicate -> stringResource(R.string.epub_duplicate)
    state.error == null -> stringResource(R.string.epub_unavailable)
    else -> stringResource(
        when (state.error) {
            com.homoludens.citacknjiga.document.epub.EpubImportError.SOURCE_UNAVAILABLE -> R.string.epub_error_source_unavailable
            com.homoludens.citacknjiga.document.epub.EpubImportError.COPY_FAILED -> R.string.epub_error_copy_failed
            com.homoludens.citacknjiga.document.epub.EpubImportError.PUBLICATION_FAILED -> R.string.epub_error_publication_failed
            com.homoludens.citacknjiga.document.epub.EpubImportError.INDEX_LOOKUP_FAILED -> R.string.epub_error_index_lookup_failed
            com.homoludens.citacknjiga.document.epub.EpubImportError.INDEX_WRITE_FAILED -> R.string.epub_error_index_write_failed
            com.homoludens.citacknjiga.document.epub.EpubImportError.SECURITY_VALIDATION_FAILED -> R.string.epub_error_security_validation_failed
        },
    )
}

@Composable
private fun EpubImportPreviewContent(
    state: ImportPreviewUiState,
    enabled: Boolean,
    onSelect: () -> Unit,
    onAccept: (EpubImportPreview) -> Unit,
    onCancel: (EpubImportPreview) -> Unit,
    onCancelLoading: () -> Unit,
    generation: ChapterGenerationUiState,
    onGenerate: (EpubAcceptanceResult.Published, Int) -> Unit,
    onPlayGenerated: (EpubChapterGenerationResult) -> Unit,
    onStopGenerated: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.import_epub_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.import_epub_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onSelect, enabled = enabled && state !is ImportPreviewUiState.Loading) {
            Text(stringResource(R.string.choose_epub))
        }
        when (state) {
            ImportPreviewUiState.Idle -> Unit
            ImportPreviewUiState.Loading -> {
                Text(
                    stringResource(R.string.preparing_preview),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedButton(onClick = onCancelLoading) { Text(stringResource(R.string.cancel_import)) }
            }
            is ImportPreviewUiState.Error -> {
                Text(
                    epubErrorMessage(state),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                state.diagnostics.forEach { diagnostic ->
                    Text(
                        EpubImportDiagnosticFormatter.formatSerbian(diagnostic),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is ImportPreviewUiState.Accepted -> {
                Text(stringResource(R.string.import_accepted))
                val noNarration = stringResource(R.string.no_narration_text)
                state.preview.canonical.chapters.forEachIndexed { index, chapter ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}. ${chapter.title}", style = MaterialTheme.typography.titleMedium)
                            Text(chapter.narrationText.ifEmpty { noNarration })
                            val retrying = generation is ChapterGenerationUiState.Error && generation.chapterOrdinal == index
                            Button(
                                onClick = { onGenerate(state.accepted, index) },
                                enabled = generation !is ChapterGenerationUiState.Generating,
                            ) { Text(stringResource(if (retrying) R.string.retry else R.string.generate_chapter)) }
                            when (val current = generation) {
                                is ChapterGenerationUiState.Generating -> if (current.chapterOrdinal == index) {
                                    Text(
                                        stringResource(R.string.generating_chapter),
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    )
                                }
                                is ChapterGenerationUiState.Success -> if (current.result.chapter.ordinal == index) {
                                    Text(stringResource(R.string.verified_wav))
                                    OutlinedButton(onClick = { onPlayGenerated(current.result) }) { Text(stringResource(R.string.play_offline)) }
                                    OutlinedButton(onClick = onStopGenerated) { Text(stringResource(R.string.stop)) }
                                }
                                is ChapterGenerationUiState.Error -> if (current.chapterOrdinal == index) {
                                    Text(
                                        stringResource(R.string.generation_failed),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                                    )
                                }
                                ChapterGenerationUiState.Idle -> Unit
                            }
                        }
                    }
                }
            }
            is ImportPreviewUiState.Ready -> {
                val preview = state.preview
                val notProvided = stringResource(R.string.not_provided)
                val noNarration = stringResource(R.string.no_narration_text)
                val authors = preview.document.metadata.authors.joinToString().ifBlank { notProvided }
                Text(preview.document.metadata.title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.author_format, authors))
                Text(stringResource(R.string.language_format, preview.document.metadata.language ?: notProvided))
                Text(
                    stringResource(
                        R.string.storage_estimate_format,
                        preview.storage.requiredBytes,
                        preview.storage.sourceBytes,
                        preview.storage.canonicalTextBytes,
                    ),
                )
                preview.canonical.chapters.forEachIndexed { index, chapter ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}. ${chapter.title}", style = MaterialTheme.typography.titleMedium)
                            Text(chapter.narrationText.ifEmpty { noNarration })
                        }
                    }
                }
                if (preview.canonical.warnings.isNotEmpty()) {
                    Text(stringResource(R.string.warnings), style = MaterialTheme.typography.titleMedium)
                    preview.canonical.warnings.forEach { warning ->
                        Text("• ${warning.message}", color = MaterialTheme.colorScheme.error)
                    }
                }
                preview.securityWarnings.forEach { diagnostic ->
                    Text(
                        EpubImportDiagnosticFormatter.formatSerbian(diagnostic),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = { onAccept(preview) }) { Text(stringResource(R.string.accept_import)) }
                OutlinedButton(onClick = { onCancel(preview) }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

internal fun parsePdfPageRange(startPage: String, endPage: String, pageCount: Int): PageRange? =
    runCatching {
        PageRange.validate(startPage.trim().toInt(), endPage.trim().toInt(), pageCount)
    }.getOrNull()

@Composable
internal fun PdfImportPreviewContent(
    state: PdfImportUiState,
    enabled: Boolean,
    onSelect: () -> Unit,
    onPreview: (startPage: Int, endPage: Int) -> Unit,
    onAccept: (PdfImportPreview) -> Unit,
    onCancel: (PdfImportPreview) -> Unit,
    onCancelLoading: () -> Unit,
) {
    var startPage by remember { mutableStateOf("1") }
    var endPage by remember { mutableStateOf("1") }
    var invalidRange by remember { mutableStateOf(false) }
    val pageCount = (state as? PdfImportUiState.Ready)?.preview?.inspection?.pageCount ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.import_pdf_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.import_pdf_description), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onSelect, enabled = enabled && state !is PdfImportUiState.Loading) {
            Text(stringResource(R.string.choose_pdf))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = startPage,
                onValueChange = { startPage = it; invalidRange = false },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.pdf_start_page)) },
                singleLine = true,
                enabled = enabled,
            )
            OutlinedTextField(
                value = endPage,
                onValueChange = { endPage = it; invalidRange = false },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.pdf_end_page)) },
                singleLine = true,
                enabled = enabled,
            )
        }
        if (pageCount > 0) {
            Text(stringResource(R.string.pdf_page_count_format, pageCount))
        }
        if (invalidRange) {
            Text(
                stringResource(R.string.pdf_invalid_range),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        when (state) {
            PdfImportUiState.Idle -> Unit
            PdfImportUiState.Loading -> {
                Text(
                    stringResource(R.string.pdf_preparing_preview),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCancelLoading) { Text(stringResource(R.string.pdf_cancel_import)) }
            }
            PdfImportUiState.Accepting -> {
                Text(
                    stringResource(R.string.pdf_accepting_import),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is PdfImportUiState.Error -> {
                Text(
                    PdfDiagnosticFormatter.format(state.diagnostic),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            is PdfImportUiState.Accepted -> {
                Text(stringResource(R.string.pdf_import_accepted))
                state.result.document.chapters.forEach { chapter ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(chapter.title, style = MaterialTheme.typography.titleMedium)
                            Text(chapter.blocks.joinToString("\n") { it.sourceText })
                        }
                    }
                }
            }
            is PdfImportUiState.Ready -> {
                val preview = state.preview
                Button(
                    onClick = {
                        val range = parsePdfPageRange(startPage, endPage, pageCount)
                        if (range == null) invalidRange = true else onPreview(range.startPage, range.endPage)
                    },
                    enabled = enabled,
                ) { Text(stringResource(R.string.pdf_preview_range)) }
                Text(stringResource(R.string.pdf_preview_text), style = MaterialTheme.typography.titleMedium)
                preview.inspection.pages.forEach { page ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.pdf_page_format, page.pageNumber), style = MaterialTheme.typography.titleMedium)
                            Text(page.text)
                        }
                    }
                }
                if (preview.inspection.warnings.isNotEmpty()) {
                    Text(stringResource(R.string.warnings), style = MaterialTheme.typography.titleMedium)
                    preview.inspection.warnings.forEach { warning ->
                        Text(stringResource(R.string.pdf_warning_format, warning.message), color = MaterialTheme.colorScheme.error)
                    }
                }
                preview.inspection.blockingDiagnostics.forEach { diagnostic ->
                    Text(PdfDiagnosticFormatter.format(diagnostic), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { onAccept(preview) }, enabled = enabled && preview.canAccept) {
                    Text(stringResource(R.string.accept_import))
                }
                OutlinedButton(onClick = { onCancel(preview) }) { Text(stringResource(R.string.pdf_discard)) }
            }
        }
    }
}

@Composable
private fun TypedTextProofContent(
    paddingValues: PaddingValues,
    variant: AppVariant,
    state: TypedTextProofState,
    onTextChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    selectedEngine: TtsEngine,
    availableEngines: List<TtsEngine>,
    onEngineSelected: (TtsEngine) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.proof_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.proof_description),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (availableEngines.size > 1) {
            Text(stringResource(R.string.engine), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableEngines.forEach { engine ->
                    if (engine == selectedEngine) {
                        Button(onClick = { onEngineSelected(engine) }) {
                            Text(stringResource(engine.label()))
                        }
                    } else {
                        OutlinedButton(onClick = { onEngineSelected(engine) }) {
                            Text(stringResource(engine.label()))
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 10,
            label = { Text(stringResource(R.string.text)) },
        )
        Text(
            stringResource(R.string.status_format, state.status.displayName()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.status == TypedTextProofStatus.ERROR) {
            Text(
                stringResource(R.string.generation_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (state.status == TypedTextProofStatus.GENERATING) {
            Text(
                stringResource(R.string.generating_chapter),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        } else {
            Button(onClick = onGenerate, enabled = state.text.isNotBlank()) {
                Text(stringResource(if (state.status == TypedTextProofStatus.ERROR) R.string.retry else R.string.generate))
            }
        }
        state.diagnostics?.let { diagnostics ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val none = stringResource(R.string.none)
                    Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium)
                    DiagnosticValue(stringResource(R.string.cleaned_text), diagnostics.cleanupText)
                    DiagnosticValue(stringResource(R.string.normalized_text), diagnostics.normalizedText)
                    DiagnosticValue(stringResource(R.string.phonemes), diagnostics.phonemes)
                    DiagnosticValue(stringResource(R.string.token_ids), diagnostics.tokenIds.joinToString())
                    DiagnosticValue(stringResource(R.string.protected_ranges), diagnostics.protectedSpans.joinToString().ifEmpty { none })
                    DiagnosticValue(stringResource(R.string.chunk_boundaries), diagnostics.chunkBoundaries.joinToString().ifEmpty { none })
                    DiagnosticValue(stringResource(R.string.voice_row), "${diagnostics.model.voice} / ${diagnostics.voiceRowIndex}")
                    DiagnosticValue(stringResource(R.string.model), "${diagnostics.model.packageId} ${diagnostics.model.packageVersion}")
                    DiagnosticValue(stringResource(R.string.package_origin), diagnostics.model.packageSha256)
                    DiagnosticValue(stringResource(R.string.runtime), diagnostics.model.runtime)
                    DiagnosticValue(stringResource(R.string.preprocessing), diagnostics.model.preprocessing)
                }
            }
        }
        state.wav?.let { wav ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ready_wav), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.wav_format_samples, wav.sampleCount))
                    Text(wav.file.name, style = MaterialTheme.typography.labelMedium)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onPlay) { Text(stringResource(R.string.play)) }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onStop) { Text(stringResource(R.string.stop)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.distribution_format, variant.distribution.id), style = MaterialTheme.typography.labelMedium)
    }
}

private fun TtsEngine.label(): Int = when (this) {
    TtsEngine.KOKORO -> R.string.engine_kokoro
    TtsEngine.VITS -> R.string.engine_vits
}

@Composable
private fun DiagnosticValue(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun TypedTextProofStatus.displayName(): String = when (this) {
    TypedTextProofStatus.IDLE -> stringResource(R.string.status_ready)
    TypedTextProofStatus.GENERATING -> stringResource(R.string.status_generating)
    TypedTextProofStatus.SUCCESS -> stringResource(R.string.status_completed)
    TypedTextProofStatus.ERROR -> stringResource(R.string.status_failed)
    TypedTextProofStatus.CANCELLED -> stringResource(R.string.cancelled)
}

private fun sendGenerationAction(context: android.content.Context, runId: String, action: GenerationAction) {
    val notificationAction = when (action) {
        GenerationAction.PAUSE -> GenerationNotificationController.ACTION_PAUSE
        GenerationAction.RESUME, GenerationAction.RETRY -> GenerationNotificationController.ACTION_RESUME
        GenerationAction.CANCEL -> GenerationNotificationController.ACTION_CANCEL
    }
    context.sendBroadcast(
        Intent(context, GenerationNotificationActionReceiver::class.java)
            .setAction(notificationAction)
            .putExtra(GenerationNotificationController.RUN_ID_EXTRA, runId),
    )
}

private class MissingProofEngine : TypedTextProofEngine {
    override suspend fun generate(
        text: String,
        onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
    ): com.homoludens.citacknjiga.proof.TypedTextProofResult =
        error("No verified model package is installed. Import a compatible package before generating.")
}
