plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.homoludens.citacknjiga.readiumspike"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 3.3.0 resolves but requires compileSdk 36; 3.1.0 is the newest
    // Readium line compatible with this repository's compileSdk 35 baseline.
    implementation("org.readium.kotlin-toolkit:readium-shared:3.1.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "fixtureRoot",
        file("../../document-epub/src/test/resources/fixtures").canonicalPath
    )
}

tasks.register("measureReadiumArtifacts") {
    doLast {
        val files = configurations.getByName("debugRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .map { it.file }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name }
        println("READIUM_SIZE unique_artifacts=${files.size}")
        files.forEach { println("READIUM_SIZE ${it.name} bytes=${it.length()}") }
        println("READIUM_SIZE total_bytes=${files.sumOf { it.length() }}")
    }
}
