package com.homoludens.citacknjiga

import com.homoludens.citacknjiga.core.diagnostics.LocalDiagnostics

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
) {
    public companion object {
        public fun production(): AppContainer = AppContainer(
            diagnostics = LocalDiagnostics(),
            variant = AppVariant.fromBuildConfig(),
        )
    }
}
