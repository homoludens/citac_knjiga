package com.homoludens.citacknjiga

import android.app.Application
import androidx.work.Configuration

public class CitacKnjigaApplication : Application(), Configuration.Provider {
    private val configuredContainer: AppContainer by lazy { AppContainer.production(this) }

    public lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = configuredContainer
        container.diagnostics.info(
            component = "app",
            message = "application_started",
            attributes = mapOf("variant" to container.variant.distribution.id),
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
        .setWorkerFactory(requireNotNull(configuredContainer.generationWorkerFactory))
        .build()
}
