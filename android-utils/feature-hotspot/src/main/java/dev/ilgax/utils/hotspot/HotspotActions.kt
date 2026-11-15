package dev.ilgax.utils.hotspot

import android.content.Context
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.action.ActionFacts
import dev.ilgax.utils.core.action.ActionGroup
import dev.ilgax.utils.core.action.ActionProvider
import dev.ilgax.utils.core.action.ActionType
import dev.ilgax.utils.core.action.Requirement
import dev.ilgax.utils.core.action.RequirementKind
import dev.ilgax.utils.core.action.SimpleAction
import dev.ilgax.utils.core.action.StepResult
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus

/** Macro steps of the hotspot module: on, off, toggle through its existing controller and Shizuku UserService. */
class HotspotActions(context: Context) : ActionProvider {
    private val app = context.applicationContext
    override val id = "hotspot"

    init {
        // Lets the core's Wi-Fi off step warn without knowing this module. Reads the last polled state.
        ActionFacts.provide(ActionFacts.Fact.HotspotActive) {
            HotspotController.get(app).state.value.phase.let { it == HotspotPhase.On || it == HotspotPhase.TurningOn }
        }
    }

    private fun step(id: String, label: Int, op: suspend (HotspotController) -> Unit): ActionType = SimpleAction(
        id, UiText.Res(label), ActionGroup.Network, R.drawable.ic_wifi_tethering,
        requirements = listOf(Requirement.of(RequirementKind.Shizuku)),
    ) { c, _ ->
        if (ShizukuState.current(c) != ShizukuStatus.Ready) {
            StepResult.failed(R.string.hotspot_action_shizuku)
        } else {
            val controller = HotspotController.get(c)
            op(controller)
            val error = controller.state.value.error
            if (error == null) StepResult.Ok else StepResult.failed(R.string.hotspot_action_error, error)
        }
    }

    override val actions: List<ActionType> = listOf(
        step("hotspot.on", R.string.hotspot_action_on) { it.start() },
        step("hotspot.off", R.string.hotspot_action_off) { it.stop() },
        step("hotspot.toggle", R.string.hotspot_action_toggle) { it.toggle() },
    )
}
