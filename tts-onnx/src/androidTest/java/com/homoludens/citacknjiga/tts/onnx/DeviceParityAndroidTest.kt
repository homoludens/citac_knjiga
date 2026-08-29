package com.homoludens.citacknjiga.tts.onnx

import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test
    public fun runsOptInProductionParityAgainstExternalPackage() {
        assumeTrue(
            "Pass -e production_parity true to run production qualification",
            InstrumentationRegistry.getArguments().getString("production_parity") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().context
        require(Process.is64Bit() && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            "Production parity requires a native 64-bit arm64-v8a process; supported ABIs=" +
                Build.SUPPORTED_ABIS.contentToString()
        }

        val storage = AppPrivateStorage(context.filesDir)
        val bundleDirectory = storage.parityInputDirectory
        val vectors = DesktopOnnxParityVectorLoader.load(bundleDirectory)
        assertEquals("The production bundle must contain all 26 vectors", 26, vectors.size)

        val reportStore = DeviceParityReportStore(storage.parityReportsDirectory)
        val report = AndroidDeviceParityRunner().runInstalledAndPersist(
            store = ModelPackageStore(context.filesDir),
            vectors = vectors,
            context = DeviceParityContext(
                device = DeviceParityDeviceIdentity(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    device = Build.DEVICE,
                    apiLevel = Build.VERSION.SDK_INT,
                    abi = Build.SUPPORTED_ABIS.first(),
                ),
                build = buildIdentity(context),
                runtime = DeviceParityRuntimeIdentity(),
                model = DeviceParityModelIdentity(null, null, null, null, null),
                evidence = "production external 26-vector parity on native arm64-v8a",
            ),
            reportStore = reportStore,
        )

        Log.i(
            "DeviceParity",
            "production report=${reportStore.reportFile} status=${report.status} " +
                "vectors=${report.vectorsEvaluated}/${report.vectorsExpected} " +
                "package=${report.model.packageId}@${report.model.packageVersion} " +
                "device=${report.device.manufacturer}/${report.device.model}",
        )
        assertEquals("Production parity must evaluate all 26 vectors", 26, report.vectorsExpected)
        assertEquals("Production parity must run all 26 vectors", 26, report.vectorsEvaluated)
        assertEquals("passed", report.status)
        assertTrue(
            "Production parity failed: " + report.vectors.flatMap { it.failures },
            report.ok,
        )
        assertEquals("arm64-v8a", report.device.abi)
        assertNotNull("A verified package ID must be reported", report.model.packageId)
        assertNotNull("A verified package version must be reported", report.model.packageVersion)
        assertNotNull("A verified package identity must be reported", report.model.packageSha256)
        assertNotNull("A verified model identity must be reported", report.model.modelSha256)
        assertNotNull("A verified voice identity must be reported", report.model.voiceSha256)
        assertTrue(reportStore.reportFile.isFile)
    }

    private fun buildIdentity(context: android.content.Context): DeviceParityBuildIdentity {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val buildType = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "debug"
        } else {
            "release"
        }
        return DeviceParityBuildIdentity(
            applicationId = context.packageName,
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = packageInfo.longVersionCode,
            buildType = buildType,
        )
    }
}
