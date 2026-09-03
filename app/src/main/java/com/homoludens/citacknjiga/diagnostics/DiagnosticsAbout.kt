package com.homoludens.citacknjiga.diagnostics

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homoludens.citacknjiga.AppVariant
import com.homoludens.citacknjiga.BuildConfig
import com.homoludens.citacknjiga.R
import com.homoludens.citacknjiga.core.diagnostics.DiagnosticEvent
import com.homoludens.citacknjiga.core.diagnostics.DiagnosticRedactor
import com.homoludens.citacknjiga.core.diagnostics.LocalDiagnostics
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.tts.onnx.DeviceParityRuntimeIdentity
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.TtsEngine
import com.homoludens.citacknjiga.tts.onnx.TtsEnginePreference
import com.homoludens.citacknjiga.tts.onnx.VitsModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.ModelPackageFailure
import com.homoludens.citacknjiga.tts.onnx.ModelPackageFailureCode
import com.homoludens.citacknjiga.modeldownload.ModelDownloadWorkContract
import com.homoludens.citacknjiga.modeldownload.ModelDownloadWorkScheduler
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

public enum class DiagnosticsStatus {
    VERIFIED,
    AVAILABLE,
    LIMITED,
    MISSING,
    UNAVAILABLE,
    INVALID,
    INCOMPATIBLE,
    ERROR,
}

public data class DiagnosticsModelState(
    val status: DiagnosticsStatus,
    val packageId: String? = null,
    val packageVersion: String? = null,
    val packageSha256: String? = null,
    val modelSha256: String? = null,
    val voiceSha256: String? = null,
    val preprocessingVersion: String? = null,
    val pronunciationVersion: String? = null,
    val runtimeId: String? = null,
    val runtimeVersion: String? = null,
    val preprocessingCompatibilityId: String? = null,
    val preprocessingContractVersion: Int? = null,
    val failureCode: String? = null,
)

public data class DiagnosticsDeviceState(
    val manufacturer: String,
    val model: String,
    val device: String,
    val apiLevel: Int,
    val abis: List<String>,
    val processorCount: Int,
    val supportsTarget: Boolean,
    val runtime: DeviceParityRuntimeIdentity,
)

public data class DiagnosticsAppState(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val schemaVersion: Int,
    val distribution: String,
)

public data class DiagnosticsAttributionReference(
    val id: String,
    val subject: String,
    val license: String,
    val sourceUrl: String,
)

public data class DiagnosticsStorageState(
    val status: DiagnosticsStatus,
    val usedBytes: Long? = null,
    val availableBytes: Long? = null,
    val capacityBytes: Long? = null,
)

public enum class DiagnosticsEvidenceKind {
    DEVICE_PARITY,
    SUSTAINED_BENCHMARK,
    TYPED_TEXT_PROOF,
    CHAPTER_EXPORT,
}

public data class DiagnosticsEvidenceState(
    val kind: DiagnosticsEvidenceKind,
    val status: DiagnosticsStatus,
    val count: Int? = null,
)

public data class DiagnosticsAboutState(
    val model: DiagnosticsModelState,
    val vitsModel: DiagnosticsModelState = DiagnosticsModelState(DiagnosticsStatus.MISSING),
    val device: DiagnosticsDeviceState,
    val app: DiagnosticsAppState,
    val attributions: List<DiagnosticsAttributionReference>,
    val storage: DiagnosticsStorageState,
    val evidence: List<DiagnosticsEvidenceState>,
) {
    public companion object {
        public fun missing(
            applicationId: String = "unknown",
            versionName: String = "unknown",
            distribution: String = "standard",
        ): DiagnosticsAboutState = DiagnosticsAboutState(
            model = DiagnosticsModelState(DiagnosticsStatus.MISSING),
            vitsModel = DiagnosticsModelState(DiagnosticsStatus.MISSING),
            device = DiagnosticsDeviceState(
                manufacturer = "unknown",
                model = "unknown",
                device = "unknown",
                apiLevel = 0,
                abis = emptyList(),
                processorCount = 0,
                supportsTarget = false,
                runtime = DeviceParityRuntimeIdentity(),
            ),
            app = DiagnosticsAppState(applicationId, versionName, 0, 2, distribution),
            attributions = emptyList(),
            storage = DiagnosticsStorageState(DiagnosticsStatus.MISSING),
            evidence = DiagnosticsEvidenceKind.entries.map { DiagnosticsEvidenceState(it, DiagnosticsStatus.MISSING) },
        )
    }
}

