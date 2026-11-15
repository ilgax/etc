package dev.ilgax.utils.hotspot

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ilgax.utils.core.ModuleCardState
import dev.ilgax.utils.core.ModuleShortcut
import dev.ilgax.utils.core.UtilModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HotspotModule(context: Context) : UtilModule {
    private val app = context.applicationContext
    private val controller = HotspotController.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val id = "hotspot"

    init {
        dev.ilgax.utils.core.action.ActionRegistry.shared.register(HotspotActions(app))
    }

    override val title: String = app.getString(R.string.hotspot_title)
    override val summary: String = app.getString(R.string.hotspot_summary)
    override val iconRes = R.drawable.ic_wifi_tethering

    override val cardState: StateFlow<ModuleCardState> = controller.state
        .map { ModuleCardState(HotspotText.cardMode(it), HotspotText.cardStatus(it).resolve(app)) }
        .stateIn(scope, SharingStarted.Eagerly, ModuleCardState(HotspotText.cardMode(controller.state.value), HotspotText.cardStatus(controller.state.value).resolve(app)))

    override val shortcut = ModuleShortcut(
        id = "toggle_hotspot",
        shortLabel = app.getString(R.string.hotspot_shortcut_short),
        longLabel = app.getString(R.string.hotspot_shortcut_long),
        iconRes = R.drawable.ic_wifi_tethering,
        activityClass = HotspotToggleActivity::class.java,
    )

    override suspend fun toggle() {
        controller.toggle()
    }

    override fun onAppVisible(visible: Boolean) {
        if (visible) scope.launch { controller.refresh() }
    }

    @Composable
    override fun Detail(contentPadding: PaddingValues, modifier: Modifier) =
        HotspotDetail(controller, contentPadding, modifier)
}
