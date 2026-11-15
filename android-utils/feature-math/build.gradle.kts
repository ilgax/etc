plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ilgax.utils.math"
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    // The ONNX files must stay uncompressed so they can be read straight from the APK.
    androidResources.noCompress += "onnx"
    testOptions.unitTests {
        isReturnDefaultValues = true
        isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.robolectric)
}

// Lets restricted build machines point Robolectric's runtime jar download at a mirror (see :app).
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectric.repo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
        systemProperty("robolectric.dependency.repo.id", "mirror")
    }
}
