import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val sherpaOnnxRevision = "34eba5a27220026b5981b633981c53205515067d"
val sherpaOnnxDirectory = layout.buildDirectory.dir("sherpa-onnx-$sherpaOnnxRevision")
val onnxRuntimeDirectory = layout.buildDirectory.dir("onnxruntime-android")
val sherpaVitsEnabled = providers.gradleProperty("enableSherpaVits")
    .map { it.toBooleanStrictOrNull() ?: error("enableSherpaVits must be true or false") }
    .orElse(true)
val sherpaOnnxSourceDir = providers.gradleProperty("sherpaOnnxSourceDir")
    .orElse(sherpaOnnxDirectory.map { it.asFile.absolutePath })
val sherpaOnnxRuntimeLibRoot = providers.gradleProperty("sherpaOnnxRuntimeLibRoot")
    .orElse(onnxRuntimeDirectory.map { it.dir("jni").asFile.absolutePath })
val sherpaOnnxRuntimeIncludeDir = providers.gradleProperty("sherpaOnnxRuntimeIncludeDir")
    .orElse(onnxRuntimeDirectory.map { it.dir("headers").asFile.absolutePath })
val onnxRuntimeNative by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    add(onnxRuntimeNative.name, libs.onnxruntime.android)
}

val prepareSherpaVitsRuntime = tasks.register("prepareSherpaVitsRuntime") {
    val sourceProvided = providers.gradleProperty("sherpaOnnxSourceDir").isPresent
    val runtimeProvided = providers.gradleProperty("sherpaOnnxRuntimeLibRoot").isPresent &&
        providers.gradleProperty("sherpaOnnxRuntimeIncludeDir").isPresent
    inputs.files(onnxRuntimeNative)
    outputs.dir(sherpaOnnxDirectory)
    outputs.dir(onnxRuntimeDirectory)
    onlyIf { sherpaVitsEnabled.get() }
    doLast {
        val source = file(sherpaOnnxSourceDir.get())
        val sourceCheckout = File(source, ".git").isDirectory
        if (!sourceProvided && !sourceCheckout) {
            delete(source)
            source.parentFile.mkdirs()
            exec {
                commandLine("git", "clone", "--no-checkout", "https://github.com/k2-fsa/sherpa-onnx.git", source)
            }
            exec {
                commandLine("git", "-C", source, "fetch", "--depth=1", "origin", sherpaOnnxRevision)
            }
            exec {
                commandLine("git", "-C", source, "checkout", "--detach", sherpaOnnxRevision)
            }
        } else if (sourceProvided && !sourceCheckout) {
            error("sherpaOnnxSourceDir is not a Git checkout")
        }
        val revision = ByteArrayOutputStream().also { output ->
            exec {
                commandLine("git", "-C", source, "rev-parse", "HEAD")
                standardOutput = output
            }
        }.toString().trim()
        check(revision == sherpaOnnxRevision) { "Sherpa-ONNX revision is not pinned" }

        if (!runtimeProvided) {
            val destination = onnxRuntimeDirectory.get().asFile
            delete(destination)
            copy {
                from(zipTree(onnxRuntimeNative.singleFile))
                include("headers/**", "jni/**")
                into(destination)
            }
        }
    }
}

android {
    namespace = "com.homoludens.citacknjiga.tts.onnx"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments("-DCITA_ENABLE_SHERPA_VITS=${sherpaVitsEnabled.get()}")
                arguments("-DSHERPA_ONNX_SOURCE_DIR=${sherpaOnnxSourceDir.get()}")
                arguments("-DCITA_ONNXRUNTIME_LIB_ROOT=${sherpaOnnxRuntimeLibRoot.get()}")
                arguments("-DCITA_ONNXRUNTIME_INCLUDE_DIR=${sherpaOnnxRuntimeIncludeDir.get()}")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Package both the available emulator ABI and the target device ABI.
            ndk {
                abiFilters.clear()
                abiFilters += "x86_64"
                abiFilters += "arm64-v8a"
            }
        }
        getByName("release") {
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("model-tools/preprocessing"))
            assets.srcDir(rootProject.file("model-tools/native"))
        }
        getByName("test") {
            resources.srcDir(rootProject.file("model-tools/reference"))
            resources.srcDir(rootProject.file("model-tools/preprocessing"))
        }
        getByName("androidTest") {
            assets.srcDir(rootProject.file("model-tools/reference"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(prepareSherpaVitsRuntime)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
    implementation(project(":core"))
    debugImplementation(libs.onnxruntime.android)
    releaseImplementation(libs.onnxruntime.android)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
