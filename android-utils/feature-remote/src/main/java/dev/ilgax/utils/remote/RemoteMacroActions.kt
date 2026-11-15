package dev.ilgax.utils.remote

import android.content.Context
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.tabremote.protocol.Status
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.action.ActionGroup
import dev.ilgax.utils.core.action.ActionProvider
import dev.ilgax.utils.core.action.ActionType
import dev.ilgax.utils.core.action.ParamSpec
import dev.ilgax.utils.core.action.Requirement
import dev.ilgax.utils.core.action.RequirementKind
import dev.ilgax.utils.core.action.SimpleAction
import dev.ilgax.utils.core.action.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.ilgax.utils.core.R as CoreR

/**
 * The watch remote's own commands as macro steps. They run through the same [CommandExecutor] the watch uses, so
 * "close current app" force-stops through Shizuku when it is ready, exactly as before.
 */
class RemoteMacroActions(context: Context) : ActionProvider {
    private val executor = CommandExecutor(context)
    override val id = "remote"

    private val accessibility = Requirement.of(RequirementKind.Accessibility)

    private fun statusToResult(status: Status): StepResult = when (status) {
        Status.SUCCESS -> StepResult.Ok
        Status.ACCESSIBILITY_DISABLED -> StepResult.failed(CoreR.string.err_accessibility)
        Status.NOTHING_TO_CLOSE -> StepResult.skipped(R.string.macro_nothing_to_close)
        Status.SLOT_EMPTY -> StepResult.failed(R.string.remote_res_slot_empty)
        Status.APP_START_FAILED -> StepResult.failed(R.string.remote_res_start_failed)
        else -> StepResult.failed(R.string.remote_res_failed)
    }

    private suspend fun exec(command: Command, args: ByteArray = ByteArray(0)): StepResult =
        statusToResult(withContext(Dispatchers.IO) { executor.execute(command, args) })

    private fun a(
        id: String, label: Int, icon: Int, command: Command,
        requirements: List<Requirement> = emptyList(),
        after: suspend () -> Unit = {},
    ): ActionType = SimpleAction(id, UiText.Res(label), ActionGroup.WatchRemote, icon, requirements = requirements) { _, _ ->
        exec(command).also { if (it == StepResult.Ok) after() }
    }

    private fun slotAction(id: String, label: Int, summary: Int, command: Command): ActionType = SimpleAction(
        id, UiText.Res(label), ActionGroup.WatchRemote, CoreR.drawable.ic_apps,
        params = listOf(ParamSpec.AppSlot("slot", UiText.Res(CoreR.string.param_slot), Protocol.APP_SLOTS)),
        requirements = listOf(accessibility),
        summaryFn = { c, v -> c.getString(summary, v.int("slot") + 1) },
    ) { _, v -> exec(command, byteArrayOf(v.int("slot").toByte())) }

    override val actions: List<ActionType> = listOf(
        a("remote.lock", R.string.macro_act_lock, CoreR.drawable.ic_lock, Command.LOCK, listOf(accessibility)),
        a("remote.play_pause", R.string.macro_act_play_pause, CoreR.drawable.ic_play_pause, Command.PLAY_PAUSE),
        a("remote.volume_up", R.string.macro_act_volume_up, CoreR.drawable.ic_volume_up, Command.VOLUME_UP),
        a("remote.volume_down", R.string.macro_act_volume_down, CoreR.drawable.ic_volume_down, Command.VOLUME_DOWN),
        a("remote.ring", R.string.macro_act_ring, CoreR.drawable.ic_notifications, Command.RING),
        // The kill is scheduled 700 ms after going Home; wait it out so a following step is not caught by it.
        a("remote.close_app", R.string.macro_act_close_app, CoreR.drawable.ic_cancel, Command.KILL_APP, listOf(accessibility)) { delay(CLOSE_SETTLE_MS) },
        slotAction("remote.open_slot", R.string.macro_act_open_slot, R.string.macro_act_open_slot_summary, Command.OPEN_APP),
        slotAction("remote.switch_slot", R.string.macro_act_switch_slot, R.string.macro_act_switch_slot_summary, Command.SWITCH_APP),
    )

    private companion object {
        const val CLOSE_SETTLE_MS = 900L
    }
}
