package com.homoludens.citacknjiga.tts.onnx

import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
import com.homoludens.citacknjiga.core.generation.GenerationProgressStore
import com.homoludens.citacknjiga.core.generation.ApproximateWordCounter
import com.homoludens.citacknjiga.core.generation.SegmentGenerator
import com.homoludens.citacknjiga.core.database.AudioSegmentEntity
import com.homoludens.citacknjiga.core.database.NarrationBlockEntity
import android.content.SharedPreferences
import java.io.OutputStream

public enum class TtsEngine { KOKORO, VITS }

public class TtsEngineSelector(
    private val vitsStore: VitsModelPackageStore,
    private val apiLevel: Int,
    private val abi: String,
    private val runtimeAvailable: () -> Boolean = SherpaVitsSession::isRuntimeAvailable,
) {
    public fun available(): List<TtsEngine> = buildList {
        add(TtsEngine.KOKORO)
        if (apiLevel >= 33 && abi in SUPPORTED_VITS_ABIS && runtimeAvailable() && vitsStore.activePackage() != null) {
            add(TtsEngine.VITS)
        }
    }

    public fun select(preferred: TtsEngine): TtsEngine = preferred.takeIf { it in available() } ?: TtsEngine.KOKORO

    private companion object {
        val SUPPORTED_VITS_ABIS = setOf("arm64-v8a", "x86_64")
    }
}

/** Persists the user's engine choice while falling back closed to Kokoro. */
public class TtsEnginePreference(
    private val selector: TtsEngineSelector,
    private val preferences: SharedPreferences,
) {
    public var selected: TtsEngine = load()
        private set

    public fun available(): List<TtsEngine> = selector.available()

    public fun refresh(): List<TtsEngine> {
        val available = selector.available()
        val refreshed = selected.takeIf { it in available } ?: TtsEngine.KOKORO
        if (refreshed != selected) {
            selected = refreshed
            preferences.edit().putString(KEY, refreshed.name).apply()
        }
        return available
    }

    public fun select(preferred: TtsEngine) {
        val selected = selector.select(preferred)
        this.selected = selected
        preferences.edit().putString(KEY, selected.name).apply()
    }

    private fun load(): TtsEngine = selector.select(
        preferences.getString(KEY, null)?.let { value ->
            runCatching { TtsEngine.valueOf(value) }.getOrDefault(TtsEngine.KOKORO)
        } ?: TtsEngine.KOKORO,
    )

    private companion object {
        const val KEY = "tts_engine"
    }
}

