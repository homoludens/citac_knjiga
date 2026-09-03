package com.homoludens.citacknjiga.tts.onnx

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Maintains a valid mono PCM16 staging WAV while inference produces chunks. */
internal class ProgressiveWavWriter(file: File, private val sampleRateHz: Int) : AutoCloseable {
    private val output: RandomAccessFile
    private var sampleCount = 0L

    init {
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        output = RandomAccessFile(file, "rw")
        output.setLength(0)
        writeHeader()
    }

    fun append(samples: FloatArray) {
        require(samples.all { it.isFinite() && kotlin.math.abs(it) < 1f })
        output.seek(output.length())
        samples.forEach { sample ->
            val scale = if (sample < 0f) 32_768f else Short.MAX_VALUE.toFloat()
            writeShort((sample * scale).roundToInt())
        }
        sampleCount += samples.size
        val dataBytes = Math.multiplyExact(sampleCount, 2L)
        require(dataBytes <= Int.MAX_VALUE - 36L) { "PCM output is too large for a WAV artifact" }
        output.seek(4)
        writeInt((36L + dataBytes).toInt())
        output.seek(40)
        writeInt(dataBytes.toInt())
        output.fd.sync()
    }

    override fun close() {
        output.close()
    }

    private fun writeHeader() {
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeInt(36)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(sampleRateHz)
        writeInt(sampleRateHz * 2)
        writeShort(2)
        writeShort(16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeInt(0)
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
    }

    private fun writeInt(value: Int) {
        writeShort(value)
        writeShort(value ushr 16)
    }
}
