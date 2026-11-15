package dev.ilgax.utils.core

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** What a home card shows: a coarse mode plus one line of live status. */
enum class CardMode { On, Off, Busy, Unavailable }

data class ModuleCardState(val mode: CardMode, val status: String)

/** Launcher shortcut that runs [activityClass] (a translucent trampoline that toggles the module). */
data class ModuleShortcut(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    @DrawableRes val iconRes: Int,
    val activityClass: Class<*>,
)

/**
 * One tool in the app. A module owns its state, its detail screen and its entry points
 * (tile, shortcut); the shell only talks to this interface.
 */
interface UtilModule {
    val id: String
    val title: String
    val summary: String

    @get:DrawableRes
    val iconRes: Int

    /** Drives the home card. Must always have a value. */
    val cardState: StateFlow<ModuleCardState>

    /** Home card quick switch. Runs on the caller's coroutine; the module posts [CardMode.Busy] itself. */
    suspend fun toggle()

    /** Full screen for this module. [contentPadding] already contains the system bar insets. */
    @Composable
    fun Detail(contentPadding: PaddingValues, modifier: Modifier)

    val shortcut: ModuleShortcut? get() = null

    /** False for modules with nothing to switch on or off; the home card then hides its switch. */
    val quickSwitch: Boolean get() = true

    /** The app window became visible or hidden; modules may refresh or release resources. */
    fun onAppVisible(visible: Boolean) {}
}
