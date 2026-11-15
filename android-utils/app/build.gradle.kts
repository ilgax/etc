plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.ilgax.utils"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ilgax.utils"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions.unitTests {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
    }
}

// Lets restricted build machines point Robolectric's runtime jar download at a mirror
// (./gradlew -Probolectric.repo=https://mirror/maven2). Unset on a normal machine.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectric.repo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
        systemProperty("robolectric.dependency.repo.id", "mirror")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":feature-speaker"))
    implementation(project(":feature-hotspot"))
    implementation(project(":feature-remote"))

    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(project(":protocol"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
