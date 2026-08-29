package com.homoludens.citacknjiga.proof

import com.homoludens.citacknjiga.tts.onnx.InstalledModelPackage
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.OnnxTtsException
import com.homoludens.citacknjiga.tts.onnx.OnnxTtsOutput
import com.homoludens.citacknjiga.tts.onnx.OnnxTtsSession
import com.homoludens.citacknjiga.tts.onnx.PcmWavWriter
import com.homoludens.citacknjiga.tts.onnx.WavArtifact
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessingOutput
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public enum class TypedTextProofStatus {
    IDLE,
    GENERATING,
    SUCCESS,
    ERROR,
    CANCELLED,
}

public data class TypedTextProofDiagnostics(
    val cleanupText: String,
    val normalizedText: String,
    val phonemes: String,
    val tokenIds: List<Int>,
    val protectedSpans: List<String>,
    val chunkBoundaries: List<String>,
    val voiceRowIndex: Int,
    val model: TypedTextModelProvenance,
)

public data class TypedTextModelProvenance(
    val packageId: String,
    val packageVersion: String,
    val packageSha256: String,
    val voiceSha256: String = "",
    val voice: String = "Dragana",
    val runtime: String = "ONNX Runtime 1.29.0 CPU (1/1 threads)",
    val preprocessing: String = "kokoro-sr-ca5590d9 / contract 1",
)

public data class TypedTextProofState(
    val text: String = "Dobar dan.",
    val status: TypedTextProofStatus = TypedTextProofStatus.IDLE,
    val diagnostics: TypedTextProofDiagnostics? = null,
    val wav: WavArtifact? = null,
    val errorMessage: String? = null,
)

public data class TypedTextProofResult(
    val diagnostics: TypedTextProofDiagnostics,
    val wav: WavArtifact,
)

public interface TypedTextProofEngine {
    public suspend fun generate(
        text: String,
        onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
    ): TypedTextProofResult
}

/** Small lifecycle-owned state holder for the proof route. */
public class TypedTextProofController(
    private val engine: TypedTextProofEngine,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(TypedTextProofState())
    private var generation: Job? = null
    private var generationNumber = 0L

    public val state: StateFlow<TypedTextProofState> = _state.asStateFlow()

    public fun setText(text: String) {
        _state.update { it.copy(text = text, errorMessage = null) }
    }

    public fun generate() {
        if (_state.value.status == TypedTextProofStatus.GENERATING) return
        val text = _state.value.text
        if (text.isBlank()) {
            _state.update {
                it.copy(status = TypedTextProofStatus.ERROR, errorMessage = "Унесите српски текст за генерисање.")
            }
            return
        }
        val run = ++generationNumber
        generation = scope.launch {
            _state.update { it.copy(status = TypedTextProofStatus.GENERATING, wav = null, errorMessage = null) }
            try {
                val result = engine.generate(text) { diagnostics ->
                    if (run == generationNumber) _state.update { it.copy(diagnostics = diagnostics) }
                }
                ensureActive()
                if (run == generationNumber) {
                    _state.update {
                        it.copy(status = TypedTextProofStatus.SUCCESS, diagnostics = result.diagnostics, wav = result.wav)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (run == generationNumber) {
                    _state.update { it.copy(status = TypedTextProofStatus.CANCELLED, wav = null) }
                }
            } catch (failure: Throwable) {
                if (run == generationNumber) {
                    _state.update {
                        it.copy(
                            status = TypedTextProofStatus.ERROR,
                            wav = null,
                            errorMessage = failure.message ?: "Генерисање није успело.",
                        )
                    }
                }
            }
        }
    }

    public fun cancel() {
        if (_state.value.status == TypedTextProofStatus.GENERATING) generation?.cancel()
    }

    override fun close() {
        generation?.cancel()
        scope.cancel()
    }
}

/** Connects exact native Serbian preprocessing to the existing ONNX boundary. */
public class AndroidTypedTextProofEngine(
    private val modelStore: ModelPackageStore,
    private val preprocessorFactory: () -> SerbianPreprocessor,
    private val artifactDirectory: File,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TypedTextProofEngine {
    override suspend fun generate(
        text: String,
        onDiagnostics: (TypedTextProofDiagnostics) -> Unit,
    ): TypedTextProofResult = withContext(workerDispatcher) {
        currentCoroutineContext().ensureActive()
        val packageInfo = modelStore.activePackage()
            ?: throw OnnxTtsException("No verified model package is installed. Import a compatible package before generating.")
        val processed = preprocessorFactory().process(text)
        val diagnostics = processed.diagnostics(packageInfo)
        onDiagnostics(diagnostics)
        currentCoroutineContext().ensureActive()
        val chunkInputs = processed.chunkBoundaries.map(processed::tokenIdsForChunk)
        val output = OnnxTtsSession.open(modelStore, packageInfo).use { session ->
            val chunks = chunkInputs.map { tokenIds ->
                currentCoroutineContext().ensureActive()
                session.generate(tokenIds, speed = 1f)
            }
            OnnxTtsOutput(
                pcm = chunks.flatMap { it.pcm.asIterable() }.toFloatArray(),
                predDur = chunks.flatMap { it.predDur.asIterable() }.toLongArray(),
            )
        }
        currentCoroutineContext().ensureActive()
        val wav = withContext(ioDispatcher) {
            PcmWavWriter.writeAtomic(
                destination = File(artifactDirectory, "typed-proof.wav"),
                output = output,
                expectedTokenCount = output.predDur.size,
            )
        }
        TypedTextProofResult(diagnostics, wav)
    }

    private fun SerbianPreprocessingOutput.diagnostics(packageInfo: InstalledModelPackage): TypedTextProofDiagnostics =
        TypedTextProofDiagnostics(
            cleanupText = cleanupText,
            normalizedText = normalizedText,
            phonemes = phonemes,
            tokenIds = tokenIds,
            protectedSpans = protectedSpans.map { "${it.start}..${it.end}" },
            chunkBoundaries = chunkBoundaries.map { "${it.start}..${it.end}" },
            voiceRowIndex = voiceRowIndex,
            model = TypedTextModelProvenance(
                packageId = packageInfo.packageId,
                packageVersion = packageInfo.packageVersion,
                packageSha256 = packageInfo.identitySha256,
                voiceSha256 = packageInfo.voiceSha256,
            ),
        )
}
