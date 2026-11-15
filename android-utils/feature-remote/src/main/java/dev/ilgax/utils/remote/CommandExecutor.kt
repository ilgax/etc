package dev.ilgax.utils.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.Status
import dev.ilgax.tabremote.protocol.UpdateCodec
import dev.ilgax.tabremote.protocol.UpdateReport
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs commands. [execute] blocks (up to a few seconds) and must not be called from the main thread. */
class CommandExecutor(context: Context, private val killer: AppKiller = AppKiller.create(context)) {
    private val context = context.applicationContext
    private val prefs = RemotePrefs(this.context)
    private val main = Handler(Looper.getMainLooper())

    fun execute(command: Command, args: ByteArray = ByteArray(0)): Status = try {
        when (command) {
            Command.PING, Command.SYNC_APPS, Command.GET_OFFER -> Status.SUCCESS // GET_OFFER's grant is handled by the GATT server
            Command.HELLO -> UpdateCodec.decodeVersion(args)?.let { UpdateHost.get(context).onHello(it); Status.SUCCESS } ?: Status.FAILED
            Command.UPDATE_STATE -> UpdateReport.decode(args)?.let { UpdateHost.get(context).onReport(it); Status.SUCCESS } ?: Status.FAILED
            Command.LOCK -> lock()
            Command.PLAY_PAUSE -> playPause()
            Command.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            Command.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            Command.RING -> onMain { if (RingPlayer.toggle(context)) Status.SUCCESS else Status.FAILED }
            Command.KILL_APP -> killApp()
            Command.OPEN_APP -> openApp(args.firstOrNull()?.toInt(), switchFrom = false)
            Command.SWITCH_APP -> openApp(args.firstOrNull()?.toInt(), switchFrom = true)
        }
    } catch (e: Exception) {
        Status.FAILED
    }

    fun stopRing() = main.post { RingPlayer.stop() }

    private fun lock(): Status {
        val svc = RemoteA11y.service() ?: return Status.ACCESSIBILITY_DISABLED
        return onMain { if (RemoteA11y.lockScreen()) Status.SUCCESS else Status.FAILED }
    }

    private fun playPause(): Status {
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        return Status.SUCCESS
    }

    private fun volume(direction: Int): Status {
        context.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return Status.SUCCESS
    }

    /**
     * With the background killer this only ends the app's process if nothing (e.g. a foreground service)
     * keeps it alive, and the app stays listed in Recents; the Shizuku killer force-stops it.
     */
    private fun killApp(): Status {
        val svc = RemoteA11y.service() ?: return Status.ACCESSIBILITY_DISABLED
        return onMain {
            val pkg = closablePackage(svc, null) ?: return@onMain Status.NOTHING_TO_CLOSE
            if (!RemoteA11y.goHome()) return@onMain Status.FAILED
            scheduleKill(pkg)
            Status.SUCCESS
        }
    }

    /** Foreground package that is fine to close, or null (nothing, this app, launcher, system UI, [except]). */
    private fun closablePackage(svc: android.accessibilityservice.AccessibilityService, except: String?): String? {
        val pkg = RemoteA11y.foregroundPackage() ?: return null
        if (pkg == context.packageName || pkg == "com.android.systemui" || pkg == except || pkg in homePackages()) return null
        return pkg
    }

    private fun scheduleKill(pkg: String) {
        main.postDelayed({
            // Off the main thread: the Shizuku path makes binder calls.
            thread(name = "remote-kill") {
                try {
                    killer.kill(pkg)
                } catch (_: Exception) {
                }
            }
        }, KILL_DELAY_MS)
    }

    private fun homePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = context.packageManager
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved.map { it.activityInfo.packageName }.toSet()
    }

    /** [switchFrom] also closes the previous foreground app; having none to close is not an error. */
    private fun openApp(slot: Int?, switchFrom: Boolean): Status {
        if (slot == null || slot !in 0 until dev.ilgax.tabremote.protocol.Protocol.APP_SLOTS) return Status.FAILED
        val svc = RemoteA11y.service() ?: return Status.ACCESSIBILITY_DISABLED
        val pkg = prefs.slot(slot) ?: return Status.SLOT_EMPTY
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return Status.SLOT_EMPTY
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return onMain {
            val previous = if (switchFrom) closablePackage(svc, pkg) else null
            try {
                // Started from the accessibility service context: background starts are blocked for plain services.
                svc.startActivity(intent)
            } catch (e: Exception) {
                return@onMain Status.APP_START_FAILED
            }
            previous?.let(::scheduleKill)
            Status.SUCCESS
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return task.get(3, TimeUnit.SECONDS)
    }

    private companion object {
        const val KILL_DELAY_MS = 700L
    }
}
