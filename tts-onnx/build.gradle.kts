plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
                arguments("-DCITA_ENABLE_SHERPA_VITS=${providers.gradleProperty("enableSherpaVits").orNull == "true"}")
                providers.gradleProperty("sherpaOnnxSourceDir").orNull?.let { source ->
                    arguments("-DSHERPA_ONNX_SOURCE_DIR=$source")
                }
                providers.gradleProperty("sherpaOnnxRuntimeLibRoot").orNull?.let { root ->
                    arguments("-DCITA_ONNXRUNTIME_LIB_ROOT=$root")
                }
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

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
    implementation(project(":core"))
    debugImplementation(libs.onnxruntime.android)
    releaseImplementation(libs.onnxruntime.android)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
