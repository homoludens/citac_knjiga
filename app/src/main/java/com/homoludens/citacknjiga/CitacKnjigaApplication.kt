package com.homoludens.citacknjiga

import android.app.Application

public class CitacKnjigaApplication : Application() {
    public lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.production()
        container.diagnostics.info(
            component = "app",
            message = "application_started",
            attributes = mapOf("variant" to container.variant.distribution.id),
        )
    }
}
