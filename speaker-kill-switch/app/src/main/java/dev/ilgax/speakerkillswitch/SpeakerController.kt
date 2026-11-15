package dev.ilgax.speakerkillswitch

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

/** All audio logic. Hidden APIs are reached by reflection through a Shizuku-wrapped IAudioService binder. */
object SpeakerController {
    private const val TAG = "SpeakerKill"
    private const val PREFS = "sks"
    const val K_ENABLED = "enabled"
    const val K_PAUSE = "pause_on_disconnect"
    private const val K_SAVED = "saved_speaker_idx"
    private const val K_LOG = "event_log"
    private const val K_PENDING = "pending_release"
    private const val SHELL_PKG = "com.android.shell"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val STREAM_MUSIC = AudioManager.STREAM_MUSIC
    private const val TYPE_SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private const val TYPE_SPEAKER_SAFE = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
    private const val ROLE_OUTPUT = 2 // AudioDeviceAttributes.ROLE_OUTPUT

    private val exec = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()
    private val errors = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var statusLine = "idle"
        private set
    @Volatile private var lastPauseMs = 0L
    @Volatile private var blockStatus: Map<Int, String> = emptyMap()

    private var prefsRef: SharedPreferences? = null
    private fun prefs(ctx: Context): SharedPreferences =
        prefsRef ?: ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            prefsRef = it
            synchronized(logLines) {
                it.getString(K_LOG, "")?.split('\n')?.filter { l -> l.isNotEmpty() }?.forEach { l -> logLines.add(l) }
            }
        }

    // ---------- logging ----------

    fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        val p = prefs(ctx)
        synchronized(logLines) {
            logLines.add("${timeFmt.format(Date())} $msg")
            while (logLines.size > 80) logLines.removeFirst()
            p.edit().putString(K_LOG, logLines.joinToString("\n")).apply()
        }
    }

    fun error(ctx: Context, what: String, t: Throwable?) {
        val root = if (t is InvocationTargetException) t.targetException ?: t else t
        val msg = "$what: ${root?.javaClass?.simpleName ?: ""} ${root?.message ?: ""}".trim()
        Log.e(TAG, msg, root)
        synchronized(logLines) {
            errors.add("${timeFmt.format(Date())} $msg")
            while (errors.size > 20) errors.removeFirst()
        }
        log(ctx, "ERR $msg")
    }

    // ---------- settings ----------

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(K_ENABLED, false)
    fun pauseOnDisconnect(ctx: Context) = prefs(ctx).getBoolean(K_PAUSE, true)
    fun setPauseOnDisconnect(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_PAUSE, v).apply()
    private fun savedIndex(ctx: Context) = prefs(ctx).getInt(K_SAVED, -1)

    fun setEnabled(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        prefs(app).edit().putBoolean(K_ENABLED, on).apply()
        log(app, "switch -> ${if (on) "ON" else "OFF"}")
        if (on) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, KillSwitchService::class.java))
            } catch (t: Throwable) {
                error(app, "startForegroundService", t)
                enforce(app, "switch on (no service)", true)
            }
        } else {
            app.stopService(Intent(app, KillSwitchService::class.java))
            exec.execute { doRelease(app) }
        }
    }

    // ---------- Shizuku ----------

    fun shizukuState(ctx: Context): ShizukuState = try {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ShizukuState.READY
            else ShizukuState.NEEDS_PERMISSION
        } else {
            val installed = try {
                ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
            } catch (e: PackageManager.NameNotFoundException) { false }
            if (installed) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
    } catch (t: Throwable) {
        ShizukuState.NOT_RUNNING
    }

    @Volatile private var hidden: Hidden? = null
    @Volatile private var hiddenFailure: String? = null

    /** Called when the Shizuku binder dies so the wrapped service is rebuilt next time. */
    fun invalidateBinder() { hidden = null }

    private fun hidden(ctx: Context): Hidden? {
        hidden?.let { return it }
        return try {
            val h = Hidden()
            hidden = h
            hiddenFailure = null
            h
        } catch (t: Throwable) {
            val m = "bind hidden APIs: ${(t as? InvocationTargetException)?.targetException?.message ?: t.message}"
            if (m != hiddenFailure) { hiddenFailure = m; error(ctx, "bind hidden APIs", t) }
            null
        }
    }

    /** Reflection bindings + the shell-wrapped IAudioService instance. */
    private class Hidden {
        val service: Any
        private val cAda: Class<*>
        private val adaCtor: java.lang.reflect.Constructor<*>
        private val cVi: Class<*>
        private val viBuilderCtor: java.lang.reflect.Constructor<*>
        private val bSetIndex: Method
        private val bBuild: Method
        private val viGetIndex: Method
        private val mSetNonDefault: Method
        private val mRemoveNonDefault: Method
        private val mGetStrategies: Method
        private val mSetDevVol: Method
        private val mGetDevVol: Method
        private val strategyGetId: Method

        init {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val cService = Class.forName("android.media.IAudioService")
            val cStub = Class.forName("android.media.IAudioService\$Stub")
            cAda = Class.forName("android.media.AudioDeviceAttributes")
            adaCtor = cAda.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            cVi = Class.forName("android.media.VolumeInfo")
            val cViB = Class.forName("android.media.VolumeInfo\$Builder")
            viBuilderCtor = cViB.getConstructor(Int::class.javaPrimitiveType)
            bSetIndex = cViB.getMethod("setVolumeIndex", Int::class.javaPrimitiveType)
            bBuild = cViB.getMethod("build")
            viGetIndex = cVi.getMethod("getVolumeIndex")
            val i = Int::class.javaPrimitiveType
            mSetNonDefault = cService.getMethod("setDeviceAsNonDefaultForStrategy", i, cAda)
            mRemoveNonDefault = cService.getMethod("removeDeviceAsNonDefaultForStrategy", i, cAda)
            mGetStrategies = cService.getMethod("getAudioProductStrategies")
            mSetDevVol = cService.getMethod("setDeviceVolume", cVi, cAda, String::class.java)
            mGetDevVol = cService.getMethod("getDeviceVolume", cVi, cAda, String::class.java)
            strategyGetId = Class.forName("android.media.audiopolicy.AudioProductStrategy").getMethod("getId")
            val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("audio"))
            service = cStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        }

        fun ada(type: Int, address: String): Any = adaCtor.newInstance(ROLE_OUTPUT, type, address)
        fun strategyIds(): List<Int> =
            (mGetStrategies.invoke(service) as List<*>).map { strategyGetId.invoke(it) as Int }
        fun setNonDefault(strategy: Int, ada: Any) = mSetNonDefault.invoke(service, strategy, ada) as Int
        fun removeNonDefault(strategy: Int, ada: Any) = mRemoveNonDefault.invoke(service, strategy, ada) as Int
        private fun vi(index: Int): Any = bBuild.invoke(viBuilderCtor.newInstance(STREAM_MUSIC).also { bSetIndex.invoke(it, index) })!!
        fun setDeviceVolume(ada: Any, index: Int) { mSetDevVol.invoke(service, vi(index), ada, SHELL_PKG) }
        fun getDeviceVolume(ada: Any): Int {
            val res = mGetDevVol.invoke(service, vi(0), ada, SHELL_PKG)
            return viGetIndex.invoke(res) as Int
        }
    }

    // ---------- device helpers ----------

    private fun speakerDevices(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type == TYPE_SPEAKER || it.type == TYPE_SPEAKER_SAFE }

    /** (type, address) pairs to block; the plain speaker is always included even if not enumerated. */
    private fun speakerTargets(am: AudioManager): List<Pair<Int, String>> {
        val l = speakerDevices(am).map { it.type to (it.address ?: "") }.toMutableList()
        if (l.none { it.first == TYPE_SPEAKER }) l.add(TYPE_SPEAKER to "")
        return l.distinct()
    }

    private fun mediaDevices(am: AudioManager): List<AudioDeviceInfo> = try {
        am.getAudioDevicesForAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        )
    } catch (t: Throwable) { emptyList() }

    private fun speakerActive(am: AudioManager) =
        mediaDevices(am).any { it.type == TYPE_SPEAKER || it.type == TYPE_SPEAKER_SAFE }

    private fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER || type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
        type == AudioDeviceInfo.TYPE_HEARING_AID

    fun isBluetoothDevice(d: AudioDeviceInfo) = isBluetooth(d.type)

    private fun speakerMusicIndex(ctx: Context, am: AudioManager, h: Hidden?, active: Boolean): Int? {
        if (h != null) {
            try {
                return h.getDeviceVolume(h.ada(TYPE_SPEAKER, speakerDevices(am).firstOrNull { it.type == TYPE_SPEAKER }?.address ?: ""))
            } catch (t: Throwable) { error(ctx, "getDeviceVolume(speaker)", t) }
        }
        if (active) return am.getStreamVolume(STREAM_MUSIC)
        val v = Settings.System.getInt(ctx.contentResolver, "volume_music_speaker", -1)
        return if (v >= 0) v else null
    }

    // ---------- enforce / release ----------

    /** Idempotent. [full] also (re)applies the routing block; volume-only events pass false. */
    fun enforce(ctx: Context, reason: String, full: Boolean) {
        val app = ctx.applicationContext
        exec.execute {
            try { doEnforce(app, reason, full) } catch (t: Throwable) { error(app, "enforce($reason)", t) }
        }
    }

    private fun doEnforce(ctx: Context, reason: String, full: Boolean) {
        if (!isEnabled(ctx)) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val state = shizukuState(ctx)
        val h = if (state == ShizukuState.READY) hidden(ctx) else null
        val notes = ArrayList<String>()
        if (state != ShizukuState.READY) notes.add("Shizuku: $state (volume-only mode)")
        else if (h == null) notes.add("hidden API bind failed")

        if (h != null && (full || blockStatus.isEmpty())) {
            val res = LinkedHashMap<Int, String>()
            try {
                val targets = speakerTargets(am)
                for (s in h.strategyIds()) {
                    res[s] = targets.joinToString(" ") { (type, addr) ->
                        val code = try { h.setNonDefault(s, h.ada(type, addr)).toString() } catch (t: Throwable) {
                            error(ctx, "setDeviceAsNonDefaultForStrategy($s,type=$type)", t); "EXC"
                        }
                        "t$type=$code"
                    }
                }
            } catch (t: Throwable) { error(ctx, "getAudioProductStrategies", t) }
            blockStatus = res
            log(ctx, "block applied ($reason): ${res.size} strategies, " +
                "failures=${res.values.count { v -> v.split(' ').any { !it.endsWith("=0") } }}")
        }

        val active = speakerActive(am)
        val cur = speakerMusicIndex(ctx, am, h, active)
        var saved = savedIndex(ctx)
        if (cur == null) {
            notes.add("speaker index unknown")
        } else {
            if (saved < 0 && cur > 0) {
                prefs(ctx).edit().putInt(K_SAVED, cur).apply()
                saved = cur
                log(ctx, "saved speaker music index $cur")
            }
            if (cur != 0) {
                var done = false
                if (h != null && !active) {
                    try {
                        h.setDeviceVolume(h.ada(TYPE_SPEAKER, speakerDevices(am).firstOrNull { it.type == TYPE_SPEAKER }?.address ?: ""), 0)
                        done = true
                    } catch (t: Throwable) { error(ctx, "setDeviceVolume(speaker,0)", t) }
                }
                if (active) {
                    try { am.setStreamVolume(STREAM_MUSIC, 0, 0); done = true } catch (t: Throwable) { error(ctx, "setStreamVolume(0)", t) }
                }
                log(ctx, "zeroed speaker music $cur->0 via ${if (active) "setStreamVolume" else "setDeviceVolume"} ok=$done ($reason)")
                if (!done) notes.add("could not zero speaker (active=$active)")
            }
        }
        statusLine = "ON | Shizuku ${state.name} | speaker " + (if (active) "ACTIVE" else "blocked") +
            (if (notes.isEmpty()) "" else " | " + notes.joinToString("; "))
        KillSwitchService.refreshNotification(ctx)
    }

    private fun doRelease(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val state = shizukuState(ctx)
            val h = if (state == ShizukuState.READY) hidden(ctx) else null
            if (h != null) {
                try {
                    val targets = speakerTargets(am)
                    val ids = h.strategyIds()
                    for (s in ids) for ((type, addr) in targets) {
                        try { h.removeNonDefault(s, h.ada(type, addr)) } catch (t: Throwable) {
                            error(ctx, "removeDeviceAsNonDefaultForStrategy($s,type=$type)", t)
                        }
                    }
                    prefs(ctx).edit().remove(K_PENDING).apply()
                    log(ctx, "block removed for ${ids.size} strategies")
                } catch (t: Throwable) { error(ctx, "release block", t) }
            } else {
                prefs(ctx).edit().putBoolean(K_PENDING, true).apply()
                error(ctx, "release: routing block NOT removed, Shizuku unavailable ($state); will retry when Shizuku is ready", null)
            }
            blockStatus = emptyMap()
            val saved = savedIndex(ctx)
            if (saved >= 0) {
                var ok = false
                try {
                    if (speakerActive(am)) { am.setStreamVolume(STREAM_MUSIC, saved, 0); ok = true }
                    else if (h != null) {
                        h.setDeviceVolume(h.ada(TYPE_SPEAKER, speakerDevices(am).firstOrNull { it.type == TYPE_SPEAKER }?.address ?: ""), saved)
                        ok = true
                    }
                } catch (t: Throwable) { error(ctx, "restore volume", t) }
                if (ok) { prefs(ctx).edit().remove(K_SAVED).apply(); log(ctx, "restored speaker music index $saved") }
                else log(ctx, "ERR could not restore index $saved; kept saved value")
            }
            statusLine = "OFF"
        } catch (t: Throwable) { error(ctx, "release", t) }
        KillSwitchService.refreshNotification(ctx)
    }

    /** If an OFF could not remove the routing block, redo the release once Shizuku is ready. */
    fun retryPendingRelease(ctx: Context) {
        val app = ctx.applicationContext
        if (isEnabled(app) || !prefs(app).getBoolean(K_PENDING, false)) return
        if (shizukuState(app) != ShizukuState.READY) return
        log(app, "retrying pending release")
        exec.execute { doRelease(app) }
    }

    fun pauseMedia(ctx: Context, why: String) {
        val now = System.currentTimeMillis()
        if (now - lastPauseMs < 1500) return
        lastPauseMs = now
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            log(ctx, "pause dispatched ($why)")
        } catch (t: Throwable) { error(ctx, "dispatchMediaKeyEvent", t) }
    }

    // ---------- diagnostics ----------

    /** Blocking (binder calls); call off the main thread. */
    fun diagnostics(ctx: Context): String {
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        val sb = StringBuilder()
        val state = shizukuState(app)
        sb.append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Switch: ${if (isEnabled(app)) "ON" else "OFF"}  Shizuku: $state  status: $statusLine\n")
        sb.append("\nOutput devices:\n")
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
            sb.append("  [${it.id}] ${typeName(it.type)}(${it.type}) '${it.productName}' addr='${it.address}'\n")
        }
        sb.append("\nMedia route: ")
        val md = mediaDevices(am)
        sb.append(if (md.isEmpty()) "(none/unknown)" else md.joinToString { "${typeName(it.type)}(${it.type}) '${it.productName}'" }).append('\n')
        sb.append("\nStrategy block status (code 0 = ok; tN = device type N):\n")
        if (blockStatus.isEmpty()) sb.append("  (not applied yet)\n")
        blockStatus.forEach { (s, v) -> sb.append("  strategy $s: $v\n") }
        val h = if (state == ShizukuState.READY) hidden(app) else null
        val active = speakerActive(am)
        val cur = try { speakerMusicIndex(app, am, h, active)?.toString() ?: "?" } catch (t: Throwable) { "err ${t.message}" }
        sb.append("\nSpeaker music index: current=$cur saved=${savedIndex(app).let { if (it < 0) "-" else it.toString() }} speakerActive=$active\n")
        sb.append("Stream music now: ${am.getStreamVolume(STREAM_MUSIC)}/${am.getStreamMaxVolume(STREAM_MUSIC)}  " +
            "Settings volume_music_speaker=${Settings.System.getInt(app.contentResolver, "volume_music_speaker", -1)}\n")
        sb.append("\nLast errors:\n")
        synchronized(logLines) {
            if (errors.isEmpty()) sb.append("  (none)\n")
            errors.reversed().forEach { sb.append("  $it\n") }
            sb.append("\nEvent log (newest first):\n")
            logLines.reversed().take(60).forEach { sb.append("  $it\n") }
        }
        return sb.toString()
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE_$t"
    }
}
