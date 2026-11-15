package dev.ilgax.utils.speaker

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.ModuleCardState
import dev.ilgax.utils.core.ModuleShortcut
import dev.ilgax.utils.core.UtilModule
import dev.ilgax.utils.core.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class SpeakerModule(context: Context) : UtilModule {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val id = "speaker"
    override val title: String = app.getString(R.string.speaker_title)
    override val summary: String = app.getString(R.string.speaker_summary)
    override val iconRes = R.drawable.ic_speaker_off

    override val cardState: StateFlow<ModuleCardState> =
        combine(SpeakerController.enabledFlow, ShizukuState.status) { enabled, shizuku ->
            ModuleCardState(
                if (enabled) CardMode.On else CardMode.Off,
                SpeakerText.cardStatus(enabled, shizuku).resolve(app),
            )
        }.stateIn(scope, SharingStarted.Eagerly, ModuleCardState(CardMode.Off, ""))

    override val shortcut = ModuleShortcut(
        id = "toggle_speaker",
        shortLabel = app.getString(R.string.speaker_shortcut_short),
        longLabel = app.getString(R.string.speaker_shortcut_long),
        iconRes = R.drawable.ic_speaker_off,
        activityClass = SpeakerToggleActivity::class.java,
    )

    init {
        // Prime the enabled flow from preferences and finish any release that was waiting for Shizuku.
        SpeakerController.isEnabled(app)
        dev.ilgax.utils.core.action.ActionRegistry.shared.register(SpeakerMacroActions)
        SpeakerController.retryPendingRelease(app)
    }

    override suspend fun toggle() {
        withContext(Dispatchers.Default) { SpeakerController.setEnabled(app, !SpeakerController.isEnabled(app)) }
    }

    @Composable
    override fun Detail(contentPadding: PaddingValues, modifier: Modifier) =
        SpeakerDetail(contentPadding, modifier)
}
