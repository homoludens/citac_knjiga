plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.homoludens.citacknjiga.tts.onnx"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("model-tools/preprocessing"))
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
    implementation(libs.onnxruntime.android)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
