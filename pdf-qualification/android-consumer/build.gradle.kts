plugins {
    id("com.android.library") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.10"
}

group = "com.homoludens.citacknjiga.qualification"
version = "1.0"

android {
    namespace = "com.homoludens.citacknjiga.pdfqualification"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

kotlin { jvmToolchain(21) }

dependencies {
    val candidateEnabled = providers.gradleProperty("pdfboxCandidate").orElse("true").get().toBoolean()
    if (candidateEnabled) {
        implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    } else {
        // Keep the compile classpath for the baseline while excluding runtime bytes.
        compileOnly("com.tom-roush:pdfbox-android:2.0.27.0")
        androidTestCompileOnly("com.tom-roush:pdfbox-android:2.0.27.0")
    }
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
