package com.homoludens.citacknjiga.tts.onnx

import com.homoludens.citacknjiga.core.generation.GeneratedSegmentAudio
import com.homoludens.citacknjiga.core.generation.GenerationProvenance
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
        if (apiLevel >= 33 && abi == "arm64-v8a" && runtimeAvailable() && vitsStore.activePackage() != null) {
            add(TtsEngine.VITS)
        }
    }

    public fun select(preferred: TtsEngine): TtsEngine = preferred.takeIf { it in available() } ?: TtsEngine.KOKORO
}

/** Persists the user's engine choice while falling back closed to Kokoro. */
public class TtsEnginePreference(
    private val selector: TtsEngineSelector,
    private val preferences: SharedPreferences,
) {
    public var selected: TtsEngine = load()
        private set

    public fun available(): List<TtsEngine> = selector.available()

    public fun refresh() {
        val refreshed = selector.select(selected)
        if (refreshed != selected) {
            selected = refreshed
            preferences.edit().putString(KEY, refreshed.name).apply()
        }
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
) : SegmentGenerator, AutoCloseable {
    override suspend fun generate(segment: AudioSegmentEntity, block: NarrationBlockEntity): GeneratedSegmentAudio {
        val prepared = frontend.process(block.sourceText)
        val expectedKey = VitsGenerationContract.generationKey(packageInfo, prepared.tokenIds)
        check(segment.generationKey == expectedKey) { "VITS generation key does not match the pending segment" }
        val native = session.generate(prepared.tokenIds.toIntArray(), packageInfo.speakerId ?: 0, 1f)
        val final = VitsAudioOutputValidator.resampleOnce(native)
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
            nativeSampleRateHz = native.sampleRateHz,
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
            writer = { output -> writeWav(output, final.pcm, final.sampleRateHz) },
            validator = { file ->
                val info = PcmWavValidator.validate(file)
                require(info.sampleCount == final.pcm.size.toLong()) { "VITS WAV sample count is invalid" }
            },
            artifactExtension = "wav",
        )
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
