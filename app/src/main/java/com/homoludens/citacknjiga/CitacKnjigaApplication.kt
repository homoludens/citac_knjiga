package com.homoludens.citacknjiga

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

public class CitacKnjigaApplication : Application(), Configuration.Provider {
    private val configuredContainer: AppContainer by lazy { AppContainer.production(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    public lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        WorkManager.initialize(this, workManagerConfiguration)
        container = configuredContainer
        container.projectDeletionCoordinator?.let { coordinator ->
            applicationScope.launch { coordinator.reconcileDeletingProjects() }
        }
        container.diagnostics.info(
            component = "app",
            message = "application_started",
            attributes = mapOf("variant" to container.variant.distribution.id),
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
        .setWorkerFactory(requireNotNull(configuredContainer.workerFactory))
        .build()
}
