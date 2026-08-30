package com.homoludens.citacknjiga.tts.onnx

import com.google.gson.JsonParser
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class AacBenchmarkTest {
    @Test
    public fun fixtureIsDeterministicAndCanonical24KhzMonoWav() {
        val first = AacBenchmarkFixture.create()
        val second = AacBenchmarkFixture.create()

        assertEquals(AAC_BENCHMARK_SAMPLE_RATE_HZ, first.sampleRateHz)
        assertEquals(1, first.channels)
        assertEquals(first.sampleCount, second.sampleCount)
        assertArrayEquals(first.pcm16, second.pcm16)
        assertEquals(
            AacBenchmarkFixture.sha256(AacBenchmarkFixture.pcm16Bytes(first)),
            AacBenchmarkFixture.sha256(AacBenchmarkFixture.pcm16Bytes(second)),
        )
        val wav = AacBenchmarkFixture.wavBytes(first)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(44 + first.sampleCount * 2, wav.size)
        assertEquals(first.segments.size, first.consonantWindows.size)
        assertTrue(first.consonantWindows.all { it.sampleCount > 0 })
    }

    @Test
    public fun wavReferenceAndIdenticalDecodedPcmProduceWindowMeasurements() {
        val input = AacBenchmarkFixture.create()
        val aligned = findPcmAlignment(input.pcm16, input.pcm16)

        assertEquals(0, aligned)
        val measurements = qualityMeasurement(input.pcm16, input.pcm16, input.consonantWindows, 0)
        assertEquals(input.consonantWindows.size, measurements.size)
        assertTrue(measurements.all { it.rmsRatio == 1.0 })
        assertTrue(measurements.all { it.referenceZeroCrossingRate == it.decodedZeroCrossingRate })
    }

    @Test
    public fun reportStoreValidatesAndPublishesABlockedDeviceReport() {
        val input = AacBenchmarkFixture.create()
        val report = report(
            input,
            listOf(
                AacBenchmarkBitrateReport(
                    requestedBitrateBps = 64_000,
                    available = false,
                    codecName = null,
                    status = "unavailable",
                    reason = "fixture has no Android codec",
                    fullOutputSizeBytes = null,
                    fullEncodedDurationUs = null,
                    fullEncodeElapsedMs = null,
                    boundary = null,
                    quality = null,
                ),
            ),
        )
        val store = AacBenchmarkReportStore(createTempDirectory().toFile())

        val file = store.writeAtomic(report)
        val json = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals("blocked", json.get("status").asString)
        assertEquals("serbian-consonants-synthetic-v1", json.getAsJsonObject("input").get("id").asString)
        assertTrue(file.isFile)
        assertFalse(file.parentFile!!.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    public fun reportValidatorRejectsAvailableResultWithoutMeasurements() {
        val input = AacBenchmarkFixture.create()
        val invalid = report(
            input,
            listOf(
                AacBenchmarkBitrateReport(
                    requestedBitrateBps = 64_000,
                    available = true,
                    codecName = "fixture.encoder",
                    status = "completed",
                    reason = null,
                    fullOutputSizeBytes = null,
                    fullEncodedDurationUs = null,
                    fullEncodeElapsedMs = null,
                    boundary = null,
                    quality = null,
                ),
            ),
        )

        val failure = runCatching { AacBenchmarkReportValidator.validate(invalid) }.exceptionOrNull()
        assertNotNull(failure)
    }

    private fun report(input: AacBenchmarkInput, bitrates: List<AacBenchmarkBitrateReport>) = AacBenchmarkReport(
        status = if (bitrates.any { it.available }) "completed" else "blocked",
        completed = bitrates.any { it.available },
        device = DeviceParityDeviceIdentity("fixture", "fixture", "fixture", 35, "x86_64"),
        build = DeviceParityBuildIdentity("fixture", "1.0", 1, "debug"),
        input = AacBenchmarkInputReport(
            id = input.id,
            sampleRateHz = input.sampleRateHz,
            channels = input.channels,
            sampleCount = input.sampleCount,
            durationUs = input.durationUs,
            pcmSha256 = AacBenchmarkFixture.sha256(AacBenchmarkFixture.pcm16Bytes(input)),
            wavSha256 = AacBenchmarkFixture.sha256(AacBenchmarkFixture.wavBytes(input)),
            segments = input.segments.mapIndexed { index, segment ->
                AacBenchmarkSegmentReport(segment.id, segment.label, segment.pcm16.size, index * segment.pcm16.size)
            },
            consonantWindows = input.consonantWindows,
        ),
        bitrates = bitrates,
        qualityMethod = "aac-wav-consonant-v1",
        limitations = emptyList(),
        failure = null,
        createdAtEpochMs = 1L,
    )
}