public val DIAGNOSTICS_ATTRIBUTION_REFERENCES: List<DiagnosticsAttributionReference> = listOf(
    DiagnosticsAttributionReference(
        id = "dragana-dataset",
        subject = "Serbian Common Voice Style TTS Dataset; Darko Milosevic; speaker Dragana",
        license = "CC BY 4.0",
        sourceUrl = "https://huggingface.co/datasets/daremc86/serbian_common_voice",
    ),
    DiagnosticsAttributionReference(
        id = "juzne-vesti-corpus",
        subject = "JuzneVesti-SR; Peter Rupnik; Nikola Ljubesic; CLARIN.SI",
        license = "CC BY-SA 4.0",
        sourceUrl = "https://www.clarin.si/repository/xmlui/handle/11356/1679",
    ),
    DiagnosticsAttributionReference(
        id = "espeak-ng",
        subject = "eSpeak-NG 1.52.0",
        license = "GPL-3.0-or-later",
        sourceUrl = "https://github.com/espeak-ng/espeak-ng/tree/1.52.0",
    ),
)

/** Builds a safe snapshot from existing runtime, storage, and evidence helpers. */
public class DiagnosticsAboutSnapshotBuilder(
    private val context: Context,
    private val variant: AppVariant,
    private val modelStore: ModelPackageStore?,
    private val storage: AppPrivateStorage?,
    private val vitsStore: VitsModelPackageStore? = modelStore?.vitsModelPackageStore,
) {
    public fun build(latestImportFailure: ModelPackageFailure? = null): DiagnosticsAboutState {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val model = when (val store = modelStore) {
            null -> DiagnosticsModelState(DiagnosticsStatus.MISSING)
            else -> runCatching { store.activePackage() }.fold(
                onSuccess = { packageInfo ->
                    packageInfo?.let {
                        DiagnosticsModelState(
                            status = DiagnosticsStatus.VERIFIED,
                            packageId = it.packageId,
                            packageVersion = it.packageVersion,
                            packageSha256 = it.identitySha256,
                            modelSha256 = it.modelSha256,
                            voiceSha256 = it.voiceSha256,
                            preprocessingVersion = "kokoro-sr-ca5590d9/contract-1",
                            pronunciationVersion = "espeak-ng-1.52.0-sr",
                            runtimeId = it.runtimeId,
                            runtimeVersion = it.runtimeVersion,
                            preprocessingCompatibilityId = it.preprocessingCompatibilityId,
                            preprocessingContractVersion = it.preprocessingContractVersion,
                            failureCode = latestImportFailure?.code?.name,
                        )
                    } ?: DiagnosticsModelState(
                        status = latestImportFailure?.let(::modelStatus) ?: DiagnosticsStatus.MISSING,
                        failureCode = latestImportFailure?.code?.name,
                    )
                },
                onFailure = { failure ->
                    DiagnosticsModelState(
                        status = modelStatus(ModelPackageStore.normalizeFailure(failure)),
                        failureCode = ModelPackageStore.normalizeFailure(failure).code.name,
                    )
                },
            )
        }
        val vitsModel = when (val store = vitsStore) {
            null -> DiagnosticsModelState(DiagnosticsStatus.MISSING)
            else -> runCatching { store.activePackage() }.fold(
                onSuccess = { packageInfo ->
                    packageInfo?.let {
                        DiagnosticsModelState(
                            status = DiagnosticsStatus.VERIFIED,
                            packageId = it.packageId,
                            packageVersion = it.packageVersion,
                            packageSha256 = it.identitySha256,
                            modelSha256 = it.modelSha256,
                            voiceSha256 = it.voiceSha256,
                            runtimeId = it.runtimeId,
                            runtimeVersion = it.runtimeVersion,
                            preprocessingCompatibilityId = it.preprocessingCompatibilityId,
                            preprocessingContractVersion = it.preprocessingContractVersion,
                        )
                    } ?: DiagnosticsModelState(DiagnosticsStatus.MISSING)
                },
                onFailure = { DiagnosticsModelState(DiagnosticsStatus.ERROR) },
            )
        }
        val runtime = DeviceParityRuntimeIdentity()
        val device = DiagnosticsDeviceState(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            processorCount = Runtime.getRuntime().availableProcessors(),
            supportsTarget = Build.VERSION.SDK_INT >= 30 && "arm64-v8a" in Build.SUPPORTED_ABIS,
            runtime = runtime,
        )
        val app = DiagnosticsAppState(
            applicationId = context.packageName,
            versionName = packageInfo?.versionName ?: "unknown",
            versionCode = packageInfo?.longVersionCode ?: 0L,
            schemaVersion = 2,
            distribution = variant.distribution.id,
        )
        return DiagnosticsAboutState(
            model = model,
            vitsModel = vitsModel,
            device = device,
            app = app,
            attributions = DIAGNOSTICS_ATTRIBUTION_REFERENCES,
            storage = storage?.let(::storageState) ?: DiagnosticsStorageState(DiagnosticsStatus.MISSING),
            evidence = listOf(
                reportEvidence(
                    DiagnosticsEvidenceKind.DEVICE_PARITY,
                    storage?.parityReportsDirectory?.resolve("device-parity-report.json"),
                ),
                reportEvidence(
                    DiagnosticsEvidenceKind.SUSTAINED_BENCHMARK,
                    storage?.benchmarkReportsDirectory?.resolve("android-benchmark-report.json"),
                ),
                DiagnosticsEvidenceState(DiagnosticsEvidenceKind.TYPED_TEXT_PROOF, DiagnosticsStatus.AVAILABLE),
                DiagnosticsEvidenceState(DiagnosticsEvidenceKind.CHAPTER_EXPORT, DiagnosticsStatus.AVAILABLE),
            ),
        )
    }

    private fun modelStatus(failure: ModelPackageFailure): DiagnosticsStatus = when (failure.code) {
        ModelPackageFailureCode.INVALID_ARCHIVE,
        ModelPackageFailureCode.INVALID_MANIFEST,
        ModelPackageFailureCode.CHECKSUM_MISMATCH,
        -> DiagnosticsStatus.INVALID
        ModelPackageFailureCode.INCOMPATIBLE -> DiagnosticsStatus.INCOMPATIBLE
        ModelPackageFailureCode.NO_VALID_PACKAGE -> DiagnosticsStatus.MISSING
        else -> DiagnosticsStatus.ERROR
    }

    private fun storageState(value: AppPrivateStorage): DiagnosticsStorageState = runCatching {
        val root = value.rootDirectory
        val capacity = root.totalSpace
        val available = root.usableSpace
        if (!root.exists() || capacity <= 0L) {
            DiagnosticsStorageState(DiagnosticsStatus.UNAVAILABLE)
        } else {
            DiagnosticsStorageState(
                status = if (available < STORAGE_WARNING_BYTES) DiagnosticsStatus.LIMITED else DiagnosticsStatus.AVAILABLE,
                usedBytes = directorySize(root),
                availableBytes = available,
                capacityBytes = capacity,
            )
        }
    }.getOrElse { DiagnosticsStorageState(DiagnosticsStatus.UNAVAILABLE) }

    private fun directorySize(file: File): Long = if (file.isFile) {
        file.length().coerceAtLeast(0L)
    } else {
        file.listFiles()?.sumOf(::directorySize) ?: 0L
    }

    private fun reportEvidence(kind: DiagnosticsEvidenceKind, file: File?): DiagnosticsEvidenceState {
        if (file == null || !file.isFile) return DiagnosticsEvidenceState(kind, DiagnosticsStatus.MISSING)
        val content = runCatching {
            file.inputStream().bufferedReader().use { it.readText().take(MAX_REPORT_BYTES) }
        }.getOrNull() ?: return DiagnosticsEvidenceState(kind, DiagnosticsStatus.UNAVAILABLE)
        val status = Regex("\"status\"\\s*:\\s*\"([A-Za-z0-9_.-]+)\"")
            .find(content)?.groupValues?.getOrNull(1)?.lowercase()
        val count = Regex("\"vectors_evaluated\"\\s*:\\s*(\\d+)")
            .find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when (status) {
            "passed", "completed" -> DiagnosticsEvidenceState(kind, DiagnosticsStatus.VERIFIED, count)
            "blocked", "failed" -> DiagnosticsEvidenceState(kind, DiagnosticsStatus.UNAVAILABLE, count)
            else -> DiagnosticsEvidenceState(kind, DiagnosticsStatus.UNAVAILABLE)
        }
    }

    private companion object {
        const val STORAGE_WARNING_BYTES = 64L * 1024L
        const val MAX_REPORT_BYTES = 128 * 1024
    }
}

