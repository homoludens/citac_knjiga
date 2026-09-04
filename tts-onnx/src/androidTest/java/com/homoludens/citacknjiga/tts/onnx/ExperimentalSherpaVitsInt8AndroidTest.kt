package com.homoludens.citacknjiga.tts.onnx

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs an unqualified model only inside instrumentation, never through package import. */
public class ExperimentalSherpaVitsInt8AndroidTest {
    @Test
    public fun staticInt8GeneratesOfflineAudio(): Unit {
        val arguments = InstrumentationRegistry.getArguments()
        val fp32Path = arguments.getString("fp32Model")
        val staticInt8Path = arguments.getString("staticInt8Model")
        assumeTrue("fp32Model and staticInt8Model arguments are required", fp32Path != null && staticInt8Path != null)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fp32 = stage(requireNotNull(fp32Path), File(context.cacheDir, "vits-fp32.onnx"))
        val staticInt8 = stage(requireNotNull(staticInt8Path), File(context.cacheDir, "vits-static-int8.onnx"))
        val fp32Trial = generate(fp32)
        val staticInt8Trial = generate(staticInt8)

        Log.i(TAG, "fp32 median=${fp32Trial.medianMillis}ms timings=${fp32Trial.samples} audioSamples=${fp32Trial.sampleCount}")
        Log.i(TAG, "static-int8 median=${staticInt8Trial.medianMillis}ms timings=${staticInt8Trial.samples} audioSamples=${staticInt8Trial.sampleCount}")
        assertTrue(fp32Trial.sampleCount > 0)
        assertTrue(staticInt8Trial.sampleCount > 0)
    }

    private fun stage(source: String, destination: File): File {
        require(source.matches(Regex("/data/local/tmp/[A-Za-z0-9._-]+")))
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $source"),
        ).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        require(destination.isFile && destination.length() > 0L)
        return destination
    }

    private fun generate(model: File): Trial {
        val factoryClass = Class.forName("com.homoludens.citacknjiga.tts.onnx.JniSherpaVitsSession")
        val factory = factoryClass.getField("INSTANCE").get(null) as SherpaVitsSessionFactory
        SherpaVitsSession.fromNative(factory.open(model.absolutePath, "", null)).use { session ->
            session.generate(TOKEN_IDS, speakerId = 0, speed = 1f)
            var audioSampleCount = 0L
            val samples = (0 until 3).map {
                val started = SystemClock.elapsedRealtimeNanos()
                val audio = session.generate(TOKEN_IDS, speakerId = 0, speed = 1f)
                assertEquals(22_050, audio.sampleRateHz)
                assertEquals(1, audio.channels)
                assertTrue(audio.pcm.isNotEmpty())
                assertTrue(audio.pcm.all { it.isFinite() })
                assertTrue(audio.pcm.any { abs(it) > 0.0001f })
                audioSampleCount = audio.pcm.size.toLong()
                (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLISECOND
            }.sorted()
            return Trial(samples, samples[samples.size / 2], audioSampleCount)
        }
    }

    private data class Trial(
        val samples: List<Long>,
        val medianMillis: Long,
        val sampleCount: Long,
    )

    private companion object {
        private const val TAG: String = "VitsInt8Trial"
        private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
        private val TOKEN_IDS: IntArray = intArrayOf(
            139, 111, 139, 91, 139, 78, 139, 77, 139, 94, 139,
            14, 139, 80, 139, 77, 139, 90, 139, 8, 139,
        )
    }
}
