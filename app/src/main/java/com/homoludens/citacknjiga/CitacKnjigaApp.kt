package com.homoludens.citacknjiga

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.homoludens.citacknjiga.core.database.AudiobookDao
import com.homoludens.citacknjiga.document.epub.EpubAcceptanceResult
import com.homoludens.citacknjiga.document.epub.EpubImportPreview
import com.homoludens.citacknjiga.document.epub.EpubPreviewResult
import com.homoludens.citacknjiga.document.epub.EpubImportPreviewService
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri

@Composable
public fun CitacKnjigaApp(
    variant: AppVariant,
    audiobookDao: AudiobookDao? = null,
    proofEngine: TypedTextProofEngine? = null,
    epubImportPreviewService: EpubImportPreviewService? = null,
    epubChapterProofService: EpubChapterProofService? = null,
    playbackController: AudiobookPlayerController? = null,
    audiobookExportService: RoomAudiobookExportService? = null,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
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
                    epubChapterProofService = epubChapterProofService,
                    onOpenBook = { id -> navController.navigate(AppRoute.Book.forId(id)) },
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
    epubChapterProofService: EpubChapterProofService?,
    onOpenBook: (String) -> Unit,
) {
    val libraryController = remember(audiobookDao) { audiobookDao?.let(::LibraryController) }
    val libraryFlow: Flow<LibraryViewState> = libraryController?.state ?: flowOf(LibraryViewState())
    val libraryState by libraryFlow.collectAsState(initial = LibraryViewState())
    val controller = remember(proofEngine) {
        TypedTextProofController(proofEngine ?: MissingProofEngine())
    }
    val player = remember { LocalWavPlayer() }
    val playbackScope = rememberCoroutineScope()
    var importState by remember(epubImportPreviewService) {
        mutableStateOf<ImportPreviewUiState>(ImportPreviewUiState.Idle)
    }
    var chapterGeneration by remember { mutableStateOf<ChapterGenerationUiState>(ChapterGenerationUiState.Idle) }
    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri: Uri? ->
        if (uri != null && epubImportPreviewService != null) {
            importState = ImportPreviewUiState.Loading
            playbackScope.launch(Dispatchers.IO) {
                val result = epubImportPreviewService.previewSelected(uri)
                withContext(Dispatchers.Main.immediate) {
                    importState = result.toUiState()
                    chapterGeneration = ChapterGenerationUiState.Idle
                }
            }
        }
    }
    DisposableEffect(controller, player) {
        onDispose {
            player.close()
            controller.close()
        }
    }
    DisposableEffect(libraryController) {
        onDispose { libraryController?.close() }
    }
    val state by controller.state.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Srpski tekst u govor") }) },
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
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
            EpubImportPreviewContent(
                state = importState,
                enabled = epubImportPreviewService != null,
                onSelect = { importLauncher.launch(arrayOf("application/epub+zip", "application/zip")) },
                onAccept = { preview ->
                    importState = ImportPreviewUiState.Loading
                    playbackScope.launch(Dispatchers.IO) {
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
                                    onFailure = { ChapterGenerationUiState.Error(it.message ?: "Генерисање поглавља није успело.") },
                                )
                            }
                        }
                    }
                },
                onPlayGenerated = { result -> player.play(result.audio.file, playbackScope) },
                onStopGenerated = player::stop,
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
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var exportPlan by remember { mutableStateOf<ExportPlan?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null && bookId != null && audiobookExportService != null) {
            SafDocumentTreePermissions.persistWritePermission(context.contentResolver, uri)
            exportScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    audiobookExportService.planForProject(uri, bookId)
                }
                withContext(Dispatchers.Main.immediate) {
                    result.fold(
                        onSuccess = { plan ->
                            exportMessage = null
                            if (plan.hasCollisions) exportPlan = plan
                            else {
                                exportScope.launch(Dispatchers.IO) {
                                    val export = runCatching { audiobookExportService.export(plan) }
                                    withContext(Dispatchers.Main.immediate) {
                                        exportMessage = export.fold(
                                            onSuccess = { "Извоз је сачуван (${it.writtenNames.size} датотека)." },
                                            onFailure = { it.message ?: "Извоз није успео." },
                                        )
                                    }
                                }
                            }
                        },
                        onFailure = { exportMessage = it.message ?: "Извоз није успео." },
                    )
                }
            }
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
                title = { Text("Књига") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            BookDetailScreen(book = book, modifier = Modifier.weight(1f))
            if (book != null && audiobookExportService != null) {
                Button(
                    onClick = { exportLauncher.launch(null) },
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) { Text("Извези аудио") }
            }
            exportMessage?.let { Text(it, modifier = Modifier.padding(24.dp)) }
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
            title = { Text("Постојећи извоз") },
            text = { Text("Неколико назива већ постоји. Можете сачувати нове датотеке или заменити постојеће.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    exportPlan = null
                    exportScope.launch(Dispatchers.IO) {
                        val export = runCatching {
                            audiobookExportService!!.export(plan.withOverwriteConfirmation(), overwriteConfirmed = true)
                        }
                        withContext(Dispatchers.Main.immediate) {
                            exportMessage = export.fold(
                                onSuccess = { "Извоз је сачуван (${it.writtenNames.size} датотека)." },
                                onFailure = { it.message ?: "Извоз није успео." },
                            )
                        }
                    }
                }) { Text("Замени постојеће") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    exportPlan = null
                    exportScope.launch(Dispatchers.IO) {
                        val export = runCatching { audiobookExportService!!.export(plan) }
                        withContext(Dispatchers.Main.immediate) {
                            exportMessage = export.fold(
                                onSuccess = { "Извоз је сачуван новим називима (${it.writtenNames.size} датотека)." },
                                onFailure = { it.message ?: "Извоз није успео." },
                            )
                        }
                    }
                }) { Text("Сачувај нове") }
            },
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
    data class Error(val message: String) : ImportPreviewUiState
}