public object DiagnosticsExport {
    public fun render(state: DiagnosticsAboutState, events: List<DiagnosticEvent>): String = buildString {
        appendLine("citac-knjiga-diagnostics-v1")
        appendRedacted("model", "status", state.model.status.name.lowercase())
        state.model.packageId?.let { appendRedacted("model", "packageId", it) }
        state.model.packageVersion?.let { appendRedacted("model", "version", "v$it") }
        state.model.packageSha256?.let { appendRedacted("model", "sha256", it) }
        state.model.modelSha256?.let { appendRedacted("model", "modelSha256", it) }
        state.model.voiceSha256?.let { appendRedacted("model", "voiceSha256", it) }
        state.model.preprocessingVersion?.let { appendRedacted("model", "version", it) }
        state.model.pronunciationVersion?.let { appendRedacted("model", "version", it) }
        state.model.runtimeId?.let { appendRedacted("model", "runtime", it) }
        state.model.runtimeVersion?.let { appendRedacted("model", "version", it) }
        state.model.preprocessingCompatibilityId?.let { appendRedacted("model", "version", it) }
        state.model.preprocessingContractVersion?.let { appendRedacted("model", "count", it.toString()) }
        state.model.failureCode?.let { appendRedacted("model", "errorCode", it) }
        appendRedacted("device", "count", state.device.apiLevel.toString())
        state.device.abis.firstOrNull()?.let { appendRedacted("device", "abi", it) }
        appendRedacted("device", "count", state.device.processorCount.toString())
        appendRedacted("runtime", "runtime", "v${state.device.runtime.version}")
        appendRedacted("runtime", "provider", state.device.runtime.executionProvider)
        appendRedacted("runtime", "count", state.device.runtime.intraOpThreads.toString())
        appendRedacted("runtime", "count", state.device.runtime.interOpThreads.toString())
        appendRedacted("app", "version", "v${state.app.versionName}")
        appendRedacted("app", "count", state.app.versionCode.toString())
        appendRedacted("app", "count", state.app.schemaVersion.toString())
        appendRedacted("app", "distribution", state.app.distribution)
        appendRedacted("storage", "status", state.storage.status.name.lowercase())
        state.storage.usedBytes?.let { appendRedacted("storage", "sizeBytes", it.toString()) }
        state.storage.availableBytes?.let { appendRedacted("storage", "sizeBytes", it.toString()) }
        state.storage.capacityBytes?.let { appendRedacted("storage", "sizeBytes", it.toString()) }
        state.attributions.forEach { reference ->
            appendRedacted("attribution", "packageId", reference.id)
            appendRedacted("attribution", "license", reference.license.lowercase().replace(' ', '-'))
        }
        state.evidence.forEach { evidence ->
            appendRedacted("evidence", "status", evidence.status.name.lowercase())
            evidence.count?.let { appendRedacted("evidence", "count", it.toString()) }
        }
        appendLine("events:")
        val eventExport = events.joinToString("\n") { event ->
            val attributes = event.attributes.entries.sortedBy { it.key }
                .joinToString(",") { (key, value) -> "$key=${DiagnosticRedactor.redact(key, value)}" }
            "${event.timestampMillis}|${event.level.name}|${DiagnosticRedactor.component(event.component)}|" +
                "${DiagnosticRedactor.message(event.message)}|$attributes"
        }
        appendLine(eventExport.ifBlank { "none" })
    }

