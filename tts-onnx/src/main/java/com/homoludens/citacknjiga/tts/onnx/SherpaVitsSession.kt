package com.homoludens.citacknjiga.tts.onnx

public interface SherpaVitsNativeSession : AutoCloseable {
    public fun generate(tokenIds: IntArray, speakerId: Int, speed: Float): VitsNativeAudio
}

public fun interface SherpaVitsSessionFactory {
    public fun open(modelPath: String, tokensPath: String, lexiconPath: String?): SherpaVitsNativeSession
}

/** Owns one Sherpa session and closes it on every coroutine exit path. */
public class SherpaVitsSession private constructor(
    private val native: SherpaVitsNativeSession,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    public fun generate(
        tokenIds: IntArray,
        speakerId: Int,
        speed: Float,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): VitsNativeAudio {
        check(!closed) { "Sherpa VITS session is closed" }
        check(!isCancelled()) { "Sherpa VITS generation cancelled" }
        val output = native.generate(tokenIds, speakerId, speed)
        check(!isCancelled()) { "Sherpa VITS generation cancelled" }
        VitsAudioOutputValidator.validateNative(output)
        return output
    }

    override fun close() {
        synchronized(this) {
            if (!closed) {
                closed = true
                native.close()
            }
        }
    }

    public companion object {
        public fun fromNative(native: SherpaVitsNativeSession): SherpaVitsSession = SherpaVitsSession(native)

        public fun open(
            store: VitsModelPackageStore,
            packageInfo: InstalledModelPackage = store.activePackage()
                ?: throw ModelPackageImportException(ModelPackageFailureCode.NO_VALID_PACKAGE),
            factory: SherpaVitsSessionFactory = JniSherpaVitsSession,
        ): SherpaVitsSession {
            var result: SherpaVitsSession? = null
            store.withVerifiedArtifactFiles(packageInfo, setOf("onnx", "tokens"), setOf("lexicon")) { artifacts ->
                result = SherpaVitsSession(
                    factory.open(
                        requireNotNull(artifacts["onnx"]).absolutePath,
                        requireNotNull(artifacts["tokens"]).absolutePath,
                        artifacts["lexicon"]?.absolutePath,
                    ),
                )
            }
            return requireNotNull(result)
        }
    }
}

private object JniSherpaVitsSession : SherpaVitsSessionFactory {
    override fun open(modelPath: String, tokensPath: String, lexiconPath: String?): SherpaVitsNativeSession {
        return try {
            System.loadLibrary("cita_sherpa_vits")
            JniSession(SherpaVitsJni.open(modelPath, tokensPath, lexiconPath ?: ""))
        } catch (failure: Throwable) {
            throw IllegalStateException("Sherpa VITS native runtime is unavailable", failure)
        }
    }

    private class JniSession(private val handle: Long) : SherpaVitsNativeSession {
        private var closed = false
        override fun generate(tokenIds: IntArray, speakerId: Int, speed: Float): VitsNativeAudio {
            check(!closed) { "native Sherpa VITS session is closed" }
            return VitsNativeAudio(
                SherpaVitsJni.generate(handle, tokenIds, speakerId, speed),
                SherpaVitsJni.sampleRate(handle),
            )
        }
        override fun close() {
            if (!closed) {
                closed = true
                SherpaVitsJni.close(handle)
            }
        }
    }
}

private object SherpaVitsJni {
    init {
        // Loading is intentionally explicit: an unbuilt optional runtime is unavailable.
    }

    external fun open(modelPath: String, tokensPath: String, lexiconPath: String): Long
    external fun generate(handle: Long, tokenIds: IntArray, speakerId: Int, speed: Float): FloatArray
    external fun sampleRate(handle: Long): Int
    external fun close(handle: Long)
}
