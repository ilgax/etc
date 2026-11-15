plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ilgax.utils.core"
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    api(libs.activity.compose)
    api(libs.lifecycle.runtime.compose)
    api(libs.core.ktx)
    api(libs.coroutines.android)
    api(libs.shizuku.api)
    api(libs.shizuku.provider)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
