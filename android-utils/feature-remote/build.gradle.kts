plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ilgax.utils.remote"
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
}
