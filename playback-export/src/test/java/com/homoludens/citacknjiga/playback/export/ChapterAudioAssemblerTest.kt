package com.homoludens.citacknjiga.playback.export

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class ChapterAudioAssemblerTest {
    private lateinit var directory: File

    @Before
    public fun setUp() {
        directory = Files.createTempDirectory("chapter-export").toFile()
    }

    @After
    public fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    public fun twoWavSegmentsBecomeOneOrderedValidatedChapter() {
        val firstSamples = ShortArray(240) { if (it == 0) 100 else 200 }
        val secondSamples = ShortArray(240) { if (it == 0) -300 else -400 }
        val first = wav("first", firstSamples)
        val second = wav("second", secondSamples)
        val output = File(directory, "chapter.wav")

        val assembled = WavChapterAudioAssembler().assemble(
            listOf(first, second),
            output,
            ExportAudioFormat.AUTO,
        )

        assertEquals(ExportAudioFormat.WAV, assembled.format)
        assertEquals(20L, assembled.durationMs)
        val bytes = output.readBytes()
        assertEquals(44 + 960, bytes.size)
        assertArrayEquals(
            (firstSamples + secondSamples).toPcmBytes(),
            bytes.copyOfRange(44, bytes.size),
        )
        assertTrue(com.homoludens.citacknjiga.tts.onnx.PcmWavValidator.validate(output).sampleCount == 480L)
    }

    @Test
    public fun m4aFormatIsRejectedByPcmAssemblerRatherThanContainerConcatenated() {
        val output = File(directory, "chapter.m4a")

        val failure = runCatching {
            WavChapterAudioAssembler().assemble(emptyList(), output, ExportAudioFormat.M4A)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(!output.exists())
    }

    private fun wav(name: String, samples: ShortArray): File = File(directory, "$name.wav").apply {
        val dataSize = samples.size * 2
        writeBytes(ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(24_000)
            putInt(48_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
            samples.forEach { putShort(it) }
        }.array())
    }

    private fun ShortArray.toPcmBytes(): ByteArray = ByteBuffer.allocate(size * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { forEach { putShort(it) } }
        .array()
}
