package com.homoludens.citacknjiga.tts.onnx

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.sin
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class DeviceParityTest {
    @Test
    public fun evaluatesEveryFrozenMetricAndReportsPass() {
        val pcm = fixturePcm()
        val result = DeviceParityEvaluator.compare(
            DesktopOnnxParityVector("fixture-desktop-onnx", pcm),
            OnnxTtsOutput(pcm.copyOf(), longArrayOf(1, 1)),
        )

        assertTrue(result.ok)
        assertEquals(setOf("sample_count", "waveform_error", "spectral_similarity", "silence", "clipping", "invalid_values"), result.metrics.keys)
        assertEquals(1.0, result.metrics.getValue("spectral_similarity").measurements.getValue("stft_magnitude_cosine").value!!, 0.0)
        assertEquals(DeviceParityThresholds.VERSION, DeviceParityThresholdReport().version)
    }

    @Test
    public fun keepsVectorFailureAndDeclaredMetricWhenPcmDiffers() {
        val reference = fixturePcm()
        val candidate = reference.copyOf().also { it[0] += 0.2f }
        val result = DeviceParityEvaluator.compare(
            DesktopOnnxParityVector("fixture-desktop-onnx", reference),
            OnnxTtsOutput(candidate, longArrayOf(1, 1)),
        )

        assertFalse(result.ok)
        assertFalse(result.metrics.getValue("waveform_error").pass)
        assertTrue(result.failures.any { it.contains("fixture-desktop-onnx: waveform_error") })
    }

    @Test
    public fun reportStorePublishesJsonAtomicallyWithoutDocumentText() {
        val directory = createTempDirectory().toFile()
        val reportStore = DeviceParityReportStore(directory)
        val report = AndroidDeviceParityRunner { 123L }.run(
            vectors = listOf(DesktopOnnxParityVector("fixture-desktop-onnx", fixturePcm())),
            context = context(),
        ) { vector -> OnnxTtsOutput(vector.pcm.copyOf(), longArrayOf(1, 1)) }

        val path = reportStore.writeAtomic(report)
        val json = path.readText()
        assertTrue(path.isFile)
        assertTrue(json.contains("\"thresholds\""))
        assertTrue(json.contains("\"vectors\""))
        assertFalse(json.contains("\"text\""))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    public fun blockedReportIsNonPassingAndPersisted() {
        val store = DeviceParityReportStore(createTempDirectory().toFile())
        val report = AndroidDeviceParityRunner { 123L }.blocked(
            context(),
            "No verified model package is installed",
            store,
        )

        assertEquals("blocked", report.status)
        assertFalse(report.ok)
        val json = JsonParser.parseString(store.reportFile.readText()).asJsonObject
        assertEquals("blocked", json.get("status").asString)
        assertEquals(false, json.get("ok").asBoolean)
        assertEquals(DeviceParityThresholds.VERSION, json.getAsJsonObject("thresholds").get("version").asString)
    }

    @Test
    public fun installedRunnerFailsClosedWhenNoPackageIsAvailable() {
        val root = createTempDirectory().toFile()
        val reportStore = DeviceParityReportStore(File(root, "diagnostics"))
        val report = AndroidDeviceParityRunner { 123L }.runInstalledAndPersist(
            store = ModelPackageStore(root),
            vectors = listOf(DesktopOnnxParityVector("fixture-desktop-onnx", fixturePcm())),
            context = context(),
            reportStore = reportStore,
        )

        assertEquals("blocked", report.status)
        assertFalse(report.ok)
        assertTrue(report.blocker?.contains("No verified model package") == true)
        assertTrue(reportStore.reportFile.isFile)
    }

    private fun context(): DeviceParityContext = DeviceParityContext(
        device = DeviceParityDeviceIdentity("fixture", "fixture", "fixture", 35, "x86_64"),
        build = DeviceParityBuildIdentity("com.example.fixture", "1.0", 1, "debug"),
        model = DeviceParityModelIdentity("fixture-model", "1.0.0", "package-sha", "model-sha", "voice-sha"),
        evidence = "fixture",
    )

    private fun fixturePcm(): FloatArray = FloatArray(4096) { index ->
        (0.2 * sin(index / 17.0)).toFloat()
    }
}
