package com.homoludens.citacknjiga.tts.onnx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Test

public class DraganaStyleTableAndroidTest {
    @Test
    public fun readsTorchStoredDataDescriptorArchive() {
        val payload = ByteBuffer.allocate(DraganaStyleTable.VALUE_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(DraganaStyleTable.VALUE_COUNT) { index -> payload.putFloat(index.toFloat()) }

        val table = DraganaStyleTable.fromTorchArchive(torchArchive(payload.array()))

        assertEquals(DraganaStyleTable.VALUE_COUNT, table.size)
        assertEquals(0f, table.first())
        assertEquals((DraganaStyleTable.VALUE_COUNT - 1).toFloat(), table.last())
    }

    private fun torchArchive(payload: ByteArray): ByteArray {
        val entries = listOf(
            "voice/data.pkl" to ByteArray(165),
            "voice/.format_version" to "1".toByteArray(),
            "voice/.storage_alignment" to "64".toByteArray(),
            "voice/byteorder" to "little".toByteArray(),
            "voice/data/0" to payload,
            "voice/version" to "3\n".toByteArray(),
            "voice/.data/serialization_id" to ByteArray(40),
        )
        val output = ByteArrayOutputStream()
        val centralEntries = entries.map { (name, bytes) ->
            val nameBytes = name.toByteArray()
            val localOffset = output.size()
            val extraPayloadLength = 16 + ((4 - ((localOffset + 30 + nameBytes.size + 4) % 4)) % 4)
            val extra = byteArrayOf(
                0x46, 0x42, extraPayloadLength.toByte(), 0,
            ) + ByteArray(extraPayloadLength) { 'Z'.code.toByte() }
            val crc = CRC32().also { it.update(bytes) }.value

            writeInt(output, 0x04034b50L)
            writeShort(output, 20)
            writeShort(output, 0x808)
            writeShort(output, 0)
            writeInt(output, 0)
            writeInt(output, 0)
            writeInt(output, 0)
            writeInt(output, 0)
            writeShort(output, nameBytes.size)
            writeShort(output, extra.size)
            output.write(nameBytes)
            output.write(extra)
            output.write(bytes)
            writeInt(output, 0x08074b50L)
            writeInt(output, crc)
            writeInt(output, bytes.size.toLong())
            writeInt(output, bytes.size.toLong())
            Triple(nameBytes, bytes, Triple(crc, localOffset, extra))
        }
        val centralOffset = output.size()
        centralEntries.forEach { (nameBytes, bytes, metadata) ->
            val (crc, localOffset, _) = metadata
            writeInt(output, 0x02014b50L)
            writeShort(output, 20)
            writeShort(output, 20)
            writeShort(output, 0x808)
            writeShort(output, 0)
            writeInt(output, 0)
            writeInt(output, crc)
            writeInt(output, bytes.size.toLong())
            writeInt(output, bytes.size.toLong())
            writeShort(output, nameBytes.size)
            writeShort(output, 0)
            writeShort(output, 0)
            writeShort(output, 0)
            writeShort(output, 0)
            writeInt(output, 0)
            writeInt(output, localOffset.toLong())
            output.write(nameBytes)
        }
        val centralSize = output.size() - centralOffset
        writeInt(output, 0x06054b50L)
        writeShort(output, 0)
        writeShort(output, 0)
        writeShort(output, entries.size)
        writeShort(output, entries.size)
        writeInt(output, centralSize.toLong())
        writeInt(output, centralOffset.toLong())
        writeShort(output, 0)
        return output.toByteArray()
    }

    private fun writeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Long) {
        output.write((value and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 24) and 0xff).toInt())
    }
}
