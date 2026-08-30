package com.homoludens.citacknjiga.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.tts.onnx.AAC_BENCHMARK_CHANNELS
import com.homoludens.citacknjiga.tts.onnx.AAC_BENCHMARK_SAMPLE_RATE_HZ
import com.homoludens.citacknjiga.tts.onnx.AacBenchmarkReportValidator
import com.homoludens.citacknjiga.tts.onnx.AacBenchmarkReportStore
import com.homoludens.citacknjiga.tts.onnx.AndroidAacBenchmarkRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in platform codec measurement; no model package or network is needed. */
public class AacBenchmarkAndroidTest {
    @Test
    public fun benchmarksPlatformAacWhenExplicitlyRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e aac_benchmark true to run the AAC benchmark", arguments.getString("aac_benchmark") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = AndroidAacBenchmarkRunner().runAndPersist(
            context = context,
            reportStore = AacBenchmarkReportStore(AppPrivateStorage(context.filesDir).benchmarkReportsDirectory),
            bitratesBps = arguments.getString("aac_bitrates_bps")
                ?.split(',')
                ?.filter(String::isNotBlank)
                ?.map(String::toInt)
                ?: listOf(64_000, 80_000, 96_000),
            task = arguments.getString("benchmark_task") ?: "build-serbian-audiobook-mvp 10.1",
        )

        AacBenchmarkReportValidator.validate(report)
        assertEquals(AAC_BENCHMARK_SAMPLE_RATE_HZ, report.input.sampleRateHz)
        assertEquals(AAC_BENCHMARK_CHANNELS, report.input.channels)
        assertTrue(report.status == "completed" || report.status == "blocked" || report.status == "failed")
    }
}
