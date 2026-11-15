pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Utils"
include(":app", ":core", ":feature-speaker", ":feature-hotspot", ":feature-remote", ":feature-math")

// Pure-JVM wire protocol shared with the watch app; lives in the sibling project and is not copied.
include(":protocol")
project(":protocol").projectDir = file("../watch-remote/protocol")

// The Wear OS app is built here only to bundle its APK into :feature-remote (see that module's build file).
include(":wear")
project(":wear").projectDir = file("../watch-remote/wear")
