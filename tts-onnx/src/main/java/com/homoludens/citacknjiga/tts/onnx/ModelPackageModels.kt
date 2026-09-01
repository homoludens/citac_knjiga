package com.homoludens.citacknjiga.tts.onnx

/** Safe metadata exposed to application state after complete package validation. */
public data class InstalledModelPackage(
    val packageId: String,
    val packageVersion: String,
    val identitySha256: String,
    val modelSha256: String,
    val voiceSha256: String,
    val runtimeId: String = "onnxruntime-android",
    val runtimeVersion: String = "1.29.0",
    val preprocessingCompatibilityId: String = "kokoro-sr-ca5590d9",
    val preprocessingContractVersion: Int = 1,
    val minimumAndroidApi: Int = 30,
    val requiredAbi: String = "arm64-v8a",
    val sampleRateHz: Int = 24_000,
    val channels: Int = 1,
    val engine: String = "kokoro",
    val modelRevision: String? = null,
    val speakerId: Int? = null,
    val nativeSampleRateHz: Int? = null,
    val frontendVersion: String? = null,
    val resamplerVersion: String? = null,
    val qualificationStatus: String? = null,
)

public enum class ModelPackageFailureCode {
    SOURCE_UNAVAILABLE,
    STORAGE,
    INVALID_ARCHIVE,
    INVALID_MANIFEST,
    CHECKSUM_MISMATCH,
    INCOMPATIBLE,
    PUBLICATION,
    NO_VALID_PACKAGE,
    ERROR,
}

public data class ModelPackageFailure(
    val code: ModelPackageFailureCode,
)

public sealed interface ModelPackageImportResult {
    public data class Success(val packageInfo: InstalledModelPackage) : ModelPackageImportResult
    public data class Failure(val failure: ModelPackageFailure) : ModelPackageImportResult
}

public class ModelPackageImportException(
    public val code: ModelPackageFailureCode,
    message: String = code.name,
    cause: Throwable? = null,
) : Exception(message, cause)
