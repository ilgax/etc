package dev.ilgax.utils.core.action

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import dev.ilgax.utils.core.R
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The steps that need nothing from any feature module: flow control, media, system, screen, radios and apps. */
object CoreActions : ActionProvider {
    override val id = "core"

    private const val P_MS = "ms"
    private const val P_PERCENT = "percent"
    private const val P_MODE = "mode"
    private const val P_PKG = "pkg"
    private const val P_URL = "url"

    /** Longest sum of waits one macro may contain. */
    const val MAX_TOTAL_WAIT_MS = 60_000L
    const val WAIT_ID = "flow.wait"

    private val shizuku = Requirement.of(RequirementKind.Shizuku)
    private val accessibility = Requirement.of(RequirementKind.Accessibility)
    private val writeSettingsOrShizuku = Requirement.oneOf(RequirementKind.WriteSettings, RequirementKind.Shizuku)
    private val dndAccess = Requirement.oneOf(RequirementKind.NotificationPolicy, RequirementKind.Shizuku)
    private val policyAccess = Requirement.of(RequirementKind.NotificationPolicy)

    private fun res(@androidx.annotation.StringRes id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    private fun a(
        id: String, label: Int, group: ActionGroup, icon: Int,
        params: List<ParamSpec> = emptyList(),
        requirements: List<Requirement> = emptyList(),
        requirementsFn: ((ParamValues) -> List<Requirement>)? = null,
        warnings: ((ParamValues) -> List<UiText>)? = null,
        summary: ((Context, ParamValues) -> String)? = null,
        waitMs: ((ParamValues) -> Long)? = null,
        body: suspend (Context, ParamValues) -> StepResult,
    ) = SimpleAction(id, res(label), group, icon, params, requirements, requirementsFn, warnings, summary, waitMs, body)

    private fun percentSummary(label: Int) = { c: Context, v: ParamValues -> c.getString(label, v.int(P_PERCENT)) }

    // region flow

    private val waitAction = a(
        WAIT_ID, R.string.action_wait, ActionGroup.Flow, R.drawable.ic_timer,
        params = listOf(ParamSpec.Seconds(P_MS, res(R.string.param_seconds), minMs = 500, maxMs = 30_000, initialMs = 1_000, stepMs = 500)),
        summary = { c, v -> c.getString(R.string.action_wait_summary, formatSeconds(v.int(P_MS))) },
        waitMs = { it.int(P_MS).toLong() },
    ) { _, v ->
        delay(v.int(P_MS).toLong())
        StepResult.Ok
    }

    fun formatSeconds(ms: Int): String = if (ms % 1000 == 0) (ms / 1000).toString() else "%.1f".format(java.util.Locale.ROOT, ms / 1000.0)

    // endregion

    // region media and sound

    private fun mediaKey(context: Context, code: Int): StepResult {
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return StepResult.Ok
    }

    private val ringerModes = listOf(
        ParamSpec.Option("normal", res(R.string.ringer_normal)),
        ParamSpec.Option("vibrate", res(R.string.ringer_vibrate)),
        ParamSpec.Option("silent", res(R.string.ringer_silent)),
    )

    private fun setDnd(context: Context, on: Boolean): suspend () -> StepResult = {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            try {
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                StepResult.Ok
            } catch (e: SecurityException) {
                StepResult.failed(R.string.err_dnd_access)
            }
        } else {
            val r = ShizukuShell.get(context).run("/system/bin/cmd", "notification", "set_dnd", if (on) "priority" else "off")
            when {
                r == null -> StepResult.failed(R.string.err_dnd_access)
                r.ok -> StepResult.Ok
                else -> StepResult.failed(R.string.err_command, r.output.ifBlank { "exit ${r.exitCode}" })
            }
        }
    }

    // endregion

    // region system settings

    /** Settings.System write through the "Modify system settings" permission, else a Shizuku `settings put`. */
    private suspend fun putSystemInt(context: Context, key: String, value: Int): StepResult {
        if (Settings.System.canWrite(context)) {
            return try {
                if (Settings.System.putInt(context.contentResolver, key, value)) StepResult.Ok else StepResult.failed(R.string.err_setting_rejected)
            } catch (e: SecurityException) {
                StepResult.failed(R.string.err_write_settings)
            }
        }
        val r = ShizukuShell.get(context).run("/system/bin/settings", "put", "system", key, value.toString())
            ?: return StepResult.failed(R.string.err_write_settings)
        return if (r.ok) StepResult.Ok else StepResult.failed(R.string.err_command, r.output.ifBlank { "exit ${r.exitCode}" })
    }

    private suspend fun shell(context: Context, vararg argv: String): StepResult {
        val r = ShizukuShell.get(context).run(*argv) ?: return StepResult.failed(R.string.err_shizuku)
        return if (r.ok) StepResult.Ok else StepResult.failed(R.string.err_command, r.output.ifBlank { "exit ${r.exitCode}" })
    }

