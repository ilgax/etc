package dev.ilgax.utils.math

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.ilgax.utils.math.recognition.InstalledModel
import dev.ilgax.utils.math.recognition.ModelManifest
import dev.ilgax.utils.math.recognition.ModelStore
import dev.ilgax.utils.math.recognition.Recognition
import dev.ilgax.utils.math.recognition.RunnerOptions
import dev.ilgax.utils.math.recognition.VedRunner
import dev.ilgax.utils.math.recognition.toPixelImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Model files on disk (the bundled model is copied out of the APK assets once) and the warm ONNX session.
 * Process-wide: the session is created on demand (and warmed with a dummy run) as soon as a Solve starts
 * ([hold]), outlives the Solve activity and is released [IDLE_MS] after the last use.
 */
class Recognizer private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = MathPrefs.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val store = ModelStore(File(app.filesDir, "math/models"))

    private var runner: VedRunner? = null
    private var runnerModelId: String? = null
    private var holders = 0
    private var idleJob: Job? = null

    private val _models = MutableStateFlow<List<InstalledModel>>(emptyList())
    val models: StateFlow<List<InstalledModel>> = _models.asStateFlow()

    /** Bundled model plus imported ones. Copies the bundled files out of the APK the first time. */
    fun refreshModels(): List<InstalledModel> {
        val list = listOfNotNull(bundled()) + store.list()
        _models.value = list
        return list
    }

    fun selected(): InstalledModel? {
        val all = _models.value.ifEmpty { refreshModels() }
        return all.firstOrNull { it.id == prefs.selectedModel.value } ?: all.firstOrNull()
    }

    private fun bundled(): InstalledModel? = try {
        val target = File(app.filesDir, "math/bundled/$BUNDLED_ASSET")
        val names = app.assets.list("models/$BUNDLED_ASSET").orEmpty()
        // Copy once per app install/update; the stamp file records which install the copy belongs to.
        val stamp = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        val stampFile = File(target, ".stamp")
        val upToDate = names.isNotEmpty() && stampFile.isFile && stampFile.readText() == stamp && names.all { File(target, it).isFile }
        if (!upToDate) {
            target.deleteRecursively()
            target.mkdirs()
            for (n in names) app.assets.open("models/$BUNDLED_ASSET/$n").use { i -> File(target, n).outputStream().use { o -> i.copyTo(o) } }
            stampFile.writeText(stamp)
        }
        InstalledModel(ModelStore.BUNDLED_ID, target, ModelManifest.load(target), bundled = true)
    } catch (t: Throwable) {
        null
    }

    /** Imports a zip (SAF stream). Throws ManifestException with a readable message. */
    suspend fun import(input: java.io.InputStream): InstalledModel = withContext(Dispatchers.IO) {
        val m = store.importZip(input)
        refreshModels()
        m
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { if (runnerModelId == id) closeRunner() }
        store.delete(id)
        if (prefs.selectedModel.value == id) prefs.setSelectedModel(ModelStore.BUNDLED_ID)
        refreshModels()
    }

    fun select(id: String) {
        prefs.setSelectedModel(id)
    }

    /**
     * Keep the session alive while the overlay is open; creates and warms it in the background, in parallel
     * with the Air Command close delay and the capture. Marks [Stage.SessionReady] on [timings].
     */
    fun hold(timings: SolveTimings? = null) {
        synchronized(this) { holders++; idleJob?.cancel() }
        scope.launch {
            runCatching {
                mutex.withLock {
                    val fresh = runner == null
                    val r = ensureRunner()
                    timings?.mark(Stage.SessionReady)
                    if (fresh) {
                        val t = System.nanoTime()
                        r.warmUp()
                        Log.i(SolveTimings.TAG, "model warm-up ${(System.nanoTime() - t) / 1_000_000} ms")
                    }
                }
            }.onFailure { Log.w(SolveTimings.TAG, "model session failed", it) }
        }
    }

    fun unhold() {
        synchronized(this) { holders = (holders - 1).coerceAtLeast(0) }
        scheduleIdleClose()
    }

    suspend fun recognize(crop: Bitmap, timings: SolveTimings? = null): Recognition = withContext(Dispatchers.Default) {
        val pixels = crop.toPixelImage()
        mutex.withLock {
            try {
                val r = ensureRunner()
                timings?.mark(Stage.SessionReady) // no-op when the early warm-up already marked it
                r.recognize(pixels) { timings?.mark(Stage.EncoderDone) }.also {
                    timings?.tokens = it.tokenIds.size
                    timings?.mark(Stage.DecoderDone)
                }
            } finally {
                scheduleIdleClose()
            }
        }
    }

    private fun ensureRunner(): VedRunner {
        val model = selected() ?: throw IllegalStateException("No recognition model is installed")
        runner?.takeIf { runnerModelId == model.id }?.let { return it }
        closeRunner()
        val options = RunnerOptions(xnnpack = prefs.xnnpack.value, log = { Log.w(SolveTimings.TAG, it) })
        return VedRunner(model.manifest, model.dir, options).also { runner = it; runnerModelId = model.id }
    }

    private fun closeRunner() {
        runner?.close()
        runner = null
        runnerModelId = null
    }

    private fun scheduleIdleClose() {
        synchronized(this) {
            idleJob?.cancel()
            if (holders > 0) return
            idleJob = scope.launch {
                delay(IDLE_MS)
                mutex.withLock { if (synchronized(this@Recognizer) { holders == 0 }) closeRunner() }
            }
        }
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L
        const val BUNDLED_ASSET = "p2t-mfr-1.5-int8"

        @Volatile private var instance: Recognizer? = null
        fun get(context: Context): Recognizer = instance ?: synchronized(this) { instance ?: Recognizer(context).also { instance = it } }
    }
}
