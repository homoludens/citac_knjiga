pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "citac-knjiga"

include(":app")
include(":core")
include(":tts-onnx")
include(":document-epub")
include(":document-pdf")
include(":playback-export")
