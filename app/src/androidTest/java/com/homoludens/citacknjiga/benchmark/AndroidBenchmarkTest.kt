package com.homoludens.citacknjiga.benchmark

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.tts.onnx.AndroidBenchmarkReportStore
import com.homoludens.citacknjiga.tts.onnx.AndroidBenchmarkRunner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in sustained qualification run. It must be invoked by the adb wrapper. */
public class AndroidBenchmarkTest {
    @Test
    public fun runsFifteenMinuteTypedInputBenchmark() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e benchmark true to run the sustained benchmark", arguments.getString("benchmark") == "true")
        assumeTrue("The qualification device must be native arm64", Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        assumeTrue("The qualification device must be the Poco F3", Build.MODEL == "M2012K11AG" && Build.DEVICE == "alioth")

        val targetSeconds = arguments.getString("workload_seconds")?.toIntOrNull() ?: 15 * 60
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidBenchmarkReportStore(File(context.filesDir, "benchmark-reports"))
        val report = AndroidBenchmarkRunner().runAndPersist(context, targetSeconds, store)

        assertEquals("completed", report.status)
        assertTrue(report.completed)
        assertEquals(targetSeconds, report.workload.targetAudioSeconds)
        assertTrue(report.workload.audioSecondsGenerated >= targetSeconds)
        assertTrue(report.workload.inferenceCalls > 0)
        assertNotNull(report.measurements.modelLoadTimeMs)
        assertNotNull(report.measurements.realTimeFactor)
        assertNotNull(report.measurements.peakProcessMemoryBytes)
        assertNotNull(report.measurements.cpuUtilizationPercent)
        assertTrue(store.reportFile.isFile)
        assertFalse(store.reportFile.readText().contains("\"text\""))
    }
}
