package dev.ilgax.utils.remote

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

object AppCatalog {
    class App(val label: String, val packageName: String)

    fun launchableApps(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return list.map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun label(context: Context, pkg: String?): String? {
        if (pkg == null) return null
        val pm = context.packageManager
        return try {
            val info: ApplicationInfo = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            }
            info.loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun slotLabels(context: Context, prefs: RemotePrefs): List<String?> =
        List(dev.ilgax.tabremote.protocol.Protocol.APP_SLOTS) { label(context, prefs.slot(it)) }
}
