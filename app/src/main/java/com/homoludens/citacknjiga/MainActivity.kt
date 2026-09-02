package com.homoludens.citacknjiga

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CitacKnjigaApplication).container
        if (container.variant.verboseDiagnostics) {
            container.diagnostics.debug(
                component = "navigation",
                message = "opening_start_route",
                attributes = mapOf("route" to AppRoute.Start.path),
            )
        }
        setContent {
            CitacKnjigaApp(
                variant = container.variant,
                audiobookDao = container.audiobookDao,
                proofEngine = container.typedTextProofEngine,
                epubImportPreviewService = container.epubImportPreviewService,
                pdfImportPreviewService = container.pdfImportPreviewService,
                pdfAcceptanceService = container.pdfAcceptanceService,
                epubChapterProofService = container.epubChapterProofService,
                playbackController = container.playbackController,
                audiobookExportService = container.audiobookExportService,
                diagnostics = container.diagnostics,
                privateStorage = container.privateStorage,
                modelPackageStore = container.modelPackageStore,
                ttsEnginePreference = container.ttsEnginePreference,
                projectDeletionCoordinator = container.projectDeletionCoordinator,
            )
        }
    }
}
