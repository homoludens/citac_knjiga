package com.homoludens.citacknjiga.tts.onnx

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Loads the text-free desktop parity bundle produced by model-tools. */
public object DesktopOnnxParityVectorLoader {
    public fun load(directory: File): List<DesktopOnnxParityVector> {
        require(directory.isDirectory) { "Parity bundle directory is unavailable" }
        val manifest = readObject(File(directory, "manifest.json"))
        val inputs = readObject(File(directory, "inputs.json"))
        require(manifest.get("kind").asString == "desktop-onnx-parity-audio") {
            "Unsupported desktop parity audio manifest"
        }
        require(inputs.get("kind").asString == "desktop-onnx-parity-inputs") {
            "Unsupported desktop parity input manifest"
        }
        require(manifest.get("version").asInt == 1 && inputs.get("version").asInt == 1) {
            "Unsupported desktop parity bundle version"
        }
        require(inputs.get("audio_manifest").asString == "manifest.json") {
            "Parity input manifest must reference manifest.json"
        }
        rejectTextFields(manifest)
        rejectTextFields(inputs)
        require(
            manifest.getAsJsonObject("provenance").toString() ==
                inputs.getAsJsonObject("provenance").toString(),
        ) { "Parity audio and input provenance does not match" }
        require(
            manifest.getAsJsonObject("provenance").get("thresholds_version").asString ==
                DeviceParityThresholds.VERSION,
        ) { "Unsupported parity threshold declaration" }

        val audioById = manifest.getAsJsonArray("vectors").associateByUnique { it.get("id").asString }
        val inputById = inputs.getAsJsonArray("vectors").associateByUnique { it.get("id").asString }
        require(audioById.keys == inputById.keys) { "Parity audio and input vector IDs do not match" }
        return audioById.keys.map { id ->
            val audio = audioById.getValue(id)
            val input = inputById.getValue(id)
            val audioFile = safeChild(directory, audio.get("audio_file").asString)
            require(sha256(audioFile) == audio.get("sha256").asString) {
                "$id: desktop WAV checksum mismatch"
            }
            require(audioFile.length() == audio.get("byte_size").asLong) {
                "$id: desktop WAV size mismatch"
            }
            require(audio.get("sample_rate_hz").asInt == OnnxRuntimeContract.SAMPLE_RATE_HZ) {
                "$id: unsupported desktop sample rate"
            }
            require(audio.get("channels").asInt == OnnxRuntimeContract.CHANNELS) {
                "$id: unsupported desktop channel count"
            }
            require(audio.get("sample_format").asString == "float32-le") {
                "$id: unsupported desktop sample format"
            }
            val pcm = readFloatWav(audioFile)
            require(pcm.size == audio.get("sample_count").asInt) {
                "$id: desktop WAV sample count mismatch"
            }
            require(pcm.all { it.isFinite() }) { "$id: desktop WAV contains non-finite samples" }
            val chunks = input.getAsJsonArray("token_id_chunks").map { chunkElement ->
                val chunk = chunkElement.asJsonArray.map { token -> token.asInt }
                require(chunk.size in OnnxRuntimeContract.MIN_SEQUENCE_LENGTH..OnnxRuntimeContract.MAX_SEQUENCE_LENGTH) {
                    "$id: token chunk length is outside the ONNX contract"
                }
                require(chunk.first() == 0 && chunk.last() == 0) {
                    "$id: token chunk is missing boundary tokens"
                }
                require(chunk.all { it in 0 until OnnxRuntimeContract.VOCAB_SIZE }) {
                    "$id: token chunk contains an invalid vocabulary ID"
                }
                chunk
            }
            require(chunks.isNotEmpty()) { "$id: no token chunks supplied" }
            val speed = input.get("speed").asFloat
            require(speed.isFinite() && speed > 0f) { "$id: speed must be positive and finite" }
            DesktopOnnxParityVector(
                id = id,
                pcm = pcm,
                sampleRateHz = audio.get("sample_rate_hz").asInt,
                channels = audio.get("channels").asInt,
                tokenIds = chunks.first(),
                speed = speed,
                tokenIdChunks = chunks,
            )
        }
    }

    private fun readObject(file: File): JsonObject {
        require(file.isFile) { "Parity bundle file is unavailable: ${file.name}" }
        return file.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    private fun safeChild(directory: File, relativePath: String): File {
        require(relativePath.isNotEmpty() && !relativePath.startsWith("/") && '\\' !in relativePath) {
            "Unsafe parity audio path"
        }
        val root = directory.canonicalFile
        val child = File(root, relativePath).canonicalFile
        require(child.path.startsWith(root.path + File.separator)) { "Parity audio path escapes bundle" }
        return child
    }

    private fun readFloatWav(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "Invalid desktop WAV container"
        }
        require(unsignedInt(bytes, 4) == bytes.size - 8L) { "Desktop WAV RIFF size mismatch" }
        var format: Int? = null
        var channels: Int? = null
        var sampleRate: Int? = null
        var bits: Int? = null
        var dataOffset = -1
        var dataSize = -1
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkSize = unsignedInt(bytes, offset + 4)
            val start = offset + 8
            val end = start + chunkSize
            require(end <= bytes.size && end >= start) { "Invalid desktop WAV chunk" }
            when (ascii(bytes, offset, 4)) {
                "fmt " -> {
                    require(chunkSize >= 16) { "Desktop WAV format chunk is incomplete" }
                    format = unsignedShort(bytes, start)
                    channels = unsignedShort(bytes, start + 2)
                    sampleRate = unsignedInt(bytes, start + 4).toInt()
                    bits = unsignedShort(bytes, start + 14)
                }
                "data" -> {
                    dataOffset = start
                    dataSize = chunkSize.toInt()
                }
            }
            offset = end.toInt() + (chunkSize and 1L).toInt()
        }
        require(format == 3 && channels == 1 && sampleRate == 24_000 && bits == 32) {
            "Desktop WAV is not IEEE float32 mono 24 kHz"
        }
        require(dataOffset >= 0 && dataSize >= 0 && dataSize % 4 == 0) {
            "Desktop WAV data chunk is missing or invalid"
        }
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dataSize / 4) { buffer.float }
    }

    private fun rejectTextFields(element: JsonElement) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                require(!key.contains("text", ignoreCase = true)) { "Parity bundle must not contain source text" }
                rejectTextFields(value)
            }
            element.isJsonArray -> element.asJsonArray.forEach(::rejectTextFields)
        }
    }

    private fun JsonArray.associateByUnique(selector: (JsonObject) -> String): Map<String, JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        for (element in this) {
            val objectValue = element.asJsonObject
            val id = selector(objectValue)
            require(id.matches(ID_PATTERN) && result.put(id, objectValue) == null) {
                "Parity vector IDs must be unique and safe"
            }
        }
        return result
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        unsignedShort(bytes, offset).toLong() or (unsignedShort(bytes, offset + 2).toLong() shl 16)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private val ID_PATTERN = Regex("^[a-z][a-z0-9_.-]*$")
}