private sealed interface ChapterGenerationUiState {
    data object Idle : ChapterGenerationUiState
    data class Generating(val chapterOrdinal: Int) : ChapterGenerationUiState
    data class Success(val result: EpubChapterGenerationResult) : ChapterGenerationUiState
    data class Error(val message: String) : ChapterGenerationUiState
}

private fun EpubPreviewResult.toUiState(): ImportPreviewUiState = when (this) {
    is EpubPreviewResult.Ready -> ImportPreviewUiState.Ready(preview)
    is EpubPreviewResult.Duplicate -> ImportPreviewUiState.Error("Овај EPUB је већ увезен.")
    is EpubPreviewResult.Failed -> ImportPreviewUiState.Error(message)
}

private fun EpubAcceptanceResult?.toUiState(): ImportPreviewUiState = when (this) {
    is EpubAcceptanceResult.Published -> ImportPreviewUiState.Accepted(this)
    is EpubAcceptanceResult.Failed -> ImportPreviewUiState.Error(message)
    null -> ImportPreviewUiState.Error("Увоз EPUB-а није доступан.")
}

@Composable
private fun EpubImportPreviewContent(
    state: ImportPreviewUiState,
    enabled: Boolean,
    onSelect: () -> Unit,
    onAccept: (EpubImportPreview) -> Unit,
    onCancel: (EpubImportPreview) -> Unit,
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
        Text("Увоз EPUB књиге", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Изаберите DRM-free EPUB. Садржај се прво проверава и приказује; ништа се не чува док не прихватите преглед.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onSelect, enabled = enabled && state !is ImportPreviewUiState.Loading) {
            Text("Изабери EPUB")
        }
        when (state) {
            ImportPreviewUiState.Idle -> Unit
            ImportPreviewUiState.Loading -> Text("Припрема прегледа…")
            is ImportPreviewUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is ImportPreviewUiState.Accepted -> {
                Text("Увоз је прихваћен и сачуван.")
                state.preview.canonical.chapters.forEachIndexed { index, chapter ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}. ${chapter.title}", style = MaterialTheme.typography.titleMedium)
                            Text(chapter.narrationText.ifEmpty { "Нема текста за нарацију." })
                            Button(
                                onClick = { onGenerate(state.accepted, index) },
                                enabled = generation !is ChapterGenerationUiState.Generating,
                            ) { Text("Генериши ово поглавље") }
                            when (val current = generation) {
                                is ChapterGenerationUiState.Generating -> if (current.chapterOrdinal == index) {
                                    Text("Генерисање поглавља…")
                                }
                                is ChapterGenerationUiState.Success -> if (current.result.chapter.ordinal == index) {
                                    Text("Проверен WAV: 24 kHz, mono, PCM16")
                                    OutlinedButton(onClick = { onPlayGenerated(current.result) }) { Text("Пусти офлајн") }
                                    OutlinedButton(onClick = onStopGenerated) { Text("Заустави") }
                                }
                                is ChapterGenerationUiState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
                                ChapterGenerationUiState.Idle -> Unit
                            }
                        }
                    }
                }
            }
            is ImportPreviewUiState.Ready -> {
                val preview = state.preview
                Text(preview.document.metadata.title, style = MaterialTheme.typography.titleLarge)
                Text("Аутор: ${preview.document.metadata.authors.joinToString().ifEmpty { "није наведен" }}")
                Text("Језик: ${preview.document.metadata.language ?: "није наведен"}")
                Text(
                    "Процењено заузеће: ${preview.storage.requiredBytes} B " +
                        "(извор ${preview.storage.sourceBytes} B, текст ${preview.storage.canonicalTextBytes} B)",
                )
                preview.canonical.chapters.forEachIndexed { index, chapter ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}. ${chapter.title}", style = MaterialTheme.typography.titleMedium)
                            Text(chapter.narrationText.ifEmpty { "Нема текста за нарацију." })
                        }
                    }
                }
                if (preview.canonical.warnings.isNotEmpty()) {
                    Text("Упозорења", style = MaterialTheme.typography.titleMedium)
                    preview.canonical.warnings.forEach { warning ->
                        Text("• ${warning.message}", color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(onClick = { onAccept(preview) }) { Text("Прихвати и увези") }
                OutlinedButton(onClick = { onCancel(preview) }) { Text("Откажи") }
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
            text = "Проба српске синтезе",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Унесите текст на латиници или ћирилици. Обрада и звук остају на уређају.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 10,
            label = { Text("Текст") },
        )
        Text("Стање: ${state.status.displayName()}", style = MaterialTheme.typography.titleMedium)
        if (state.status == TypedTextProofStatus.ERROR) {
            Text(state.errorMessage ?: "Генерисање није успело.", color = MaterialTheme.colorScheme.error)
        }
        if (state.status == TypedTextProofStatus.GENERATING) {
            OutlinedButton(onClick = onCancel) { Text("Откажи") }
        } else {
            Button(onClick = onGenerate, enabled = state.text.isNotBlank()) { Text("Генериши") }
        }
        state.diagnostics?.let { diagnostics ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Дијагностика", style = MaterialTheme.typography.titleMedium)
                    DiagnosticValue("Очишћен текст", diagnostics.cleanupText)
                    DiagnosticValue("Нормализован текст", diagnostics.normalizedText)
                    DiagnosticValue("Фонеме", diagnostics.phonemes)
                    DiagnosticValue("ID токена", diagnostics.tokenIds.joinToString())
                    DiagnosticValue("Заштићени опсези", diagnostics.protectedSpans.joinToString().ifEmpty { "нема" })
                    DiagnosticValue("Границе делова", diagnostics.chunkBoundaries.joinToString().ifEmpty { "нема" })
                    DiagnosticValue("Глас / ред", "${diagnostics.model.voice} / ${diagnostics.voiceRowIndex}")
                    DiagnosticValue("Модел", "${diagnostics.model.packageId} ${diagnostics.model.packageVersion}")
                    DiagnosticValue("Порекло пакета", diagnostics.model.packageSha256)
                    DiagnosticValue("Распоред", diagnostics.model.runtime)
                    DiagnosticValue("Претпроцесирање", diagnostics.model.preprocessing)
                }
            }
        }
        state.wav?.let { wav ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Готов WAV", style = MaterialTheme.typography.titleMedium)
                    Text("24 kHz, mono, PCM16, ${wav.sampleCount} samples")
                    Text(wav.file.name, style = MaterialTheme.typography.labelMedium)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onPlay) { Text("Пусти") }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onStop) { Text("Заустави") }
                    }
                }
            }
        }
        Text("Дистрибуција: ${variant.distribution.id}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DiagnosticValue(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

private fun TypedTextProofStatus.displayName(): String = when (this) {
    TypedTextProofStatus.IDLE -> "спремно"
    TypedTextProofStatus.GENERATING -> "генерисање"
    TypedTextProofStatus.SUCCESS -> "успешно"
    TypedTextProofStatus.ERROR -> "грешка"
    TypedTextProofStatus.CANCELLED -> "отказано"
}

private class MissingProofEngine : TypedTextProofEngine {
    override suspend fun generate(
        text: String,
        onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
    ): com.homoludens.citacknjiga.proof.TypedTextProofResult =
        error("No verified model package is installed. Import a compatible package before generating.")
}
