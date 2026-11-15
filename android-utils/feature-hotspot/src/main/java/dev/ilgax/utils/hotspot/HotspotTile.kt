package dev.ilgax.utils.hotspot

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.ilgax.utils.core.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HotspotTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { HotspotController.get(this) }

    override fun onStartListening() {
        render(controller.state.value)
        scope.launch {
            controller.refresh()
            render(controller.state.value)
        }
    }

    override fun onClick() {
        val current = controller.state.value
        if (!current.shizukuReady) {
            openApp()
            return
        }
        scope.launch {
            render(current.copy(busy = true))
            val after = withContext(Dispatchers.Default) { controller.toggle() }
            render(after)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Tiles that are STATE_UNAVAILABLE ignore taps, so the "needs Shizuku" tile stays inactive and opens the app. */
    private fun render(s: HotspotUiState) {
        val tile = qsTile ?: return
        when {
            !s.shizukuReady -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.hotspot_tile_needs_shizuku)
            }
            s.busy || s.phase == HotspotPhase.TurningOn || s.phase == HotspotPhase.TurningOff -> {
                tile.state = if (s.phase == HotspotPhase.TurningOff || s.phase == HotspotPhase.On) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.hotspot_card_working)
            }
            s.phase == HotspotPhase.On -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = s.ssid ?: getString(R.string.state_on_short)
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.state_off_short)
            }
        }
        tile.updateTile()
    }

    private fun openApp() {
        ShizukuState.refresh()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
    }
}
