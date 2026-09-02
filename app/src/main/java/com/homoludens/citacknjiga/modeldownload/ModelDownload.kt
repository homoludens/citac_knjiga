package com.homoludens.citacknjiga.modeldownload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.homoludens.citacknjiga.core.generation.GenerationWorkerFactory
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import com.homoludens.citacknjiga.diagnostics.ModelEngine
import com.homoludens.citacknjiga.diagnostics.ModelReleaseDescriptor
import com.homoludens.citacknjiga.tts.onnx.InstalledModelPackage
import com.homoludens.citacknjiga.tts.onnx.ModelPackageFailureCode
import com.homoludens.citacknjiga.tts.onnx.ModelPackageImportException
import com.homoludens.citacknjiga.tts.onnx.ModelPackageSource
import com.homoludens.citacknjiga.tts.onnx.ModelPackageStore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

public data class ModelDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Int,
)

public enum class ModelDownloadFailureCode {
    INVALID_RESPONSE,
    DISCONNECTED,
    SHORT_RESPONSE,
    OVERSIZED_RESPONSE,
    CHECKSUM_MISMATCH,
    INVALID_PACKAGE,
    INCOMPATIBLE_PACKAGE,
    PUBLICATION,
    STORAGE,
    ERROR,
}

public class ModelDownloadException(
    public val code: ModelDownloadFailureCode,
    message: String = code.name,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A response is deliberately transport-shaped so JVM tests never need a socket. */
public class ModelDownloadResponse(
    public val statusCode: Int,
    public val contentLengthBytes: Long,
    public val body: InputStream,
    private val closeAction: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        closeAction()
    }
}

public fun interface ModelDownloadTransport {
    public fun open(descriptor: ModelReleaseDescriptor): ModelDownloadResponse
}

public fun interface ModelDownloadPackageInstaller {
    public fun install(descriptor: ModelReleaseDescriptor, stagedFile: File): InstalledModelPackage
}

/** Verifies the release asset before delegating package checks and publication to each engine store. */
public class ModelPackageDownloadInstaller(
    private val modelPackageStore: ModelPackageStore,
) : ModelDownloadPackageInstaller {
    override fun install(descriptor: ModelReleaseDescriptor, stagedFile: File): InstalledModelPackage {
        val outerSha256 = stagedFile.takeIf(File::isFile)?.inputStream()?.use { input ->
            ModelPackageStore.sha256(input)
        }
        if (outerSha256 != descriptor.outerSha256) {
            throw ModelDownloadException(ModelDownloadFailureCode.CHECKSUM_MISMATCH)
        }

        val installed = try {
            when (descriptor.engine) {
                ModelEngine.KOKORO -> modelPackageStore.importPackage(
                    ModelPackageSource { stagedFile.inputStream() },
                    expectedPackageVersion = descriptor.version,
                )
                ModelEngine.VITS -> modelPackageStore.importVitsPackage(
                    ModelPackageSource { stagedFile.inputStream() },
                    expectedPackageVersion = descriptor.version,
                )
            }
        } catch (failure: ModelPackageImportException) {
            throw ModelDownloadException(failure.toDownloadFailureCode(), cause = failure)
        }

        return installed
    }

    private fun ModelPackageImportException.toDownloadFailureCode(): ModelDownloadFailureCode = when (code) {
        ModelPackageFailureCode.CHECKSUM_MISMATCH -> ModelDownloadFailureCode.CHECKSUM_MISMATCH
        ModelPackageFailureCode.INCOMPATIBLE -> ModelDownloadFailureCode.INCOMPATIBLE_PACKAGE
        ModelPackageFailureCode.PUBLICATION -> ModelDownloadFailureCode.PUBLICATION
        ModelPackageFailureCode.INVALID_ARCHIVE,
        ModelPackageFailureCode.INVALID_MANIFEST,
        -> ModelDownloadFailureCode.INVALID_PACKAGE
        else -> ModelDownloadFailureCode.ERROR
    }
}

