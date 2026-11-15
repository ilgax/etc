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
        versionCode = 7
        versionName = "0.3.0"
        // The target tablets are arm64; keeps the ONNX Runtime native libs of other ABIs out of the APK.
        ndk { abiFilters += "arm64-v8a" }
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

    // Bundled ONNX models (feature-math) must stay uncompressed in the APK.
    androidResources.noCompress += "onnx"

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
    implementation(project(":feature-math"))

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
