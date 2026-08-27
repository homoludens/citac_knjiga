package com.homoludens.citacknjiga.tts.onnx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.LongBuffer

public data class OnnxRuntimeConfiguration(
    val intraOpThreads: Int = 1,
    val interOpThreads: Int = 1,
    val executionMode: OrtSession.SessionOptions.ExecutionMode =
        OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL,
)

public object OnnxRuntimeContract {
    public const val VERSION: String = "1.29.0"
    public const val SAMPLE_RATE_HZ: Int = 24_000
    public const val CHANNELS: Int = 1
    public const val VOCAB_SIZE: Int = 178
    public const val MIN_SEQUENCE_LENGTH: Int = 2
    public const val MAX_SEQUENCE_LENGTH: Int = 512
    public const val SAMPLES_PER_DURATION_FRAME: Int = 300
    public val CPU_BASELINE: OnnxRuntimeConfiguration = OnnxRuntimeConfiguration()
}

public data class OnnxTtsOutput(
    val pcm: FloatArray,
    val predDur: LongArray,
    val sampleRateHz: Int = OnnxRuntimeContract.SAMPLE_RATE_HZ,
    val channels: Int = OnnxRuntimeContract.CHANNELS,
)

public class OnnxTtsException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Owns one CPU ONNX Runtime session and the environment used to create it. */
public class OnnxTtsSession private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val styleTable: FloatArray,
) : AutoCloseable {
    private var closed: Boolean = false

    /** Runs the exported tensor boundary; preprocessing remains outside this class. */
    public fun generate(tokenIds: List<Int>, speed: Float): OnnxTtsOutput = synchronized(this) {
        checkOpen()
        validateInputs(tokenIds, speed)
        val ids = LongArray(tokenIds.size) { tokenIds[it].toLong() }
        val rowOffset = minOf(tokenIds.size - OnnxRuntimeContract.MIN_SEQUENCE_LENGTH, DraganaStyleTable.ROWS - 1) *
            DraganaStyleTable.VALUES_PER_ROW
        val styleRow = styleTable.copyOfRange(rowOffset, rowOffset + DraganaStyleTable.VALUES_PER_ROW)
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            inputs[INPUT_IDS] = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(ids),
                longArrayOf(1, ids.size.toLong()),
            )
            inputs[REF_S] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(styleRow),
                longArrayOf(1, DraganaStyleTable.VALUES_PER_ROW.toLong()),
            )
            inputs[SPEED] = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(floatArrayOf(speed)),
                longArrayOf(),
            )

            session.run(inputs).use { result ->
                val waveform = outputTensor(result, WAVEFORM, OnnxJavaType.FLOAT)
                val predDur = outputTensor(result, PRED_DUR, OnnxJavaType.INT64)
                val pcm = waveform.getFloatBuffer().toFloatArray()
                val durations = predDur.getLongBuffer().toLongArray()
                if (durations.size != ids.size) {
                    throw OnnxTtsException(
                        "$PRED_DUR length ${durations.size} does not match input_ids length ${ids.size}",
                    )
                }
                val expectedSamples = durations.fold(0L) { total, duration ->
                    Math.addExact(total, Math.multiplyExact(duration, OnnxRuntimeContract.SAMPLES_PER_DURATION_FRAME))
                }
                if (pcm.size.toLong() != expectedSamples) {
                    throw OnnxTtsException(
                        "$WAVEFORM length ${pcm.size} does not match declared duration length $expectedSamples",
                    )
                }
                return@use OnnxTtsOutput(pcm = pcm, predDur = durations)
            }
        } catch (exception: OnnxTtsException) {
            throw exception
        } catch (exception: Exception) {
            throw OnnxTtsException("ONNX inference failed", exception)
        } finally {
            OnnxValue.close(inputs)
            inputs.clear()
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            var failure: Exception? = null
            try {
                session.close()
            } catch (exception: Exception) {
                failure = exception
            }
            try {
                environment.close()
            } catch (exception: Exception) {
                if (failure == null) failure = exception else failure?.addSuppressed(exception)
            }
            failure?.let { throw it }
        }
    }

    private fun checkOpen() {
        check(!closed) { "ONNX session is closed" }
    }

    private fun validateInputs(tokenIds: List<Int>, speed: Float) {
        require(tokenIds.size in OnnxRuntimeContract.MIN_SEQUENCE_LENGTH..OnnxRuntimeContract.MAX_SEQUENCE_LENGTH) {
            "input_ids length must be in ${OnnxRuntimeContract.MIN_SEQUENCE_LENGTH}..${OnnxRuntimeContract.MAX_SEQUENCE_LENGTH}"
        }
        require(tokenIds.first() == 0 && tokenIds.last() == 0) {
            "input_ids must begin and end with boundary token 0"
        }
        require(tokenIds.all { it in 0 until OnnxRuntimeContract.VOCAB_SIZE }) {
            "input_ids contains a value outside 0..${OnnxRuntimeContract.VOCAB_SIZE - 1}"
        }
        require(speed.isFinite() && speed > 0f) { "speed must be finite and positive" }
    }

    private fun outputTensor(
        result: OrtSession.Result,
        name: String,
        type: OnnxJavaType,
    ): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: throw OnnxTtsException("ONNX result is missing named output $name")
        val tensor = value as? OnnxTensor
            ?: throw OnnxTtsException("ONNX output $name is not a tensor")
        val info = tensor.info as? TensorInfo
            ?: throw OnnxTtsException("ONNX output $name has no tensor metadata")
        if (info.type != type || info.shape.size != 1 || info.shape[0] < 0) {
            throw OnnxTtsException("ONNX output $name has unexpected type or shape: $info")
        }
        return tensor
    }

    private fun validateGraphContract() {
        if (session.inputNames != setOf(INPUT_IDS, REF_S, SPEED) ||
            session.outputNames != setOf(WAVEFORM, PRED_DUR)
        ) {
            throw OnnxTtsException(
                "ONNX graph names do not match the declared boundary: inputs=${session.inputNames}, outputs=${session.outputNames}",
            )
        }
        val inputs = session.inputInfo
        validateTensor(inputs.getValue(INPUT_IDS), OnnxJavaType.INT64, longArrayOf(1, -1), INPUT_IDS)
        validateTensor(inputs.getValue(REF_S), OnnxJavaType.FLOAT, longArrayOf(1, 256), REF_S)
        validateTensor(inputs.getValue(SPEED), OnnxJavaType.FLOAT, longArrayOf(), SPEED)
        val outputs = session.outputInfo
        validateTensor(outputs.getValue(WAVEFORM), OnnxJavaType.FLOAT, longArrayOf(-1), WAVEFORM)
        validateTensor(outputs.getValue(PRED_DUR), OnnxJavaType.INT64, longArrayOf(-1), PRED_DUR)
    }

    private fun validateTensor(node: ai.onnxruntime.NodeInfo, type: OnnxJavaType, shape: LongArray, name: String) {
        val info = node.info as? TensorInfo
            ?: throw OnnxTtsException("ONNX tensor $name has no tensor metadata")
        if (info.type != type || info.shape.size != shape.size ||
            info.shape.indices.any { shape[it] >= 0 && info.shape[it] != shape[it] }
        ) {
            throw OnnxTtsException("ONNX tensor $name has unexpected type or shape: $info")
        }
    }

    public companion object {
        const val INPUT_IDS = "input_ids"
        const val REF_S = "ref_s"
        const val SPEED = "speed"
        const val WAVEFORM = "waveform"
        const val PRED_DUR = "pred_dur"

        fun FloatBuffer.toFloatArray(): FloatArray = duplicate().let { buffer ->
            FloatArray(buffer.remaining()) { buffer.get() }
        }

        fun LongBuffer.toLongArray(): LongArray = duplicate().let { buffer ->
            LongArray(buffer.remaining()) { buffer.get() }
        }

        private fun createFromArtifacts(model: ByteArray, styleTable: FloatArray): OnnxTtsSession {
            require(styleTable.size == DraganaStyleTable.VALUE_COUNT) {
                "Voice style table has ${styleTable.size} values; expected ${DraganaStyleTable.VALUE_COUNT}"
            }
            require(styleTable.all(Float::isFinite)) { "Voice style table contains non-finite values" }
            var environment: OrtEnvironment? = null
            var session: OrtSession? = null
            try {
                val configuration = OnnxRuntimeContract.CPU_BASELINE
                // Prefer a bounded process-wide pool; per-session pools are bounded below as well.
                OrtEnvironment.ThreadingOptions().use { threading ->
                    threading.setGlobalIntraOpNumThreads(configuration.intraOpThreads)
                    threading.setGlobalInterOpNumThreads(configuration.interOpThreads)
                    environment = try {
                        OrtEnvironment.getEnvironment(
                            ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
                            "citac-knjiga-tts",
                            threading,
                        )
                    } catch (exception: IllegalStateException) {
                        if (!exception.message.orEmpty().contains("thread pool")) throw exception
                        OrtEnvironment.getEnvironment()
                    }
                }
                OrtSession.SessionOptions().use { options ->
                    options.setExecutionMode(configuration.executionMode)
                    options.setIntraOpNumThreads(configuration.intraOpThreads)
                    options.setInterOpNumThreads(configuration.interOpThreads)
                    session = requireNotNull(environment).createSession(model, options)
                }
                val result = OnnxTtsSession(
                    environment = requireNotNull(environment),
                    session = requireNotNull(session),
                    styleTable = styleTable.copyOf(),
                )
                try {
                    result.validateGraphContract()
                    return result
                } catch (failure: Throwable) {
                    result.close()
                    throw failure
                }
            } catch (failure: Throwable) {
                if (session == null) environment?.close()
                throw if (failure is OnnxTtsException) failure else OnnxTtsException("Could not open ONNX session", failure)
            }
        }

        /** Opens the active verified package without copying its model into the APK. */
        public fun open(store: ModelPackageStore): OnnxTtsSession {
            val installed = store.activePackage()
                ?: throw OnnxTtsException("No verified model package is installed")
            return open(store, installed)
        }

        /** Opens a specific package returned by [ModelPackageStore]. */
        public fun open(store: ModelPackageStore, packageInfo: InstalledModelPackage): OnnxTtsSession =
            fromArtifacts(
                model = store.readArtifact(packageInfo, "model"),
                styleTable = DraganaStyleTable.fromTorchArchive(store.readArtifact(packageInfo, "voice_style")),
            )

        /** Test and package-tool entry point for already verified artifact bytes. */
        public fun fromArtifacts(model: ByteArray, styleTable: FloatArray): OnnxTtsSession =
            createFromArtifacts(model, styleTable)
    }
}
