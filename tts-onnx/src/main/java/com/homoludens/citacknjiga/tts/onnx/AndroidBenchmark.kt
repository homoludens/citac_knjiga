package com.homoludens.citacknjiga.tts.onnx

import android.content.Context
import com.homoludens.citacknjiga.core.storage.AppPrivateStorage
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.homoludens.citacknjiga.tts.onnx.preprocessing.SerbianPreprocessor
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.max

public data class AndroidBenchmarkReport(
    val kind: String = "android-device-benchmark",
    @SerializedName("report_version") val reportVersion: Int = 1,
    val task: String = "build-serbian-audiobook-mvp 5.1",
    val status: String,
    val completed: Boolean,
    val device: DeviceParityDeviceIdentity,
    val build: DeviceParityBuildIdentity,
    val runtime: DeviceParityRuntimeIdentity,
    val model: DeviceParityModelIdentity,
    val workload: AndroidBenchmarkWorkload,
    val measurements: AndroidBenchmarkMeasurements,
    val thermal: AndroidBenchmarkThermal,
    val battery: AndroidBenchmarkBattery,
    val limitations: List<String>,
    val failure: String?,
    @SerializedName("created_at_epoch_ms") val createdAtEpochMs: Long,
)

public data class AndroidBenchmarkWorkload(
    val profile: String = "representative-serbian-typed-input-v1",
    @SerializedName("target_audio_seconds") val targetAudioSeconds: Int,
    @SerializedName("audio_seconds_generated") val audioSecondsGenerated: Double,
    @SerializedName("inference_calls") val inferenceCalls: Int,
    @SerializedName("input_scripts") val inputScripts: List<String> = listOf("latin", "cyrillic"),
    val speed: Float = 1f,
)

public data class AndroidBenchmarkMeasurements(
    @SerializedName("model_load_time_ms") val modelLoadTimeMs: Long?,
    @SerializedName("workload_wall_time_ms") val workloadWallTimeMs: Long?,
    @SerializedName("real_time_factor") val realTimeFactor: Double?,
    @SerializedName("peak_process_memory_bytes") val peakProcessMemoryBytes: Long?,
    @SerializedName("peak_process_memory_kib") val peakProcessMemoryKib: Long?,
    @SerializedName("cpu_utilization_percent") val cpuUtilizationPercent: Double?,
    @SerializedName("peak_cpu_utilization_percent") val peakCpuUtilizationPercent: Double?,
    @SerializedName("cpu_sample_count") val cpuSampleCount: Int,
)

public data class AndroidBenchmarkThermal(
    @SerializedName("temperature_source") val temperatureSource: String = "battery_changed",
    @SerializedName("temperature_start_celsius") val temperatureStartCelsius: Double?,
    @SerializedName("temperature_min_celsius") val temperatureMinCelsius: Double?,
    @SerializedName("temperature_max_celsius") val temperatureMaxCelsius: Double?,
    @SerializedName("temperature_end_celsius") val temperatureEndCelsius: Double?,
    @SerializedName("temperature_sample_count") val temperatureSampleCount: Int,
    @SerializedName("thermal_status_start") val thermalStatusStart: Int?,
    @SerializedName("thermal_status_max") val thermalStatusMax: Int?,
    @SerializedName("thermal_status_end") val thermalStatusEnd: Int?,
    @SerializedName("thermal_status_sample_count") val thermalStatusSampleCount: Int,
    @SerializedName("throttling_observed") val throttlingObserved: Boolean?,
    @SerializedName("throttling_transitions") val throttlingTransitions: Int,
)

public data class AndroidBenchmarkBattery(
    @SerializedName("level_start_percent") val levelStartPercent: Double?,
    @SerializedName("level_end_percent") val levelEndPercent: Double?,
    @SerializedName("level_change_percent") val levelChangePercent: Double?,
)

