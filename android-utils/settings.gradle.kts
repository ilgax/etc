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
include(":app", ":core", ":feature-speaker", ":feature-hotspot", ":feature-remote")

// Pure-JVM wire protocol shared with the watch app; lives in the sibling project and is not copied.
include(":protocol")
project(":protocol").projectDir = file("../watch-remote/protocol")
