import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.homoludens.citacknjiga"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "com.homoludens.citacknjiga"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "MODEL_RELEASE_URL", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir(rootProject.file("document-epub/src/test/resources/fixtures"))
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("standard") {
            dimension = "distribution"
            val approvedUrl = providers.gradleProperty("MODEL_RELEASE_URL").orNull
                ?.takeIf { providers.gradleProperty("MODEL_RELEASE_URL_APPROVED").orNull == "true" }
                .orEmpty()
            buildConfigField("String", "MODEL_RELEASE_URL", "\"${approvedUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
            buildConfigField("String", "DISTRIBUTION", "\"standard\"")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("String", "MODEL_RELEASE_URL", "\"\"")
            applicationIdSuffix = ".fdroid"
            versionNameSuffix = "-fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"fdroid\"")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("Boolean", "VERBOSE_DIAGNOSTICS", "true")
            ndk {
                abiFilters.clear()
                abiFilters += "x86_64"
                abiFilters += "arm64-v8a"
            }
        }
        release {
            isMinifyEnabled = false
            buildConfigField("Boolean", "VERBOSE_DIAGNOSTICS", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

val verifyOfflineReleaseManifests = tasks.register("verifyOfflineReleaseManifests") {
    dependsOn("processStandardReleaseMainManifest", "processFdroidReleaseMainManifest")
    doLast {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val prohibitedPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
        val variants = listOf("standardRelease", "fdroidRelease")
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
        variants.forEach { variant ->
            val taskNameVariant = variant.replaceFirstChar(Char::uppercaseChar)
            val manifest = layout.buildDirectory.file(
                "intermediates/merged_manifests/$variant/process${taskNameVariant}Manifest/AndroidManifest.xml",
            ).get().asFile
            check(manifest.isFile) { "Merged manifest was not produced for $variant: ${manifest.path}" }
            val document = parser.parse(manifest)
            val permissions = (0 until document.getElementsByTagName("*").length)
                .map { index -> document.getElementsByTagName("*").item(index) }
                .filter { it.nodeName == "uses-permission" || it.nodeName.startsWith("uses-permission-") }
                .map { it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue }
                .filterNotNull()
            check(permissions.none(prohibitedPermissions::contains)) {
                "$variant declares a routine network permission: ${permissions.intersect(prohibitedPermissions)}"
            }
            println("offline merged manifest verified: $variant (${permissions.size} permissions; no routine network permission)")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(project(":core"))
    implementation(project(":tts-onnx"))
    implementation(project(":document-epub"))
    implementation(project(":document-pdf"))
    implementation(project(":playback-export"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