/** Runs the production Serbian typed-input boundary and writes only aggregate evidence. */
public class AndroidBenchmarkRunner(
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    public fun runAndPersist(
        context: Context,
        targetAudioSeconds: Int = DEFAULT_TARGET_AUDIO_SECONDS,
        reportStore: AndroidBenchmarkReportStore = AndroidBenchmarkReportStore(
            AppPrivateStorage(context.filesDir).benchmarkReportsDirectory,
        ),
        configuration: OnnxRuntimeConfiguration = OnnxRuntimeContract.CPU_BASELINE,
        task: String = "build-serbian-audiobook-mvp 5.1",
    ): AndroidBenchmarkReport {
        require(targetAudioSeconds > 0) { "Benchmark duration must be positive" }
        val identity = identities(context)
        val sampler = BenchmarkSampler(context)
        sampler.start()
        var packageInfo: InstalledModelPackage? = null
        var loadTimeMs: Long? = null
        var workload: AndroidBenchmarkWorkload? = null
        var measurements: AndroidBenchmarkMeasurements? = null
        var failure: String? = null
        try {
            val store = ModelPackageStore(context.filesDir)
            packageInfo = store.activePackage()
                ?: throw OnnxTtsException("No verified model package is installed")
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            OnnxTtsSession.open(store, packageInfo, configuration).use { session ->
                loadTimeMs = (SystemClock.elapsedRealtimeNanos() - loadStarted) / NANOS_PER_MILLISECOND
                val preprocessor = SerbianPreprocessor.fromAssets(context.assets, context.filesDir)
                val started = sampler.beginWorkload()
                var audioSamples = 0L
                var calls = 0
                while (audioSamples < targetAudioSeconds.toLong() * OnnxRuntimeContract.SAMPLE_RATE_HZ) {
                    val processed = preprocessor.process(WORKLOAD_INPUTS[calls % WORKLOAD_INPUTS.size])
                    val output = session.generate(processed.tokenIds, speed = 1f)
                    audioSamples += output.pcm.size
                    calls++
                }
                val ended = sampler.endWorkload(started)
                val audioSeconds = audioSamples.toDouble() / OnnxRuntimeContract.SAMPLE_RATE_HZ
                workload = AndroidBenchmarkWorkload(
                    targetAudioSeconds = targetAudioSeconds,
                    audioSecondsGenerated = audioSeconds,
                    inferenceCalls = calls,
                )
                measurements = AndroidBenchmarkMeasurements(
                    modelLoadTimeMs = loadTimeMs,
                    workloadWallTimeMs = ended.wallNanos / NANOS_PER_MILLISECOND,
                    realTimeFactor = ended.wallNanos.toDouble() / NANOS_PER_SECOND / audioSeconds,
                    peakProcessMemoryBytes = sampler.peakProcessMemoryBytes,
                    peakProcessMemoryKib = sampler.peakProcessMemoryBytes?.div(BYTES_PER_KIB),
                    cpuUtilizationPercent = ended.cpuNanos.toDouble() / ended.wallNanos * 100.0,
                    peakCpuUtilizationPercent = sampler.peakCpuUtilizationPercent,
                    cpuSampleCount = sampler.cpuSampleCount,
                )
            }
        } catch (throwable: Throwable) {
            failure = throwable::class.simpleName ?: "benchmark_failure"
        } finally {
            sampler.close()
        }

        val report = AndroidBenchmarkReport(
            task = task,
            status = if (failure == null) "completed" else "failed",
            completed = failure == null,
            device = identity.device,
            build = identity.build,
            runtime = DeviceParityRuntimeIdentity.from(configuration),
            model = packageInfo?.let {
                DeviceParityModelIdentity(it.packageId, it.packageVersion, it.identitySha256, it.modelSha256, it.voiceSha256)
            } ?: DeviceParityModelIdentity(null, null, null, null, null),
            workload = workload ?: AndroidBenchmarkWorkload(
                targetAudioSeconds = targetAudioSeconds,
                audioSecondsGenerated = 0.0,
                inferenceCalls = 0,
            ),
            measurements = measurements ?: AndroidBenchmarkMeasurements(
                modelLoadTimeMs = loadTimeMs,
                workloadWallTimeMs = null,
                realTimeFactor = null,
                peakProcessMemoryBytes = sampler.peakProcessMemoryBytes,
                peakProcessMemoryKib = sampler.peakProcessMemoryBytes?.div(BYTES_PER_KIB),
                cpuUtilizationPercent = null,
                peakCpuUtilizationPercent = sampler.peakCpuUtilizationPercent,
                cpuSampleCount = sampler.cpuSampleCount,
            ),
            thermal = sampler.thermal(),
            battery = sampler.battery(),
            limitations = LIMITATIONS,
            failure = failure,
            createdAtEpochMs = nowEpochMs(),
        )
        reportStore.writeAtomic(report)
        return report
    }

    private data class Identities(
        val device: DeviceParityDeviceIdentity,
        val build: DeviceParityBuildIdentity,
    )

    private fun identities(context: Context): Identities {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val buildType = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "debug"
        } else {
            "release"
        }
        return Identities(
            device = DeviceParityDeviceIdentity(
                Build.MANUFACTURER,
                Build.MODEL,
                Build.DEVICE,
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            ),
            build = DeviceParityBuildIdentity(
                context.packageName,
                packageInfo.versionName ?: "unknown",
                packageInfo.longVersionCode,
                buildType,
            ),
        )
    }

    private companion object {
        const val DEFAULT_TARGET_AUDIO_SECONDS = 15 * 60
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BYTES_PER_KIB = 1024L
        const val SAMPLE_INTERVAL_MS = 250L
        val WORKLOAD_INPUTS = listOf(
            "Dobar dan. Ovo je kratka recenica za proveru rada srpskog glasa.",
            "Čitanje knjige treba da bude jasno i stabilno.",
            "Ово је кратка реченица за проверу гласа.",
        )
        val LIMITATIONS = listOf(
            "Process memory is sampled totalPss; Android does not expose a portable peak RSS for this app process.",
            "CPU is process elapsed CPU time divided by wall time; vendor scheduler and per-core attribution are unavailable.",
            "Temperature is the battery ACTION_BATTERY_CHANGED sensor, not a SoC or skin temperature sensor.",
            "Thermal status is Android's aggregate PowerManager status; vendor thermal-zone details are not exposed to the app.",
            "Battery level is an integer vendor estimate and is sampled at benchmark boundaries; charging state is not inferred.",
            "The benchmark discards validated PCM after each call and therefore does not measure file encoding or storage throughput.",
            "Real-time factor and peak process memory are informational and do not gate implementation.",
        )
    }
}

