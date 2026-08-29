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
                proofEngine = container.typedTextProofEngine,
                epubImportPreviewService = container.epubImportPreviewService,
                epubChapterProofService = container.epubChapterProofService,
            )
        }
    }
}
