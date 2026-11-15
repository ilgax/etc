plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ilgax.utils.remote"
    testOptions.unitTests.isReturnDefaultValues = true
}

// The watch app, built from ../watch-remote/wear (signed with the key of the APK already on the watch) and
// embedded as an asset so Utils can push it to the watch. No manual copy step: merging assets builds it.
abstract class BundleWatchApk : DefaultTask() {
    @get:InputDirectory abstract val apkDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }!!.single()
        apk.copyTo(out.resolve("watch-update.apk"))
        // Version of the bundled APK for the UI, from the metadata AGP wrote next to it.
        val meta = groovy.json.JsonSlurper().parse(apkDir.get().file("output-metadata.json").asFile) as Map<*, *>
        val element = (meta["elements"] as List<*>).first() as Map<*, *>
        out.resolve("watch-update.properties")
            .writeText("versionCode=${element["versionCode"]}\nversionName=${element["versionName"]}\n")
    }
}

val bundleWatchApk = tasks.register<BundleWatchApk>("bundleWatchApk") {
    dependsOn(":wear:assembleDebug")
    apkDir.set(project(":wear").layout.buildDirectory.dir("outputs/apk/debug"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleWatchApk, BundleWatchApk::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}