/** Opens only the immutable release asset selected by the application. */
public class HttpsModelDownloadTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : ModelDownloadTransport {
    override fun open(descriptor: ModelReleaseDescriptor): ModelDownloadResponse {
        require(ModelReleaseDescriptor.ALL.any { it == descriptor }) {
            "Model descriptor is not an approved release asset"
        }
        val connection = (URL(descriptor.assetUrl).openConnection() as? HttpsURLConnection)
            ?: throw ModelDownloadException(ModelDownloadFailureCode.INVALID_RESPONSE)
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) {
                throw ModelDownloadException(
                    ModelDownloadFailureCode.INVALID_RESPONSE,
                    "Model asset returned HTTP $status",
                )
            }
            val finalUrl = connection.url
            if (finalUrl.protocol != "https" || finalUrl.userInfo != null) {
                throw ModelDownloadException(ModelDownloadFailureCode.INVALID_RESPONSE)
            }
            return ModelDownloadResponse(
                statusCode = status,
                contentLengthBytes = connection.contentLengthLong,
                body = connection.inputStream,
                closeAction = connection::disconnect,
            )
        } catch (failure: ModelDownloadException) {
            connection.disconnect()
            throw failure
        } catch (failure: Exception) {
            connection.disconnect()
            throw ModelDownloadException(
                ModelDownloadFailureCode.DISCONNECTED,
                "Could not open model asset",
                failure,
            )
        }
    }
}

internal class ModelDownloadStorage(private val storage: AppPrivateStorage) {
    private val owner = "model-download"

    fun temporaryFile(engine: ModelEngine, workId: String): File {
        require(workId.matches(WORK_ID)) { "Model download work id is invalid" }
        if (!storage.temporaryDirectory.isDirectory && !storage.temporaryDirectory.mkdirs()) {
            throw ModelDownloadException(ModelDownloadFailureCode.STORAGE)
        }
        val file = stagedFile(engine, workId)
        if (file.exists() && !file.delete()) {
            throw ModelDownloadException(ModelDownloadFailureCode.STORAGE)
        }
        return file
    }

    fun stagedFile(engine: ModelEngine, workId: String): File {
        require(workId.matches(WORK_ID)) { "Model download work id is invalid" }
        return storage.temporaryFile(owner, "${engine.name.lowercase()}-$workId.part")
    }

    fun delete(engine: ModelEngine, workId: String) {
        stagedFile(engine, workId).delete()
    }

    private companion object {
        val WORK_ID = Regex("[A-Za-z0-9-]{8,}")
    }
}

internal class ModelDownloadSession(
    private val storage: ModelDownloadStorage,
    private val transport: ModelDownloadTransport,
    private val expectedSize: (ModelReleaseDescriptor) -> Long = { it.expectedSizeBytes },
) {
    suspend fun download(
        descriptor: ModelReleaseDescriptor,
        workId: String,
        onProgress: suspend (ModelDownloadProgress) -> Unit,
    ): File {
        val target = storage.temporaryFile(descriptor.engine, workId)
        var completed = false
        try {
            val response = try {
                transport.open(descriptor)
            } catch (failure: ModelDownloadException) {
                throw failure
            } catch (failure: IOException) {
                throw ModelDownloadException(ModelDownloadFailureCode.DISCONNECTED, cause = failure)
            } catch (failure: Throwable) {
                throw ModelDownloadException(ModelDownloadFailureCode.ERROR, cause = failure)
            }
            val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { response.close() }
            try {
                stream(response, target, expectedSize(descriptor), onProgress)
            } finally {
                completionHandle?.dispose()
                response.close()
            }
            completed = true
            return target
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!completed) target.delete()
        }
    }

    internal suspend fun stream(
        response: ModelDownloadResponse,
        target: File,
        expectedSizeBytes: Long,
        onProgress: suspend (ModelDownloadProgress) -> Unit,
    ) {
        if (expectedSizeBytes <= 0L || expectedSizeBytes > MAX_DOWNLOAD_BYTES) {
            throw ModelDownloadException(ModelDownloadFailureCode.OVERSIZED_RESPONSE)
        }
        if (response.statusCode !in 200..299) {
            throw ModelDownloadException(ModelDownloadFailureCode.INVALID_RESPONSE)
        }
        when {
            response.contentLengthBytes > MAX_DOWNLOAD_BYTES ||
                response.contentLengthBytes > expectedSizeBytes ->
                throw ModelDownloadException(ModelDownloadFailureCode.OVERSIZED_RESPONSE)
            response.contentLengthBytes >= 0L && response.contentLengthBytes < expectedSizeBytes ->
                throw ModelDownloadException(ModelDownloadFailureCode.SHORT_RESPONSE)
        }

        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var transferred = 0L
                onProgress(ModelDownloadProgress(0L, expectedSizeBytes, 0))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = try {
                        response.body.read(buffer)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw ModelDownloadException(ModelDownloadFailureCode.DISCONNECTED, cause = failure)
                    }
                    if (count < 0) break
                    if (count == 0) continue
                    val next = transferred + count
                    if (next > MAX_DOWNLOAD_BYTES || next > expectedSizeBytes) {
                        throw ModelDownloadException(ModelDownloadFailureCode.OVERSIZED_RESPONSE)
                    }
                    try {
                        output.write(buffer, 0, count)
                    } catch (failure: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw ModelDownloadException(ModelDownloadFailureCode.STORAGE, cause = failure)
                    }
                    transferred = next
                    onProgress(
                        ModelDownloadProgress(
                            bytesDownloaded = transferred,
                            totalBytes = expectedSizeBytes,
                            percentage = ((transferred * 100L) / expectedSizeBytes).toInt().coerceAtMost(100),
                        ),
                    )
                }
                if (transferred != expectedSizeBytes) {
                    throw ModelDownloadException(ModelDownloadFailureCode.SHORT_RESPONSE)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelDownloadException) {
            throw failure
        } catch (failure: IOException) {
            throw ModelDownloadException(ModelDownloadFailureCode.STORAGE, cause = failure)
        }
    }

    internal companion object {
        const val MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L
    }
}

