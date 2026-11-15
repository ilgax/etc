package dev.ilgax.utils.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (RemotePrefs(context).runInBackground && RemoteService.hasBluetoothPermissions(context)) {
            RemoteService.start(context)
        }
    }
}