/** Adapts one qualified VITS session to the existing bounded generation contract. */
public class VitsSegmentGenerator(
    private val frontend: VitsSerbianFrontend,
    private val session: SherpaVitsSession,
    private val packageInfo: InstalledModelPackage,
    private val inferenceSettingsHash: String,
    private val audioProcessingVersion: String = "pcm16-wav-v1",
    private val modelPackageId: String = packageInfo.packageId,
    private val progressStore: GenerationProgressStore? = null,
) : SegmentGenerator, AutoCloseable {
    override suspend fun generate(segment: AudioSegmentEntity, block: NarrationBlockEntity): GeneratedSegmentAudio {
        val prepared = frontend.process(block.sourceText)
        val expectedKey = VitsGenerationContract.generationKey(packageInfo, prepared.tokenIds)
        check(segment.generationKey == expectedKey) { "VITS generation key does not match the pending segment" }
        val runId = segment.generationRunId
        val textChunks = chunkText(block.sourceText)
        val totalWords = ApproximateWordCounter.count(block.sourceText)
        val finalChunks = mutableListOf<VitsNativeAudio>()
        val progressWav = if (runId != null && progressStore != null) {
            ProgressiveWavWriter(progressStore.wavFile(runId), VitsAudioOutputValidator.FINAL_RATE_HZ)
        } else {
            null
        }
        val final = try {
            try {
                if (runId != null && progressStore != null) {
                    progressStore.update(runId, segment.id, completedWords = 0, totalWords = totalWords)
                }
                textChunks.forEachIndexed { index, text ->
                    val chunk = frontend.process(text)
                    val nativeChunk = session.generate(chunk.tokenIds.toIntArray(), packageInfo.speakerId ?: 0, 1f)
                    finalChunks += VitsAudioOutputValidator.resampleOnce(nativeChunk)
                    if (runId != null && progressStore != null) {
                        progressWav?.append(finalChunks.last().pcm)
                        val completedWords = textChunks.take(index + 1).sumOf(ApproximateWordCounter::count)
                        progressStore.update(runId, segment.id, completedWords.coerceAtMost(totalWords), totalWords)
                    }
                }
            } finally {
                progressWav?.close()
            }
            combined(finalChunks)
        } catch (failure: Throwable) {
            runId?.let { progressStore?.clear(it) }
            throw failure
        }
        val durationMs = (final.pcm.size * 1000L / final.sampleRateHz).coerceAtLeast(1)
        val provenance = GenerationProvenance(
            generationKey = requireNotNull(segment.generationKey),
            modelPackageId = modelPackageId,
            modelPackageSha256 = packageInfo.identitySha256,
            voiceSha256 = packageInfo.voiceSha256,
            preprocessingVersion = packageInfo.frontendVersion ?: "serbian-vits-preprocessing-v1",
            pronunciationVersion = packageInfo.frontendVersion ?: "serbian-vits-preprocessing-v1",
            inferenceSettingsHash = inferenceSettingsHash,
            audioProcessingVersion = audioProcessingVersion,
            engine = "vits",
            modelRevision = requireNotNull(packageInfo.modelRevision),
            speakerId = requireNotNull(packageInfo.speakerId),
            nativeSampleRateHz = packageInfo.nativeSampleRateHz,
            finalSampleRateHz = final.sampleRateHz,
            frontendVersion = requireNotNull(packageInfo.frontendVersion),
            resamplerVersion = requireNotNull(packageInfo.resamplerVersion),
            runtimeId = packageInfo.runtimeId,
            runtimeVersion = packageInfo.runtimeVersion,
        )
        return GeneratedSegmentAudio(
            provenance = provenance,
            sampleRateHz = final.sampleRateHz,
            channels = final.channels,
            durationMs = durationMs,
            writer = { output ->
                val staged = runId?.let { progressStore?.wavFile(it) }?.takeIf { it.isFile }
                if (staged == null) writeWav(output, final.pcm, final.sampleRateHz)
                else staged.inputStream().use { it.copyTo(output) }
            },
            validator = { file ->
                val info = PcmWavValidator.validate(file)
                require(info.sampleCount == final.pcm.size.toLong()) { "VITS WAV sample count is invalid" }
            },
            artifactExtension = "wav",
            cleanup = { runId?.let { progressStore?.clear(it) } },
        )
    }

    private fun combined(chunks: List<VitsNativeAudio>): VitsNativeAudio = VitsNativeAudio(
        pcm = chunks.flatMap { it.pcm.asIterable() }.toFloatArray(),
        sampleRateHz = VitsAudioOutputValidator.FINAL_RATE_HZ,
    )

    private fun chunkText(text: String, maximumCharacters: Int = 180): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            if (current.isNotEmpty() && current.length + word.length + 1 > maximumCharacters) {
                chunks += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.ifEmpty { listOf(text) }
    }

    override fun close() {
        session.close()
    }

    private fun writeWav(output: OutputStream, samples: FloatArray, sampleRateHz: Int) {
        val dataSize = samples.size * 2
        fun writeInt(value: Int) {
            output.write(value and 0xff); output.write(value ushr 8 and 0xff)
            output.write(value ushr 16 and 0xff); output.write(value ushr 24 and 0xff)
        }
        fun writeShort(value: Int) { output.write(value and 0xff); output.write(value ushr 8 and 0xff) }
        output.write("RIFF".toByteArray(Charsets.US_ASCII)); writeInt(36 + dataSize)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeInt(16); writeShort(1); writeShort(1)
        writeInt(sampleRateHz); writeInt(sampleRateHz * 2); writeShort(2); writeShort(16)
        output.write("data".toByteArray(Charsets.US_ASCII)); writeInt(dataSize)
        samples.forEach { sample ->
            val value = (sample * if (sample < 0) 32768 else 32767).toInt()
            writeShort(value)
        }
    }
}
