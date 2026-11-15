package dev.ilgax.utils.remote

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager

/** Non-UI actions and checks shared by the home card, the detail screen and the module. */
internal object RemoteControl {
    enum class PairStart { Started, MissingPrerequisites }

    fun hasBluetoothPermission(context: Context) = RemoteService.hasBluetoothPermissions(context)

    fun bluetoothOn(context: Context): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == LockAccessibilityService::class.java.name
        }
    }

    fun checks(context: Context) = SetupChecks(
        bluetoothPermission = hasBluetoothPermission(context),
        notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        bluetoothOn = bluetoothOn(context),
        accessibility = accessibilityEnabled(context),
        battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )

    /** Same sequence as the old Start/Stop button. Returns false if the system refused to start the service. */
    fun setListening(context: Context, on: Boolean): Boolean {
        val prefs = RemotePrefs(context)
        return if (on) {
            if (!hasBluetoothPermission(context)) {
                RemoteState.service = RemoteState.Service.NO_PERMISSION
                return false
            }
            prefs.runInBackground = true
            RemoteService.start(context)
        } else {
            prefs.runInBackground = false
            context.stopService(Intent(context, RemoteService::class.java))
            true
        }
    }

    fun startPairing(context: Context): PairStart {
        if (!hasBluetoothPermission(context) || !bluetoothOn(context)) return PairStart.MissingPrerequisites
        RemotePrefs(context).runInBackground = true
        val svc = RemoteService.instance
        if (svc != null) svc.startPairing() else RemoteService.start(context, RemoteService.ACTION_PAIR)
        return PairStart.Started
    }

    fun unpair(context: Context) {
        KeyVault().delete()
        RemotePrefs(context).paired = false
        RemoteState.poke()
    }

    fun isPaired(context: Context) = RemotePrefs(context).paired && KeyVault().has()
}
