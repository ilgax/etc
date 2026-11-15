plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ilgax.tabremote.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ilgax.tabremote.wear"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }

    // Fixed debug key (the one the first sideloaded build was signed with): Utils bundles this APK as an
    // update, and Android only accepts an update signed with the same certificate as the installed app.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