    private fun global(action: Int): suspend (Context, ParamValues) -> StepResult = { _, _ ->
        when {
            A11y.service.value == null -> StepResult.failed(R.string.err_accessibility)
            A11y.performGlobalAction(action) -> StepResult.Ok
            else -> StepResult.failed(R.string.err_global_action)
        }
    }

    private val timeoutOptions = listOf(
        15_000 to R.string.timeout_15s, 30_000 to R.string.timeout_30s, 60_000 to R.string.timeout_1m,
        120_000 to R.string.timeout_2m, 300_000 to R.string.timeout_5m, 600_000 to R.string.timeout_10m,
    )

    // endregion

    // region apps

    /** Starts [intent] through the accessibility service, the same way the watch's OPEN_APP does (background starts are blocked for plain services). */
    suspend fun startActivityFromService(intent: Intent): StepResult {
        val svc = A11y.service.value ?: return StepResult.failed(R.string.err_accessibility)
        return withContext(Dispatchers.Main) {
            try {
                svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                StepResult.Ok
            } catch (e: Exception) {
                StepResult.failed(R.string.err_start_failed)
            }
        }
    }

    fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
    } catch (_: Exception) {
        pkg
    }

    private val protectedPackages = setOf("android", "com.android.systemui")

    // endregion

    override val actions: List<ActionType> = listOf(
        waitAction,

        a("media.next", R.string.action_media_next, ActionGroup.Media, R.drawable.ic_skip_next) { c, _ -> mediaKey(c, KeyEvent.KEYCODE_MEDIA_NEXT) },
        a("media.previous", R.string.action_media_previous, ActionGroup.Media, R.drawable.ic_skip_previous) { c, _ -> mediaKey(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
        a(
            "media.volume", R.string.action_media_volume, ActionGroup.Media, R.drawable.ic_volume_up,
            params = listOf(ParamSpec.Percent(P_PERCENT, res(R.string.param_percent), 50)),
            summary = percentSummary(R.string.action_media_volume_summary),
        ) { c, v ->
            val am = c.getSystemService(AudioManager::class.java)
            val index = Mappings.volumeIndex(v.int(P_PERCENT), am.getStreamMinVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
                StepResult.Ok
            } catch (e: SecurityException) {
                StepResult.failed(R.string.err_dnd_access)
            }
        },
        a("media.mute", R.string.action_media_mute, ActionGroup.Media, R.drawable.ic_volume_off) { c, _ ->
            c.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            StepResult.Ok
        },
        a("media.unmute", R.string.action_media_unmute, ActionGroup.Media, R.drawable.ic_volume_up) { c, _ ->
            c.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            StepResult.Ok
        },
        a(
            "sound.ringer", R.string.action_ringer, ActionGroup.Media, R.drawable.ic_vibration,
            params = listOf(ParamSpec.Choice(P_MODE, res(R.string.param_mode), ringerModes, "vibrate")),
            requirements = listOf(policyAccess),
            summary = { c, v -> c.getString(R.string.action_ringer_summary, ringerModes.first { it.value == v.string(P_MODE) }.label.resolve(c)) },
        ) { c, v ->
            val am = c.getSystemService(AudioManager::class.java)
            val mode = when (v.string(P_MODE)) {
                "normal" -> AudioManager.RINGER_MODE_NORMAL
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_VIBRATE
            }
            try {
                am.ringerMode = mode
                if (am.ringerMode == mode) StepResult.Ok else StepResult.failed(R.string.err_ringer_refused)
            } catch (e: SecurityException) {
                StepResult.failed(R.string.err_dnd_access)
            }
        },
        a("sound.dnd_on", R.string.action_dnd_on, ActionGroup.Media, R.drawable.ic_dnd, requirements = listOf(dndAccess)) { c, _ -> setDnd(c, true)() },
        a("sound.dnd_off", R.string.action_dnd_off, ActionGroup.Media, R.drawable.ic_dnd, requirements = listOf(dndAccess)) { c, _ -> setDnd(c, false)() },

        a("system.home", R.string.action_home, ActionGroup.System, R.drawable.ic_home, requirements = listOf(accessibility), body = global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)),
        a("system.back", R.string.action_back, ActionGroup.System, R.drawable.ic_arrow_back, requirements = listOf(accessibility), body = global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)),
        a("system.recents", R.string.action_recents, ActionGroup.System, R.drawable.ic_recents, requirements = listOf(accessibility), body = global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)),
        a("system.notifications", R.string.action_notifications, ActionGroup.System, R.drawable.ic_notifications, requirements = listOf(accessibility), body = global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)),
        a("system.quick_settings", R.string.action_quick_settings, ActionGroup.System, R.drawable.ic_tune, requirements = listOf(accessibility), body = global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)),
        a("system.screenshot", R.string.action_screenshot, ActionGroup.System, R.drawable.ic_screenshot, requirements = listOf(accessibility), body = global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)),

        a(
            "screen.brightness", R.string.action_brightness, ActionGroup.Screen, R.drawable.ic_brightness,
            params = listOf(ParamSpec.Percent(P_PERCENT, res(R.string.param_percent), 50)),
            requirements = listOf(writeSettingsOrShizuku),
            summary = percentSummary(R.string.action_brightness_summary),
        ) { c, v -> putSystemInt(c, Settings.System.SCREEN_BRIGHTNESS, Mappings.brightnessSetting(v.int(P_PERCENT))) },
        a("screen.auto_brightness_on", R.string.action_auto_brightness_on, ActionGroup.Screen, R.drawable.ic_brightness_auto, requirements = listOf(writeSettingsOrShizuku)) { c, _ ->
            putSystemInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        },
        a("screen.auto_brightness_off", R.string.action_auto_brightness_off, ActionGroup.Screen, R.drawable.ic_brightness, requirements = listOf(writeSettingsOrShizuku)) { c, _ ->
            putSystemInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        },
        a("screen.rotation_lock_on", R.string.action_rotation_lock_on, ActionGroup.Screen, R.drawable.ic_screen_rotation, requirements = listOf(writeSettingsOrShizuku)) { c, _ ->
            putSystemInt(c, Settings.System.ACCELEROMETER_ROTATION, 0)
        },
        a("screen.rotation_lock_off", R.string.action_rotation_lock_off, ActionGroup.Screen, R.drawable.ic_screen_rotation, requirements = listOf(writeSettingsOrShizuku)) { c, _ ->
            putSystemInt(c, Settings.System.ACCELEROMETER_ROTATION, 1)
        },
        a(
            "screen.timeout", R.string.action_timeout, ActionGroup.Screen, R.drawable.ic_timer,
            params = listOf(
                ParamSpec.Choice(P_MS, res(R.string.param_timeout), timeoutOptions.map { (ms, label) -> ParamSpec.Option(ms.toString(), res(label)) }, "60000"),
            ),
            requirements = listOf(writeSettingsOrShizuku),
            summary = { c, v -> c.getString(R.string.action_timeout_summary, c.getString(timeoutOptions.first { it.first == v.int(P_MS) }.second)) },
        ) { c, v -> putSystemInt(c, Settings.System.SCREEN_OFF_TIMEOUT, v.int(P_MS)) },

        a(
            "radio.wifi_on", R.string.action_wifi_on, ActionGroup.Network, R.drawable.ic_wifi, requirements = listOf(shizuku),
        ) { c, _ -> wifi(c, true) },
        a(
            "radio.wifi_off", R.string.action_wifi_off, ActionGroup.Network, R.drawable.ic_wifi, requirements = listOf(shizuku),
            warnings = { if (ActionFacts.isTrue(ActionFacts.Fact.HotspotActive)) listOf(res(R.string.warn_wifi_off_hotspot)) else emptyList() },
        ) { c, _ -> wifi(c, false) },

        a(
            "app.open", R.string.action_open_app, ActionGroup.Apps, R.drawable.ic_apps,
            params = listOf(ParamSpec.AppPackage(P_PKG, res(R.string.param_app))),
            requirements = listOf(accessibility),
            summary = { c, v -> c.getString(R.string.action_open_app_summary, appLabel(c, v.string(P_PKG))) },
        ) { c, v ->
            val intent = c.packageManager.getLaunchIntentForPackage(v.string(P_PKG))
            if (intent == null) StepResult.failed(R.string.err_app_missing) else startActivityFromService(intent)
        },
        a(
            "app.url", R.string.action_open_url, ActionGroup.Apps, R.drawable.ic_link,
            params = listOf(ParamSpec.Text(P_URL, res(R.string.param_url), url = true)),
            requirements = listOf(accessibility),
            summary = { c, v -> c.getString(R.string.action_open_url_summary, v.string(P_URL).removePrefix("https://").removePrefix("http://").take(32)) },
        ) { _, v -> startActivityFromService(Intent(Intent.ACTION_VIEW, Uri.parse(v.string(P_URL).trim()))) },
        a(
            "app.force_stop", R.string.action_force_stop, ActionGroup.Apps, R.drawable.ic_cancel,
            params = listOf(ParamSpec.AppPackage(P_PKG, res(R.string.param_app))),
            requirements = listOf(shizuku),
            summary = { c, v -> c.getString(R.string.action_force_stop_summary, appLabel(c, v.string(P_PKG))) },
        ) { c, v ->
            val pkg = v.string(P_PKG)
            if (pkg == c.packageName || pkg in protectedPackages) StepResult.failed(R.string.err_protected_app)
            else shell(c, "/system/bin/am", "force-stop", pkg)
        },
    )

    /** `svc wifi enable|disable`, falling back to `cmd wifi set-wifi-enabled`. */
    private suspend fun wifi(context: Context, on: Boolean): StepResult {
        val first = shell(context, "/system/bin/svc", "wifi", if (on) "enable" else "disable")
        if (first == StepResult.Ok) return first
        val second = shell(context, "/system/bin/cmd", "wifi", "set-wifi-enabled", if (on) "enabled" else "disabled")
        return if (second == StepResult.Ok) second else first
    }
}
