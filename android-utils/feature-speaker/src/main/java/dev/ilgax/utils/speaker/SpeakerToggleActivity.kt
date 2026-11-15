package dev.ilgax.utils.speaker

import dev.ilgax.utils.core.ToggleTrampolineActivity

/** Launcher-shortcut target: flips the speaker switch and reports the new state in a toast. */
class SpeakerToggleActivity : ToggleTrampolineActivity() {
    override suspend fun performToggle(): String {
        val on = !SpeakerController.isEnabled(this)
        SpeakerController.setEnabled(this, on)
        return getString(if (on) R.string.speaker_toggled_on else R.string.speaker_toggled_off)
    }
}
