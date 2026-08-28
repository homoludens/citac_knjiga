package com.homoludens.citacknjiga.tts.onnx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

public class DraganaStyleTableTest {
    @Test
    public fun readsTheDeclaredTorchStyleTableShapeAndValues() {
        val payload = ByteBuffer.allocate(DraganaStyleTable.VALUE_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(DraganaStyleTable.VALUE_COUNT) { index -> payload.putFloat(index.toFloat()) }

        val table = DraganaStyleTable.fromTorchArchive(torchArchive(payload.array()))

        assertEquals(DraganaStyleTable.VALUE_COUNT, table.size)
        assertEquals(0f, table.first())
        assertEquals((DraganaStyleTable.VALUE_COUNT - 1).toFloat(), table.last())
    }

    @Test
    public fun cpuBaselineUsesOneSequentialThreadPool() {
        val configuration = OnnxRuntimeContract.CPU_BASELINE

        assertEquals(1, configuration.intraOpThreads)
        assertEquals(1, configuration.interOpThreads)
        assertEquals("sequential", configuration.executionMode)
        assertEquals("1.29.0", OnnxRuntimeContract.VERSION)
    }

    @Test
    public fun runtimeVariantsKeepProviderAndThreadIdentityExplicit() {
        val cpu = OnnxRuntimeConfiguration(
            executionProvider = OnnxExecutionProvider.CPU,
            intraOpThreads = 4,
            interOpThreads = 1,
            providerThreads = 4,
        )
        val xnnpack = OnnxRuntimeConfiguration(
            executionProvider = OnnxExecutionProvider.XNNPACK,
            intraOpThreads = 1,
            interOpThreads = 1,
            providerThreads = 2,
        )

        assertEquals("cpu-intra-4-inter-1", cpu.reportId)
        assertEquals("xnnpack-threads-2", xnnpack.reportId)
        assertEquals(4, cpu.providerThreads)
        assertEquals(2, xnnpack.providerThreads)
    }

    private fun torchArchive(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("voice/data/0"))
            zip.write(payload)
            zip.closeEntry()
        }
    }.toByteArray()
}
