package dev.ilgax.utils.speaker

import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.action.ActionGroup
import dev.ilgax.utils.core.action.ActionProvider
import dev.ilgax.utils.core.action.ActionType
import dev.ilgax.utils.core.action.Requirement
import dev.ilgax.utils.core.action.RequirementKind
import dev.ilgax.utils.core.action.SimpleAction
import dev.ilgax.utils.core.action.StepResult
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus

/** Macro steps of the speaker module: the kill switch on, off or toggled, through its existing controller. */
object SpeakerMacroActions : ActionProvider {
    override val id = "speaker"

    private fun step(id: String, label: Int, target: (Boolean) -> Boolean): ActionType = SimpleAction(
        id, UiText.Res(label), ActionGroup.Media, R.drawable.ic_speaker_off,
        requirements = listOf(Requirement.of(RequirementKind.Shizuku)),
    ) { c, _ ->
        if (ShizukuState.current(c) != ShizukuStatus.Ready) {
            StepResult.failed(R.string.speaker_action_shizuku)
        } else {
            SpeakerController.setEnabled(c, target(SpeakerController.isEnabled(c)))
            StepResult.Ok
        }
    }

    override val actions: List<ActionType> = listOf(
        step("speaker.on", R.string.speaker_action_on) { true },
        step("speaker.off", R.string.speaker_action_off) { false },
        step("speaker.toggle", R.string.speaker_action_toggle) { !it },
    )
}
