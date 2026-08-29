package com.homoludens.citacknjiga.proof

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Streams the proof WAV through Android's local PCM audio API and owns all resources. */
public class LocalWavPlayer : AutoCloseable {
    private val lock = Any()
    private var playbackJob: Job? = null
    private var track: AudioTrack? = null
    private var playbackError: Throwable? = null

    public val lastError: Throwable?
        get() = synchronized(lock) { playbackError }

    public fun play(file: File, scope: CoroutineScope) {
        stop()
        synchronized(lock) { playbackError = null }
        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                stream(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(lock) { playbackError = failure }
            }
        }
    }

    public fun stop() {
        val activeTrack = synchronized(lock) {
            playbackJob?.cancel()
            playbackJob = null
            track.also { track = null }
        }
        activeTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
    }

    override fun close() = stop()

    private suspend fun stream(file: File) {
        var localTrack: AudioTrack? = null
        try {
            require(file.isFile) { "Generated WAV artifact is unavailable" }
            FileInputStream(file).use { input ->
                val dataSize = readHeader(input)
                val bufferSize = AudioTrack.getMinBufferSize(
                    24_000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4096)
                localTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(24_000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                synchronized(lock) { track = localTrack }
                localTrack!!.play()
                val buffer = ByteArray(8192)
                var remaining = dataSize
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw IOException("WAV data ended before its declared length")
                    var written = 0
                    while (written < count) {
                        currentCoroutineContext().ensureActive()
                        val result = localTrack!!.write(buffer, written, count - written, AudioTrack.WRITE_BLOCKING)
                        if (result <= 0) throw IOException("Android could not play the WAV artifact")
                        written += result
                    }
                    remaining -= count
                }
                localTrack!!.stop()
            }
        } finally {
            synchronized(lock) {
                if (track === localTrack) track = null
            }
            localTrack?.let { runCatching { it.release() } }
        }
    }

    private fun readHeader(input: FileInputStream): Int {
        val header = ByteArray(44)
        input.readFully(header)
        require(header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) { "Not a RIFF WAV artifact" }
        require(header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) { "Not a WAVE artifact" }
        require(readShort(header, 20) == 1 && readShort(header, 22) == 1) { "WAV must be mono PCM" }
        require(readInt(header, 24) == 24_000 && readShort(header, 34) == 16) { "WAV format is not 24 kHz PCM16" }
        require(header.copyOfRange(36, 40).contentEquals("data".toByteArray())) { "WAV data chunk is missing" }
        return readInt(header, 40).also { require(it >= 0) { "WAV data chunk is invalid" } }
    }

    private fun FileInputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            if (count < 0) throw IOException("WAV header is truncated")
            offset += count
        }
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        readShort(bytes, offset) or (readShort(bytes, offset + 2) shl 16)
}