private class BenchmarkSampler(private val context: Context) : AutoCloseable {
    @Volatile private var running = false
    @Volatile private var workloadStarted = false
    private var thread: Thread? = null
    private var previousCpuNanos: Long? = null
    private var previousWallNanos: Long? = null
    private val cpuSamples = mutableListOf<Double>()
    private var temperatureValues = mutableListOf<Double>()
    private var thermalValues = mutableListOf<Int>()
    private var batteryStart: Double? = null
    private var batteryEnd: Double? = null
    private var previousThrottled = false
    private var throttlingTransitionsValue = 0
    var peakProcessMemoryBytes: Long? = null
        private set
    var peakCpuUtilizationPercent: Double? = null
        private set
    val cpuSampleCount: Int get() = cpuSamples.size

    fun start() {
        sample()
        running = true
        thread = Thread({
            while (running) {
                sample()
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    // Stop promptly when the benchmark finishes.
                }
            }
        }, "android-benchmark-sampler").also { it.start() }
    }

    fun beginWorkload(): WorkloadClock {
        val clock = WorkloadClock(Process.getElapsedCpuTime() * NANOS_PER_MILLISECOND, SystemClock.elapsedRealtimeNanos())
        previousCpuNanos = clock.cpuNanos
        previousWallNanos = clock.wallNanos
        workloadStarted = true
        return clock
    }

    fun endWorkload(start: WorkloadClock): WorkloadResult {
        workloadStarted = false
        val wallNanos = SystemClock.elapsedRealtimeNanos() - start.wallNanos
        val cpuNanos = Process.getElapsedCpuTime() * NANOS_PER_MILLISECOND - start.cpuNanos
        return WorkloadResult(wallNanos, cpuNanos)
    }

    fun thermal(): AndroidBenchmarkThermal = AndroidBenchmarkThermal(
        temperatureStartCelsius = temperatureValues.firstOrNull(),
        temperatureMinCelsius = temperatureValues.minOrNull(),
        temperatureMaxCelsius = temperatureValues.maxOrNull(),
        temperatureEndCelsius = temperatureValues.lastOrNull(),
        temperatureSampleCount = temperatureValues.size,
        thermalStatusStart = thermalValues.firstOrNull(),
        thermalStatusMax = thermalValues.maxOrNull(),
        thermalStatusEnd = thermalValues.lastOrNull(),
        thermalStatusSampleCount = thermalValues.size,
        throttlingObserved = if (thermalValues.isEmpty()) null else thermalValues.any { it >= PowerManager.THERMAL_STATUS_MODERATE },
        throttlingTransitions = throttlingTransitionsValue,
    )

    fun battery(): AndroidBenchmarkBattery = AndroidBenchmarkBattery(
        levelStartPercent = batteryStart,
        levelEndPercent = batteryEnd ?: batteryStart,
        levelChangePercent = batteryEnd?.let { end -> batteryStart?.let { start -> end - start } },
    )

    override fun close() {
        running = false
        thread?.interrupt()
        thread?.join(2_000L)
        sample()
    }

    private fun sample() {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        peakProcessMemoryBytes = max(peakProcessMemoryBytes ?: 0L, memory.totalPss.toLong() * BYTES_PER_KIB)

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) raw.toDouble() * 100.0 / scale else null
        }
        if (batteryStart == null && level != null) batteryStart = level
        if (level != null) batteryEnd = level
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.let { raw ->
            if (raw != Int.MIN_VALUE && raw >= 0) temperatureValues += raw.toDouble() / 10.0
        }

        val power = context.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29 && power != null) {
            val status = power.currentThermalStatus
            thermalValues += status
            val throttled = status >= PowerManager.THERMAL_STATUS_MODERATE
            if (throttled && !previousThrottled) throttlingTransitionsValue++
            previousThrottled = throttled
        }

        if (workloadStarted) {
            val cpuNanos = Process.getElapsedCpuTime() * NANOS_PER_MILLISECOND
            val wallNanos = SystemClock.elapsedRealtimeNanos()
            val oldCpu = previousCpuNanos
            val oldWall = previousWallNanos
            if (oldCpu != null && oldWall != null && wallNanos > oldWall) {
                cpuSamples += (cpuNanos - oldCpu).toDouble() / (wallNanos - oldWall) * 100.0
                peakCpuUtilizationPercent = max(peakCpuUtilizationPercent ?: 0.0, cpuSamples.last())
            }
            previousCpuNanos = cpuNanos
            previousWallNanos = wallNanos
        }
    }

    data class WorkloadClock(val cpuNanos: Long, val wallNanos: Long)
    data class WorkloadResult(val wallNanos: Long, val cpuNanos: Long)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val BYTES_PER_KIB = 1024L
        const val SAMPLE_INTERVAL_MS = 250L
    }
}

public class AndroidBenchmarkReportStore(
    private val directory: File,
    public val reportFile: File = File(directory, "android-benchmark-report.json"),
) {
    public fun writeAtomic(report: AndroidBenchmarkReport): File {
        require(directorySafe(report)) { "Benchmark report contains a document-text field" }
        require(reportFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Benchmark report directory is unavailable"
        }
        val temporary = File(reportFile.parentFile, ".${reportFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(GSON.toJson(report).toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), reportFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), reportFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return reportFile
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun directorySafe(report: AndroidBenchmarkReport): Boolean = !GSON.toJson(report).contains("\"text\"")

    private companion object {
        val GSON = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    }
}