public object ModelDownloadWorkContract {
    public const val ENGINE_KEY: String = "model_engine"
    public const val BYTES_DOWNLOADED_KEY: String = "bytes_downloaded"
    public const val TOTAL_BYTES_KEY: String = "total_bytes"
    public const val PERCENTAGE_KEY: String = "percentage"
    public const val STATUS_KEY: String = "download_status"
    public const val ERROR_CODE_KEY: String = "error_code"
    public const val PACKAGE_ID_KEY: String = "package_id"
    public const val PACKAGE_VERSION_KEY: String = "package_version"
    public const val PACKAGE_SHA256_KEY: String = "package_sha256"
    public const val UNIQUE_WORK_PREFIX: String = "model-download-"
    public const val TAG_PREFIX: String = "model-download:"

    public fun uniqueWorkName(engine: ModelEngine): String = UNIQUE_WORK_PREFIX + engine.name.lowercase()

    public fun tag(engine: ModelEngine): String = TAG_PREFIX + engine.name.lowercase()
}

internal class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val session: ModelDownloadSession,
    private val packageInstaller: ModelDownloadPackageInstaller,
    private val downloadStorage: ModelDownloadStorage,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val engine = inputData.getString(ModelDownloadWorkContract.ENGINE_KEY)
            ?.let { value -> ModelEngine.entries.firstOrNull { it.name.lowercase() == value } }
            ?: return@withContext Result.failure(workDataOf(ModelDownloadWorkContract.ERROR_CODE_KEY to "INVALID_ENGINE"))
        val descriptor = ModelReleaseDescriptor.ALL.single { it.engine == engine }
        try {
            setProgress(progressData(engine, ModelDownloadProgress(0L, descriptor.expectedSizeBytes, 0), "DOWNLOADING"))
            var lastReportedBytes = 0L
            session.download(descriptor, id.toString()) { progress ->
                if (progress.bytesDownloaded == progress.totalBytes ||
                    progress.bytesDownloaded - lastReportedBytes >= PROGRESS_INTERVAL_BYTES
                ) {
                    setProgress(progressData(engine, progress, "DOWNLOADING"))
                    lastReportedBytes = progress.bytesDownloaded
                }
            }
            setProgress(
                progressData(
                    engine,
                    ModelDownloadProgress(
                        descriptor.expectedSizeBytes,
                        descriptor.expectedSizeBytes,
                        100,
                    ),
                    "VERIFYING",
                ),
            )
            val installed = packageInstaller.install(
                descriptor,
                downloadStorage.stagedFile(engine, id.toString()),
            )
            val installedData = workDataOf(
                ModelDownloadWorkContract.ENGINE_KEY to engine.name.lowercase(),
                ModelDownloadWorkContract.STATUS_KEY to "INSTALLED",
                ModelDownloadWorkContract.PACKAGE_ID_KEY to installed.packageId,
                ModelDownloadWorkContract.PACKAGE_VERSION_KEY to installed.packageVersion,
                ModelDownloadWorkContract.PACKAGE_SHA256_KEY to installed.identitySha256,
            )
            setProgress(installedData)
            Result.success(installedData)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelDownloadException) {
            setProgress(
                workDataOf(
                    ModelDownloadWorkContract.ENGINE_KEY to engine.name.lowercase(),
                    ModelDownloadWorkContract.STATUS_KEY to "FAILED",
                    ModelDownloadWorkContract.ERROR_CODE_KEY to failure.code.name,
                ),
            )
            Result.failure(workDataOf(ModelDownloadWorkContract.ERROR_CODE_KEY to failure.code.name))
        } catch (failure: Throwable) {
            setProgress(
                workDataOf(
                    ModelDownloadWorkContract.ENGINE_KEY to engine.name.lowercase(),
                    ModelDownloadWorkContract.STATUS_KEY to "FAILED",
                    ModelDownloadWorkContract.ERROR_CODE_KEY to ModelDownloadFailureCode.ERROR.name,
                ),
            )
            Result.failure(workDataOf(ModelDownloadWorkContract.ERROR_CODE_KEY to ModelDownloadFailureCode.ERROR.name))
        } finally {
            downloadStorage.delete(engine, id.toString())
        }
    }

    private fun progressData(
        engine: ModelEngine,
        progress: ModelDownloadProgress,
        status: String,
    ) = workDataOf(
        ModelDownloadWorkContract.ENGINE_KEY to engine.name.lowercase(),
        ModelDownloadWorkContract.BYTES_DOWNLOADED_KEY to progress.bytesDownloaded,
        ModelDownloadWorkContract.TOTAL_BYTES_KEY to progress.totalBytes,
        ModelDownloadWorkContract.PERCENTAGE_KEY to progress.percentage,
        ModelDownloadWorkContract.STATUS_KEY to status,
    )

    private companion object {
        const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
    }
}

