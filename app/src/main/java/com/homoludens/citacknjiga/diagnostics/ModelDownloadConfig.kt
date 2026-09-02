package com.homoludens.citacknjiga.diagnostics

import java.net.URI

public enum class ModelEngine {
    KOKORO,
    VITS,
}

/** Immutable application input for one approved GitHub model asset. */
public data class ModelReleaseDescriptor(
    val engine: ModelEngine,
    val repositoryOwner: String,
    val repositoryName: String,
    val releaseTag: String,
    val assetFileName: String,
    val version: String,
    val expectedSizeBytes: Long,
    val outerSha256: String,
    val assetUrl: String,
) {
    init {
        require(repositoryOwner == REPOSITORY_OWNER && repositoryName == REPOSITORY_NAME) {
            "Model release repository is not approved"
        }
        require(releaseTag.matches(SAFE_SEGMENT)) { "Model release tag is invalid" }
        require(assetFileName.matches(SAFE_SEGMENT) && assetFileName.endsWith(".zip")) {
            "Model asset filename is invalid"
        }
        require(version.matches(VERSION)) { "Model release version is invalid" }
        require(expectedSizeBytes > 0L) { "Model asset size must be positive" }
        require(outerSha256.matches(SHA256)) { "Model asset SHA-256 must be lowercase hexadecimal" }

        val expected = when (engine) {
            ModelEngine.KOKORO -> "kokoro-model-v1.0.0" to "kokoro-serbian-dragana-v2.zip"
            ModelEngine.VITS -> "vits-model-v1.0.0" to "serbian-vits-1.0.0.zip"
        }
        require(releaseTag == expected.first && assetFileName == expected.second) {
            "Model release and asset identity are inconsistent"
        }
        val expectedSize = when (engine) {
            ModelEngine.KOKORO -> 338_316_574L
            ModelEngine.VITS -> 121_971_081L
        }
        val expectedHash = when (engine) {
            ModelEngine.KOKORO -> "58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b"
            ModelEngine.VITS -> "45aa231e12c8a317f0d093cfb56d54066e19b53561b4ac401661109f19abe5dc"
        }
        require(version == "1.0.0" && expectedSizeBytes == expectedSize) {
            "Model release metadata is inconsistent"
        }
        require(outerSha256 == expectedHash) { "Model asset checksum is not approved" }

        val uri = runCatching { URI(assetUrl) }.getOrNull()
        require(uri != null && uri.scheme == "https" && uri.host == "github.com" &&
            uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Model asset URL must be an HTTPS GitHub URL without credentials or query data"
        }
        val expectedPath = "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/" +
            "${expected.first}/${expected.second}"
        require(uri.rawPath == expectedPath && assetUrl == "https://github.com$expectedPath") {
            "Model asset URL does not match the pinned GitHub release asset"
        }
    }

    public companion object {
        public const val REPOSITORY_OWNER: String = "homoludens"
        public const val REPOSITORY_NAME: String = "citac_knjiga"

        private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val SHA256 = Regex("[0-9a-f]{64}")

        public val KOKORO: ModelReleaseDescriptor = ModelReleaseDescriptor(
            engine = ModelEngine.KOKORO,
            repositoryOwner = REPOSITORY_OWNER,
            repositoryName = REPOSITORY_NAME,
            releaseTag = "kokoro-model-v1.0.0",
            assetFileName = "kokoro-serbian-dragana-v2.zip",
            version = "1.0.0",
            expectedSizeBytes = 338_316_574L,
            outerSha256 = "58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b",
            assetUrl = "https://github.com/homoludens/citac_knjiga/releases/download/" +
                "kokoro-model-v1.0.0/kokoro-serbian-dragana-v2.zip",
        )

        public val VITS: ModelReleaseDescriptor = ModelReleaseDescriptor(
            engine = ModelEngine.VITS,
            repositoryOwner = REPOSITORY_OWNER,
            repositoryName = REPOSITORY_NAME,
            releaseTag = "vits-model-v1.0.0",
            assetFileName = "serbian-vits-1.0.0.zip",
            version = "1.0.0",
            expectedSizeBytes = 121_971_081L,
            outerSha256 = "45aa231e12c8a317f0d093cfb56d54066e19b53561b4ac401661109f19abe5dc",
            assetUrl = "https://github.com/homoludens/citac_knjiga/releases/download/" +
                "vits-model-v1.0.0/serbian-vits-1.0.0.zip",
        )

        public val ALL: List<ModelReleaseDescriptor> = listOf(KOKORO, VITS)
    }
}