    private fun StringBuilder.appendRedacted(section: String, key: String, value: String) {
        append(section).append('.').append(key).append('=')
            .append(DiagnosticRedactor.redact(key, value)).appendLine()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun DiagnosticsAboutRoute(
    diagnostics: LocalDiagnostics,
    modelPackageStore: ModelPackageStore?,
    vitsModelPackageStore: VitsModelPackageStore?,
    modelDownloadScheduler: ModelDownloadWorkScheduler? = null,
    ttsEnginePreference: TtsEnginePreference? = null,
    privateStorage: AppPrivateStorage?,
    variant: AppVariant,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(modelPackageStore, privateStorage, variant) { mutableStateOf<DiagnosticsAboutState?>(null) }
    var exportMessage by remember { mutableStateOf<Int?>(null) }
    var latestImportFailure by remember { mutableStateOf<ModelPackageFailure?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var vitsImportBusy by remember { mutableStateOf(false) }
    var vitsImportMessage by remember { mutableStateOf<Int?>(null) }
    var releaseMessage by remember { mutableStateOf<Int?>(null) }
    var selectedEngine by remember(ttsEnginePreference) {
        mutableStateOf(ttsEnginePreference?.selected ?: TtsEngine.KOKORO)
    }
    var availableEngines by remember(ttsEnginePreference) { mutableStateOf(listOf(TtsEngine.KOKORO)) }
    val kokoroDownload = rememberModelDownloadInfo(modelDownloadScheduler, ModelEngine.KOKORO)
    val vitsDownload = rememberModelDownloadInfo(modelDownloadScheduler, ModelEngine.VITS)
    val releaseUrl = BuildConfig.MODEL_RELEASE_URL
    val releaseConfigured = releaseUrl.isNotBlank()
    val releaseAvailable = remember(releaseUrl, context) { ModelReleaseAction.canOpen(context, releaseUrl) }
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && modelPackageStore != null && !importBusy) {
            importBusy = true
            latestImportFailure = null
            importJob = scope.launch(Dispatchers.IO) {
                val result = modelPackageStore.tryImportFromSaf(context.contentResolver, uri)
                withContext(Dispatchers.Main.immediate) {
                    importBusy = false
                    latestImportFailure = (result as? com.homoludens.citacknjiga.tts.onnx.ModelPackageImportResult.Failure)?.failure
                    state = DiagnosticsAboutSnapshotBuilder(
                        context,
                        variant,
                        modelPackageStore,
                        privateStorage,
                        vitsModelPackageStore,
                    )
                        .build(latestImportFailure)
                }
            }
        }
    }
    val vitsImportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && vitsModelPackageStore != null && !vitsImportBusy) {
            vitsImportBusy = true
            vitsImportMessage = null
            scope.launch(Dispatchers.IO) {
                val result = modelPackageStore?.tryImportVitsFromSaf(context.contentResolver, uri)
                withContext(Dispatchers.Main.immediate) {
                    vitsImportBusy = false
                    vitsImportMessage = if (result is com.homoludens.citacknjiga.tts.onnx.ModelPackageImportResult.Success) {
                        R.string.vits_import_success
                    } else {
                        R.string.vits_import_failed
                    }
                }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { importJob?.cancel() }
    }
    val exportLauncher = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        val current = state
        if (uri != null && current != null) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(DiagnosticsExport.render(current, diagnostics.snapshot()).toByteArray(Charsets.UTF_8))
                    } ?: error("diagnostics export unavailable")
                }
                withContext(Dispatchers.Main.immediate) {
                    exportMessage = if (result.isSuccess) R.string.diagnostics_export_success
                    else R.string.diagnostics_export_failed
                }
            }
        }
    }
    LaunchedEffect(modelPackageStore, privateStorage, variant) {
        state = withContext(Dispatchers.IO) {
            DiagnosticsAboutSnapshotBuilder(
                context,
                variant,
                modelPackageStore,
                privateStorage,
                vitsModelPackageStore,
            ).build(latestImportFailure)
        }
    }
    LaunchedEffect(ttsEnginePreference) {
        availableEngines = withContext(Dispatchers.IO) {
            ttsEnginePreference?.refresh() ?: listOf(TtsEngine.KOKORO)
        }
        selectedEngine = ttsEnginePreference?.selected ?: TtsEngine.KOKORO
    }
    DiagnosticsAboutScreen(
        state = state,
        exportMessage = exportMessage?.let { stringResource(it) },
        onBack = onBack,
        onExport = { exportLauncher.launch("citac-knjiga-diagnostics.txt") },
        onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        onImportVits = { vitsImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        vitsImportEnabled = vitsModelPackageStore != null && !vitsImportBusy,
        vitsImportBusy = vitsImportBusy,
        vitsImportMessage = vitsImportMessage?.let { stringResource(it) },
        modelDownloadScheduler = modelDownloadScheduler,
        selectedEngine = selectedEngine,
        availableEngines = availableEngines,
        onEngineSelected = {
            scope.launch(Dispatchers.IO) {
                ttsEnginePreference?.select(it)
                withContext(Dispatchers.Main.immediate) {
                    selectedEngine = ttsEnginePreference?.selected ?: TtsEngine.KOKORO
                }
            }
        },
        kokoroDownload = kokoroDownload,
        vitsDownload = vitsDownload,
        onDownload = { engine -> modelDownloadScheduler?.enqueue(engine) },
        onCancelDownload = { engine -> modelDownloadScheduler?.cancel(engine) },
        importEnabled = modelPackageStore != null && !importBusy,
        importBusy = importBusy,
        releaseConfigured = releaseConfigured,
        releaseAvailable = releaseAvailable,
        releaseMessage = releaseMessage?.let { stringResource(it) },
        onGetModelPackage = {
            releaseMessage = if (ModelReleaseAction.open(context, releaseUrl)) null else R.string.model_release_unavailable
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun DiagnosticsAboutScreen(
    state: DiagnosticsAboutState?,
    exportMessage: String? = null,
    onBack: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onImportVits: () -> Unit = {},
    importEnabled: Boolean = true,
    importBusy: Boolean = false,
    vitsImportEnabled: Boolean = false,
    vitsImportBusy: Boolean = false,
    vitsImportMessage: String? = null,
    modelDownloadScheduler: ModelDownloadWorkScheduler? = null,
    selectedEngine: TtsEngine = TtsEngine.KOKORO,
    availableEngines: List<TtsEngine> = listOf(TtsEngine.KOKORO),
    onEngineSelected: (TtsEngine) -> Unit = {},
    kokoroDownload: WorkInfo? = null,
    vitsDownload: WorkInfo? = null,
    onDownload: (ModelEngine) -> Unit = {},
    onCancelDownload: (ModelEngine) -> Unit = {},
    releaseConfigured: Boolean = false,
    releaseAvailable: Boolean = false,
    releaseMessage: String? = null,
    onGetModelPackage: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_about_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { paddingValues ->
        if (state == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                Text(stringResource(R.string.diagnostics_loading), modifier = Modifier.padding(horizontal = 24.dp))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.diagnostics_about_description), style = MaterialTheme.typography.bodyLarge)
                DiagnosticsSection(stringResource(R.string.diagnostics_model_section)) {
                    StatusValue(stringResource(R.string.diagnostics_verification), state.model.status)
                    if (TtsEngine.entries.size > 1) {
                        Text(stringResource(R.string.engine), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TtsEngine.entries.forEach { engine ->
                                val enabled = engine in availableEngines
                                if (engine == selectedEngine) {
                                    Button(onClick = { onEngineSelected(engine) }, enabled = enabled) {
                                        Text(stringResource(engine.label()))
                                    }
                                } else {
                                    OutlinedButton(onClick = { onEngineSelected(engine) }, enabled = enabled) {
                                        Text(stringResource(engine.label()))
                                    }
                                }
                            }
                        }
                        if (availableEngines.size < TtsEngine.entries.size) {
                            Text(stringResource(R.string.engine_unavailable), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    InfoValue(stringResource(R.string.diagnostics_package_id), state.model.packageId)
                    InfoValue(stringResource(R.string.diagnostics_package_version), state.model.packageVersion)
                    InfoValue(stringResource(R.string.diagnostics_package_checksum), state.model.packageSha256)
                    InfoValue(stringResource(R.string.diagnostics_model_checksum), state.model.modelSha256)
                    InfoValue(stringResource(R.string.diagnostics_voice_checksum), state.model.voiceSha256)
                    InfoValue(stringResource(R.string.diagnostics_preprocessing_version), state.model.preprocessingVersion)
                    InfoValue(stringResource(R.string.diagnostics_pronunciation_version), state.model.pronunciationVersion)
                    InfoValue(stringResource(R.string.diagnostics_runtime), state.model.runtimeId?.let { "$it ${state.model.runtimeVersion.orEmpty()}" })
                    InfoValue(
                        stringResource(R.string.diagnostics_preprocessing_version),
                        state.model.preprocessingCompatibilityId?.let { "$it/${state.model.preprocessingContractVersion}" },
                    )
                    Button(onClick = onImport, enabled = importEnabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.model_import_action))
                    }
                    Button(onClick = onImportVits, enabled = vitsImportEnabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vits_import_action))
                    }
                    if (vitsImportBusy) {
                        Text(stringResource(R.string.vits_import_busy))
                    }
                    vitsImportMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (importBusy) {
                        Text(
                            stringResource(R.string.model_import_busy),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } else if (state.model.status != DiagnosticsStatus.VERIFIED) {
                        Text(modelAction(state.model), color = MaterialTheme.colorScheme.error)
                    } else if (state.model.failureCode != null) {
                        Text(modelAction(state.model), color = MaterialTheme.colorScheme.error)
                    }
                    ModelDownloadControls(
                        engine = ModelEngine.KOKORO,
                        workInfo = kokoroDownload,
                        installed = state.model.status == DiagnosticsStatus.VERIFIED,
                        enabled = modelDownloadScheduler != null,
                        onDownload = onDownload,
                        onCancel = onCancelDownload,
                    )
                    ModelDownloadControls(
                        engine = ModelEngine.VITS,
                        workInfo = vitsDownload,
                        installed = state.vitsModel.status == DiagnosticsStatus.VERIFIED,
                        enabled = modelDownloadScheduler != null,
                        onDownload = onDownload,
                        onCancel = onCancelDownload,
                    )
                    if (releaseConfigured) {
                        Button(
                            onClick = onGetModelPackage,
                            enabled = releaseAvailable,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.model_get_action)) }
                        if (!releaseAvailable) {
                            Text(stringResource(R.string.model_release_unavailable), color = MaterialTheme.colorScheme.error)
                        }
                        releaseMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                DiagnosticsSection(stringResource(R.string.diagnostics_device_section)) {
                    InfoValue(stringResource(R.string.diagnostics_device), "${state.device.manufacturer} ${state.device.model} (${state.device.device})")
                    InfoValue(stringResource(R.string.diagnostics_api), state.device.apiLevel.takeIf { it > 0 }?.toString())
                    InfoValue(stringResource(R.string.diagnostics_abi), state.device.abis.joinToString().ifBlank { null })
                    InfoValue(stringResource(R.string.diagnostics_processors), state.device.processorCount.takeIf { it > 0 }?.toString())
                    InfoValue(stringResource(R.string.diagnostics_runtime), state.device.runtime.coordinate)
                    InfoValue(stringResource(R.string.diagnostics_provider), state.device.runtime.executionProvider)
                    InfoValue(
                        stringResource(R.string.diagnostics_threads),
                        "${state.device.runtime.intraOpThreads}/${state.device.runtime.interOpThreads}",
                    )
                    InfoValue(stringResource(R.string.diagnostics_execution_mode), state.device.runtime.executionMode)
                    Text(
                        stringResource(
                            when {
                                state.device.apiLevel <= 0 || state.device.abis.isEmpty() -> R.string.diagnostics_device_missing
                                state.device.supportsTarget -> R.string.diagnostics_device_supported
                                else -> R.string.diagnostics_device_limited
                            },
                        ),
                        color = if (state.device.supportsTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                DiagnosticsSection(stringResource(R.string.diagnostics_app_section)) {
                    InfoValue(stringResource(R.string.diagnostics_application_id), state.app.applicationId)
                    InfoValue(stringResource(R.string.diagnostics_version), state.app.versionName)
                    InfoValue(stringResource(R.string.diagnostics_version_code), state.app.versionCode.toString())
                    InfoValue(stringResource(R.string.diagnostics_schema), state.app.schemaVersion.toString())
                    InfoValue(stringResource(R.string.diagnostics_distribution), state.app.distribution)
                }
                DiagnosticsSection(stringResource(R.string.diagnostics_license_section)) {
                    if (state.attributions.isEmpty()) {
                        Text(stringResource(R.string.diagnostics_attribution_missing), color = MaterialTheme.colorScheme.error)
                    } else {
                        state.attributions.forEach { reference ->
                            Text(reference.subject, style = MaterialTheme.typography.titleSmall)
                            InfoValue(stringResource(R.string.diagnostics_license), reference.license)
                            InfoValue(stringResource(R.string.diagnostics_reference), reference.sourceUrl)
                        }
                    }
                }
                DiagnosticsSection(stringResource(R.string.diagnostics_storage_section)) {
                    StatusValue(stringResource(R.string.diagnostics_storage_status), state.storage.status)
                    InfoValue(stringResource(R.string.diagnostics_storage_used), state.storage.usedBytes?.let(::formatBytes))
                    InfoValue(stringResource(R.string.diagnostics_storage_available), state.storage.availableBytes?.let(::formatBytes))
                    InfoValue(stringResource(R.string.diagnostics_storage_capacity), state.storage.capacityBytes?.let(::formatBytes))
                    if (state.storage.status == DiagnosticsStatus.MISSING || state.storage.status == DiagnosticsStatus.UNAVAILABLE) {
                        Text(stringResource(R.string.diagnostics_storage_action), color = MaterialTheme.colorScheme.error)
                    }
                }
                DiagnosticsSection(stringResource(R.string.diagnostics_policy_section)) {
                    Text(stringResource(R.string.diagnostics_offline_policy))
                    Text(stringResource(R.string.diagnostics_network_policy))
                }
                DiagnosticsSection(stringResource(R.string.diagnostics_evidence_section)) {
                    state.evidence.forEach { evidence ->
                        val evidenceLabel = evidenceStatus(evidence.status, evidence.count)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(evidenceTitle(evidence.kind), modifier = Modifier.weight(1f))
                            Text(
                                evidenceLabel,
                                modifier = Modifier.semantics { stateDescription = evidenceLabel },
                            )
                        }
                    }
                    Text(stringResource(R.string.diagnostics_evidence_note), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostics_export))
                }
                exportMessage?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                Text(stringResource(R.string.diagnostics_export_description), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private enum class DownloadDisplayStatus {
    IDLE,
    WAITING,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    FAILED,
    CANCELED,
}

private data class DownloadDisplay(
    val status: DownloadDisplayStatus,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
)

@Composable
private fun rememberModelDownloadInfo(
    scheduler: ModelDownloadWorkScheduler?,
    engine: ModelEngine,
): WorkInfo? {
    val flow = remember(scheduler, engine) { scheduler?.workInfo(engine) }
    return flow?.collectAsState(initial = null)?.value
}

@Composable
private fun ModelDownloadControls(
    engine: ModelEngine,
    workInfo: WorkInfo?,
    installed: Boolean,
    enabled: Boolean,
    onDownload: (ModelEngine) -> Unit,
    onCancel: (ModelEngine) -> Unit,
) {
    val display = downloadDisplay(workInfo, installed)
    val status = downloadStatusText(display.status)
    val active = display.status == DownloadDisplayStatus.WAITING ||
        display.status == DownloadDisplayStatus.DOWNLOADING ||
        display.status == DownloadDisplayStatus.VERIFYING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = status
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(downloadEngineName(engine), style = MaterialTheme.typography.titleSmall)
        Text(status)
        if (display.totalBytes > 0L && display.status != DownloadDisplayStatus.INSTALLED) {
            val fraction = (display.bytesDownloaded.toFloat() / display.totalBytes).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
            )
            Text("${formatBytes(display.bytesDownloaded)} / ${formatBytes(display.totalBytes)}")
        }
        if (active) {
            OutlinedButton(onClick = { onCancel(engine) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_download_cancel))
            }
        } else {
            Button(onClick = { onDownload(engine) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (display.status == DownloadDisplayStatus.FAILED) {
                    R.string.model_download_retry
                } else {
                    R.string.model_download_start
                }))
            }
        }
    }
}

private fun downloadDisplay(workInfo: WorkInfo?, installed: Boolean): DownloadDisplay {
    val progress = workInfo?.progress
    val bytes = progress?.getLong(ModelDownloadWorkContract.BYTES_DOWNLOADED_KEY, 0L) ?: 0L
    val total = progress?.getLong(ModelDownloadWorkContract.TOTAL_BYTES_KEY, 0L) ?: 0L
    val status = when (workInfo?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadDisplayStatus.WAITING
        WorkInfo.State.RUNNING -> if (
            progress?.getString(ModelDownloadWorkContract.STATUS_KEY) == "VERIFYING"
        ) DownloadDisplayStatus.VERIFYING else DownloadDisplayStatus.DOWNLOADING
        WorkInfo.State.SUCCEEDED -> DownloadDisplayStatus.INSTALLED
        WorkInfo.State.FAILED -> DownloadDisplayStatus.FAILED
        WorkInfo.State.CANCELLED -> DownloadDisplayStatus.CANCELED
        null -> if (installed) DownloadDisplayStatus.INSTALLED else DownloadDisplayStatus.IDLE
    }
    return DownloadDisplay(status, bytes, total)
}

@Composable
private fun downloadEngineName(engine: ModelEngine): String = stringResource(
    if (engine == ModelEngine.KOKORO) R.string.model_download_kokoro else R.string.model_download_vits,
)

@Composable
private fun downloadStatusText(status: DownloadDisplayStatus): String = stringResource(
    when (status) {
        DownloadDisplayStatus.IDLE -> R.string.model_download_idle
        DownloadDisplayStatus.WAITING -> R.string.model_download_offline
        DownloadDisplayStatus.DOWNLOADING -> R.string.model_download_downloading
        DownloadDisplayStatus.VERIFYING -> R.string.model_download_verifying
        DownloadDisplayStatus.INSTALLED -> R.string.model_download_installed
        DownloadDisplayStatus.FAILED -> R.string.model_download_failed
        DownloadDisplayStatus.CANCELED -> R.string.model_download_canceled
    },
)

@Composable
private fun DiagnosticsSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusValue(label: String, status: DiagnosticsStatus) {
    InfoValue(label, statusText(status))
}

@Composable
private fun InfoValue(label: String, value: String?) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value ?: stringResource(R.string.not_provided), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun statusText(status: DiagnosticsStatus): String = stringResource(
    when (status) {
        DiagnosticsStatus.VERIFIED -> R.string.diagnostics_status_verified
        DiagnosticsStatus.AVAILABLE -> R.string.diagnostics_status_available
        DiagnosticsStatus.LIMITED -> R.string.diagnostics_status_limited
        DiagnosticsStatus.MISSING -> R.string.diagnostics_status_missing
        DiagnosticsStatus.UNAVAILABLE -> R.string.diagnostics_status_unavailable
        DiagnosticsStatus.INVALID -> R.string.diagnostics_status_invalid
        DiagnosticsStatus.INCOMPATIBLE -> R.string.diagnostics_status_incompatible
        DiagnosticsStatus.ERROR -> R.string.diagnostics_status_error
    },
)

@Composable
private fun evidenceTitle(kind: DiagnosticsEvidenceKind): String = stringResource(
    when (kind) {
        DiagnosticsEvidenceKind.DEVICE_PARITY -> R.string.diagnostics_evidence_parity
        DiagnosticsEvidenceKind.SUSTAINED_BENCHMARK -> R.string.diagnostics_evidence_benchmark
        DiagnosticsEvidenceKind.TYPED_TEXT_PROOF -> R.string.diagnostics_evidence_typed_text
        DiagnosticsEvidenceKind.CHAPTER_EXPORT -> R.string.diagnostics_evidence_export
    },
)

@Composable
private fun evidenceStatus(status: DiagnosticsStatus, count: Int?): String {
    val base = statusText(status)
    return if (count == null) base else stringResource(R.string.diagnostics_evidence_count_format, base, count)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.1f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun TtsEngine.label(): Int = when (this) {
    TtsEngine.KOKORO -> R.string.engine_kokoro
    TtsEngine.VITS -> R.string.engine_vits
}

@Composable
private fun modelAction(model: DiagnosticsModelState): String = when (model.failureCode) {
    ModelPackageFailureCode.SOURCE_UNAVAILABLE.name -> stringResource(R.string.model_action_source)
    ModelPackageFailureCode.STORAGE.name -> stringResource(R.string.model_action_storage)
    ModelPackageFailureCode.INVALID_ARCHIVE.name -> stringResource(R.string.model_action_archive)
    ModelPackageFailureCode.INVALID_MANIFEST.name -> stringResource(R.string.model_action_manifest)
    ModelPackageFailureCode.CHECKSUM_MISMATCH.name -> stringResource(R.string.model_action_checksum)
    ModelPackageFailureCode.INCOMPATIBLE.name -> stringResource(R.string.model_action_incompatible)
    ModelPackageFailureCode.PUBLICATION.name -> stringResource(R.string.model_action_publication)
    ModelPackageFailureCode.NO_VALID_PACKAGE.name -> stringResource(R.string.model_action_missing)
    ModelPackageFailureCode.ERROR.name -> stringResource(R.string.model_action_error)
    else -> stringResource(R.string.diagnostics_model_action)
}
