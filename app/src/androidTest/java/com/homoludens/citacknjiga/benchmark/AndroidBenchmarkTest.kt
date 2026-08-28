package com.homoludens.citacknjiga.benchmark

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.tts.onnx.AndroidBenchmarkReportStore
import com.homoludens.citacknjiga.tts.onnx.AndroidBenchmarkRunner
import com.homoludens.citacknjiga.tts.onnx.OnnxExecutionProvider
import com.homoludens.citacknjiga.tts.onnx.OnnxRuntimeConfiguration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device measurement. It must be invoked by the adb wrapper. */
public class AndroidBenchmarkTest {
    @Test
    public fun runsFifteenMinuteTypedInputBenchmark() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e benchmark true to run the sustained benchmark", arguments.getString("benchmark") == "true")
        assumeTrue("The measurement device must be native arm64", Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        assumeTrue("The measurement device must be the Poco F3", Build.MODEL == "M2012K11AG" && Build.DEVICE == "alioth")

        val targetSeconds = arguments.getString("workload_seconds")?.toIntOrNull() ?: 15 * 60
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidBenchmarkReportStore(File(context.filesDir, "benchmark-reports"))
        val report = AndroidBenchmarkRunner().runAndPersist(
            context,
            targetSeconds,
            store,
            runtimeConfiguration(arguments),
            arguments.getString("benchmark_task") ?: "build-serbian-audiobook-mvp 5.1",
        )

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

    private fun runtimeConfiguration(arguments: android.os.Bundle): OnnxRuntimeConfiguration {
        val threads = arguments.getString("runtime_threads")?.toIntOrNull() ?: 1
        return when (arguments.getString("runtime_provider") ?: "cpu") {
            "cpu" -> OnnxRuntimeConfiguration(
                executionProvider = OnnxExecutionProvider.CPU,
                intraOpThreads = threads,
                interOpThreads = 1,
                providerThreads = threads,
            )
            "xnnpack" -> OnnxRuntimeConfiguration(
                executionProvider = OnnxExecutionProvider.XNNPACK,
                intraOpThreads = 1,
                interOpThreads = 1,
                providerThreads = threads,
            )
            else -> error("Unsupported runtime provider")
        }
    }

}
