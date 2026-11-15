package dev.ilgax.utils.hotspot

import dev.ilgax.utils.core.ToggleTrampolineActivity

/** Launcher-shortcut target: toggles the hotspot and toasts the outcome. */
class HotspotToggleActivity : ToggleTrampolineActivity() {
    override suspend fun performToggle(): String {
        val result = HotspotController.get(this).toggle()
        return HotspotText.toast(result).resolve(this)
    }
}
