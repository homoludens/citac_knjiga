plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val pdfQualificationReport = rootProject.file("pdf-qualification/qualification-report.json")
val pdfQualificationPassed = runCatching {
    val report = pdfQualificationReport.readText()
    listOf(
        Regex("\\\"qualification\\\"\\s*:\\s*\\\"pass\\\""),
        Regex("\\\"selected_candidate\\\"\\s*:\\s*\\\"com.tom-roush:pdfbox-android:2.0.27.0\\\""),
        Regex("\\\"production_pdf_enabled\\\"\\s*:\\s*true"),
        Regex("\\\"33\\\"\\s*:\\s*\\{[^}]*\\\"status\\\"\\s*:\\s*\\\"passed\\\"[^}]*\\\"gating\\\"\\s*:\\s*true"),
        Regex("\\\"35\\\"\\s*:\\s*\\{[^}]*\\\"status\\\"\\s*:\\s*\\\"passed\\\"[^}]*\\\"gating\\\"\\s*:\\s*true"),
    ).all { it.containsMatchIn(report) }
}.getOrDefault(false)

android {
    namespace = "com.homoludens.citacknjiga.document.pdf"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("Boolean", "PDF_QUALIFIED", pdfQualificationPassed.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

kotlin { jvmToolchain(libs.versions.jvm.get().toInt()) }

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.pdfbox.android)
    implementation(libs.bcprov.jdk15to18)
    implementation(libs.bcpkix.jdk15to18)
    implementation(libs.bcutil.jdk15to18)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
