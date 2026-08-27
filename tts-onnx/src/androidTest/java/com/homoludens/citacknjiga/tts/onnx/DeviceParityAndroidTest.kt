package com.homoludens.citacknjiga.tts.onnx

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class DeviceParityAndroidTest {
    @Test
    public fun persistsFixtureParityReportOnAndroid() {
        val pcm = FloatArray(4096) { index -> (0.2 * sin(index / 17.0)).toFloat() }
        val reportStore = DeviceParityReportStore(
            File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "parity-test"),
        )
        val report = AndroidDeviceParityRunner { 123L }.runAndPersist(
            vectors = listOf(DesktopOnnxParityVector("fixture-desktop-onnx", pcm)),
            context = DeviceParityContext(
                device = DeviceParityDeviceIdentity("fixture", "fixture", "fixture", 35, "x86_64"),
                build = DeviceParityBuildIdentity("com.example.fixture", "1.0", 1, "debug"),
                model = DeviceParityModelIdentity("fixture-model", "1.0.0", "package-sha", "model-sha", "voice-sha"),
                evidence = "fixture",
            ),
            reportStore = reportStore,
        ) { vector -> OnnxTtsOutput(vector.pcm.copyOf(), longArrayOf(1, 1)) }

        assertEquals("passed", report.status)
        assertTrue(report.ok)
        assertTrue(reportStore.reportFile.isFile)
        assertFalse(reportStore.reportFile.readText().contains("\"text\""))
    }
}
