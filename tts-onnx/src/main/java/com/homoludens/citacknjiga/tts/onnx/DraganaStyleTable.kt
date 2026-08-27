package com.homoludens.citacknjiga.tts.onnx

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Reads the single FloatStorage used by the checked-in Dragana Torch archive. */
public object DraganaStyleTable {
    public const val ROWS: Int = 510
    public const val VALUES_PER_ROW: Int = 256
    public const val VALUE_COUNT: Int = ROWS * VALUES_PER_ROW

    public fun fromTorchArchive(bytes: ByteArray): FloatArray {
        var storage: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && (entry.name == "data/0" || entry.name.endsWith("/data/0"))) {
                    check(storage == null) { "Voice archive contains multiple FloatStorage payloads" }
                    storage = zip.readBytes()
                }
            }
        }
        val payload = checkNotNull(storage) { "Voice archive has no FloatStorage payload" }
        require(payload.size == VALUE_COUNT * Float.SIZE_BYTES) {
            "Voice style payload has ${payload.size} bytes; expected ${VALUE_COUNT * Float.SIZE_BYTES}"
        }
        val values = FloatArray(VALUE_COUNT)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
        require(values.all(Float::isFinite)) { "Voice style payload contains non-finite values" }
        return values
    }
}
