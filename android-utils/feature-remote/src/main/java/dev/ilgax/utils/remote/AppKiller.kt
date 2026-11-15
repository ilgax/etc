package dev.ilgax.utils.remote

import android.app.ActivityManager
import android.content.Context
import android.os.IBinder
import android.os.Process
import android.util.Log
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Ends another app for KILL_APP and the "close previous app" step of SWITCH_APP. */
interface AppKiller {
    /** Returns true if the app was asked to stop; a true result does not prove the process is gone. */
    fun kill(packageName: String): Boolean

    companion object {
        /** Force-stop through Shizuku when it is ready, the background kill otherwise. */
        fun create(context: Context): AppKiller =
            ShizukuAppKiller(context.applicationContext, BackgroundAppKiller(context.applicationContext))
    }
}

/** The original behaviour: only ends the process when nothing keeps it alive, and leaves it in Recents. */
class BackgroundAppKiller(private val context: Context) : AppKiller {
    override fun kill(packageName: String): Boolean = try {
        context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(packageName)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Real force-stop as the shell uid: hidden IActivityManager.forceStopPackage(String, int) reached through
 * a Shizuku-wrapped "activity" binder. Any failure, or Shizuku not being ready, defers to [fallback].
 */
class ShizukuAppKiller(private val context: Context, private val fallback: AppKiller) : AppKiller {
    private class Binding(val service: Any, val forceStop: Method)

    @Volatile private var binding: Binding? = null

    override fun kill(packageName: String): Boolean {
        if (ShizukuState.current(context) == ShizukuStatus.Ready) {
            try {
                forceStop(packageName)
                return true
            } catch (t: Throwable) {
                val root = (t as? InvocationTargetException)?.targetException ?: t
                Log.w(TAG, "forceStopPackage failed, using background kill: $root")
                binding = null
            }
        }
        return fallback.kill(packageName)
    }

    private fun forceStop(packageName: String) {
        val b = binding ?: bind().also { binding = it }
        b.forceStop.invoke(b.service, packageName, Process.myUid() / UID_PER_USER)
    }

    private fun bind(): Binding {
        HiddenApiBypass.addHiddenApiExemptions("L")
        val stub = Class.forName("android.app.IActivityManager\$Stub")
        val iface = Class.forName("android.app.IActivityManager")
        val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity"))
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val method = iface.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
        return Binding(service, method)
    }

    private companion object {
        const val TAG = "ShizukuAppKiller"
        const val UID_PER_USER = 100_000
    }
}
