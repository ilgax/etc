package dev.ilgax.utils.core.action

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus

enum class RequirementKind { Shizuku, Accessibility, WriteSettings, NotificationPolicy, Bluetooth }

/** One need of a step; satisfied when any of [anyOf] is available (for example write-settings permission or Shizuku). */
class Requirement(val anyOf: List<RequirementKind>) {
    init {
        require(anyOf.isNotEmpty()) { "a requirement needs at least one way to satisfy it" }
    }

    companion object {
        fun of(kind: RequirementKind) = Requirement(listOf(kind))
        fun oneOf(vararg kinds: RequirementKind) = Requirement(kinds.toList())
    }
}

/** Whether a kind of requirement is currently available. Swappable so the checks can be tested without a device. */
fun interface RequirementEnv {
    fun isMet(kind: RequirementKind): Boolean
}

class AndroidRequirementEnv(context: Context) : RequirementEnv {
    private val app = context.applicationContext

    override fun isMet(kind: RequirementKind): Boolean = try {
        when (kind) {
            RequirementKind.Shizuku -> ShizukuState.current(app) == ShizukuStatus.Ready
            RequirementKind.Accessibility -> A11y.service.value != null
            RequirementKind.WriteSettings -> Settings.System.canWrite(app)
            RequirementKind.NotificationPolicy -> app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
            RequirementKind.Bluetooth -> app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }
    } catch (_: Throwable) {
        false
    }
}

/** Reports what a step still needs. */
class RequirementChecker(private val env: RequirementEnv) {
    /** The requirements of [action] (for these [values]) that nothing in [env] satisfies. */
    fun missing(action: ActionType, values: ParamValues): List<Requirement> =
        action.requirementsFor(values).filter { req -> req.anyOf.none { env.isMet(it) } }

    fun isMet(requirement: Requirement) = requirement.anyOf.any { env.isMet(it) }
}

/** The one screen the user can act in for each kind. */
object RequirementFix {
    fun open(context: Context, kind: RequirementKind) {
        try {
            when (kind) {
                RequirementKind.Shizuku -> ShizukuState.performAction(context, ShizukuState.current(context))
                RequirementKind.Accessibility -> A11y.openSettings(context)
                RequirementKind.WriteSettings -> context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                RequirementKind.NotificationPolicy -> context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                RequirementKind.Bluetooth -> context.startActivity(
                    Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (_: Throwable) {
            // The screen may not exist on every device; nothing more to do.
        }
    }
}
