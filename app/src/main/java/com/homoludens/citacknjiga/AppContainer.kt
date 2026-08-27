package com.homoludens.citacknjiga

import com.homoludens.citacknjiga.core.diagnostics.LocalDiagnostics
import com.homoludens.citacknjiga.proof.AndroidTypedTextProofEngine
import com.homoludens.citacknjiga.proof.TypedTextProofEngine
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor
import android.content.res.AssetManager
import java.io.File

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
) {
    public companion object {
        public fun production(filesDir: File, assets: AssetManager): AppContainer {
            val modelStore = ModelPackageStore(filesDir)
            return AppContainer(
                diagnostics = LocalDiagnostics(),
                variant = AppVariant.fromBuildConfig(),
                typedTextProofEngine = AndroidTypedTextProofEngine(
                    modelStore = modelStore,
                    preprocessorFactory = { SerbianPreprocessor.fromAssets(assets, filesDir) },
                    artifactDirectory = File(filesDir, "typed-proof"),
                ),
            )
        }
    }
}
