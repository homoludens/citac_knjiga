plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.homoludens.citacknjiga.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.androidx.room.runtime)
    kapt(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}
