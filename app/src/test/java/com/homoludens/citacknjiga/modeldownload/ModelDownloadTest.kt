package com.homoludens.citacknjiga.modeldownload

import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.diagnostics.ModelReleaseDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

public class ModelDownloadTest {
    @Test
    public fun streamsBytesAndPersistsProgressWithoutExposingAPathInProgress() = runBlocking {
        val fixture = Fixture(response = ModelDownloadResponse(200, 4, ChunkedInputStream(byteArrayOf(1, 2, 3, 4))))
        val progress = mutableListOf<ModelDownloadProgress>()

        val downloaded = fixture.session.download(ModelReleaseDescriptor.KOKORO, "progress-work") {
            progress += it
        }

        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), downloaded.readBytes().toList())
        assertEquals(4L, progress.last().bytesDownloaded)
        assertEquals(100, progress.last().percentage)
        assertFalse(progress.any { it.toString().contains(downloaded.path) })
        downloaded.delete()
        fixture.cleanup()
        Unit
    }

    @Test
    public fun cancellationRemovesPartialTemporaryFile() = runBlocking {
        val fixture = Fixture(response = ModelDownloadResponse(200, 4, ChunkedInputStream(byteArrayOf(1, 2, 3, 4))))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                fixture.session.download(ModelReleaseDescriptor.KOKORO, "cancel-work") {
                    if (it.bytesDownloaded > 0) throw CancellationException("test cancellation")
                }
            }
        }

        fixture.assertNoTemporaryFiles()
    }

    @Test
    public fun disconnectRemovesPartialTemporaryFile() = runBlocking {
        val fixture = Fixture(
            response = ModelDownloadResponse(200, -1, FailingInputStream()),
        )

        val failure = assertThrows(ModelDownloadException::class.java) {
            runBlocking { fixture.session.download(ModelReleaseDescriptor.KOKORO, "disconnect-work") {} }
        }

        assertEquals(ModelDownloadFailureCode.DISCONNECTED, failure.code)
        fixture.assertNoTemporaryFiles()
    }

    @Test
    public fun shortResponseRemovesTemporaryFile() = runBlocking {
        val fixture = Fixture(response = ModelDownloadResponse(200, 3, ByteArrayInputStream(byteArrayOf(1, 2, 3))))

        val failure = assertThrows(ModelDownloadException::class.java) {
            runBlocking { fixture.session.download(ModelReleaseDescriptor.KOKORO, "short-work") {} }
        }

        assertEquals(ModelDownloadFailureCode.SHORT_RESPONSE, failure.code)
        fixture.assertNoTemporaryFiles()
    }

    @Test
    public fun oversizedResponseRemovesTemporaryFile() = runBlocking {
        val fixture = Fixture(response = ModelDownloadResponse(200, 5, ByteArrayInputStream(ByteArray(5))))

        val failure = assertThrows(ModelDownloadException::class.java) {
            runBlocking { fixture.session.download(ModelReleaseDescriptor.KOKORO, "oversized-work") {} }
        }

        assertEquals(ModelDownloadFailureCode.OVERSIZED_RESPONSE, failure.code)
        fixture.assertNoTemporaryFiles()
    }

    @Test
    public fun failedHttpResponseRemovesTemporaryFile() = runBlocking {
        val fixture = Fixture(response = ModelDownloadResponse(503, 4, ByteArrayInputStream(ByteArray(0))))

        val failure = assertThrows(ModelDownloadException::class.java) {
            runBlocking { fixture.session.download(ModelReleaseDescriptor.KOKORO, "http-failure") {} }
        }

        assertEquals(ModelDownloadFailureCode.INVALID_RESPONSE, failure.code)
        fixture.assertNoTemporaryFiles()
    }

    private class Fixture(response: ModelDownloadResponse) {
        private val root = Files.createTempDirectory("model-download-test").toFile()
        private val appStorage = AppPrivateStorage(root)
        val session = ModelDownloadSession(
            storage = ModelDownloadStorage(appStorage),
            transport = ModelDownloadTransport { response },
            expectedSize = { 4L },
        )

        fun assertNoTemporaryFiles() {
            assertFalse(appStorage.temporaryDirectory.walkTopDown().any(File::isFile))
            cleanup()
        }

        fun cleanup() {
            root.deleteRecursively()
        }
    }

    private class ChunkedInputStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int = if (index == bytes.size) -1 else bytes[index++].toInt()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index == bytes.size) return -1
            buffer[offset] = bytes[index++]
            return 1
        }
    }

    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw IOException("connection lost")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IOException("connection lost")
    }
}
