package com.homoludens.citacknjiga.tts.onnx.preprocessing

import android.content.res.AssetManager
import java.io.File

public class NativeSerbianPhonemizer(
    private val dataRoot: File,
) : SerbianPhonemizer {
    override fun phonemize(text: String): String {
        if (!dataRoot.isDirectory) {
            throw unavailable("eSpeak-NG data is unavailable")
        }
        NativeEspeakBridge.loadError?.let { error ->
            throw unavailable("The native eSpeak-NG library is unavailable", error)
        }
        return try {
            NativeEspeakBridge.phonemizeNative(text, dataRoot.absolutePath)
                ?: throw SerbianPreprocessingException(
                    stage = PreprocessingStage.PHONEMES,
                    code = PreprocessingFailureCode.PHONEMIZER_FAILED,
                    message = "Native eSpeak-NG failed to phonemize Serbian text",
                )
        } catch (exception: SerbianPreprocessingException) {
            throw exception
        } catch (exception: Exception) {
            throw SerbianPreprocessingException(
                stage = PreprocessingStage.PHONEMES,
                code = PreprocessingFailureCode.PHONEMIZER_FAILED,
                message = "Native eSpeak-NG failed to phonemize Serbian text",
                cause = exception,
            )
        }
    }

    public companion object {
        public fun fromAssets(assetManager: AssetManager, filesDir: File): NativeSerbianPhonemizer =
            NativeSerbianPhonemizer(EspeakDataInstaller.install(assetManager, filesDir))
    }

    private fun unavailable(message: String, cause: Throwable? = null): SerbianPreprocessingException =
        SerbianPreprocessingException(
            stage = PreprocessingStage.PHONEMES,
            code = PreprocessingFailureCode.NATIVE_PHONEMIZER_UNAVAILABLE,
            message = message,
            cause = cause,
        )
}

public object NativeEspeakBridge {
    internal val loadError: Throwable? = try {
        System.loadLibrary("cita_espeak")
        null
    } catch (error: Throwable) {
        error
    }

    @JvmStatic
    public external fun phonemizeNative(text: String, dataRoot: String): String?
}
