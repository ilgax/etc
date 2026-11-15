package dev.ilgax.utils.remote.macros

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import dev.ilgax.tabremote.protocol.Status
import dev.ilgax.utils.core.action.ActionRegistry
import dev.ilgax.utils.remote.R
import dev.ilgax.utils.remote.RemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Process-wide macros: the store, the run history and the runner, plus the two entry points (the watch's RUN_MACRO and
 * the local Run button). Lives in :feature-remote because the watch remote is the only thing that triggers macros; the
 * steps themselves come from the [ActionRegistry], so this module does not know the hotspot or speaker modules.
 */
class MacroEngine private constructor(context: Context) {
    private val app = context.applicationContext
    val registry: ActionRegistry = ActionRegistry.shared

    val store = MacroStore(File(app.filesDir, "macros.json")) {
        // Edited on the tablet: tell a subscribed watch to sync (a content-free ping, the list itself is only read authenticated).
        RemoteService.instance?.notifyMacros()
    }
    val log = RunLog(File(app.filesDir, "macro_runs.json"))

    val runner = MacroRunner(
        context = app,
        registry = registry,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        log = log,
        text = { it.resolve(app) },
        summarize = { action, values -> action.summary(app, values) },
        onFinished = { record -> if (record.origin == Origin.Watch && !record.ok) postFailure(record) },
    )

    /** RUN_MACRO from the watch. Answers at once: SUCCESS means "started", the run continues in the background. */
    fun runFromWatch(id: Int): Status {
        val macro = store.get(id) ?: return Status.MACRO_NOT_FOUND
        if (macro.steps.isEmpty()) return Status.MACRO_NOT_FOUND
        return when (runner.trigger(macro, Origin.Watch)) {
            TriggerResult.Started -> Status.SUCCESS
            TriggerResult.Busy -> Status.MACRO_BUSY
            is TriggerResult.Invalid -> Status.FAILED
        }
    }

    private fun postFailure(record: RunRecord) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val step = record.steps.firstOrNull { it.index + 1 == record.failedStep } ?: return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.macro_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val text = app.getString(R.string.macro_failed_text, record.failedStep, step.summary, step.message ?: app.getString(R.string.remote_res_failed))
        nm.notify(
            NOTIFICATION_BASE + record.macroId,
            android.app.Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle(app.getString(R.string.macro_failed_title, record.macroName))
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "macro_failures"
        private const val NOTIFICATION_BASE = 1000

        @Volatile private var instance: MacroEngine? = null

        fun get(context: Context): MacroEngine =
            instance ?: synchronized(this) { instance ?: MacroEngine(context).also { instance = it } }
    }
}