public class ModelDownloadWorkerFactory(
    private val storage: AppPrivateStorage,
    private val transport: ModelDownloadTransport = HttpsModelDownloadTransport(),
    private val packageInstaller: ModelDownloadPackageInstaller =
        ModelPackageDownloadInstaller(ModelPackageStore(storage.rootDirectory)),
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == ModelDownloadWorker::class.java.name) {
        ModelDownloadWorker(
            appContext,
            workerParameters,
            ModelDownloadSession(ModelDownloadStorage(storage), transport),
            packageInstaller,
            ModelDownloadStorage(storage),
        )
    } else {
        null
    }
}

public class AppWorkerFactory(
    private val generationWorkerFactory: GenerationWorkerFactory,
    private val modelDownloadWorkerFactory: ModelDownloadWorkerFactory,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = modelDownloadWorkerFactory.createWorker(
        appContext,
        workerClassName,
        workerParameters,
    ) ?: generationWorkerFactory.createWorker(appContext, workerClassName, workerParameters)
}

public class ModelDownloadWorkScheduler(
    private val workManagerProvider: () -> WorkManager,
    private val constraints: Constraints = defaultConstraints(),
) {
    public fun enqueue(engine: ModelEngine): Operation {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorkContract.ENGINE_KEY to engine.name.lowercase()))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(ModelDownloadWorkContract.tag(engine))
            .build()
        return workManagerProvider().enqueueUniqueWork(
            ModelDownloadWorkContract.uniqueWorkName(engine),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    public fun cancel(engine: ModelEngine): Operation = workManagerProvider().cancelUniqueWork(
        ModelDownloadWorkContract.uniqueWorkName(engine),
    )

    public fun workInfo(engine: ModelEngine): Flow<WorkInfo?> = workManagerProvider()
        .getWorkInfosForUniqueWorkFlow(ModelDownloadWorkContract.uniqueWorkName(engine))
        .map { it.firstOrNull() }

    public companion object {
        public fun defaultConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}
