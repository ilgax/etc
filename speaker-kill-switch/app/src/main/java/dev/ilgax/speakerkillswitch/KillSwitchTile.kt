package dev.ilgax.speakerkillswitch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class KillSwitchTile : TileService() {
    override fun onStartListening() {
        SpeakerController.retryPendingRelease(this)
        update()
    }

    override fun onClick() {
        SpeakerController.setEnabled(this, !SpeakerController.isEnabled(this))
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (SpeakerController.isEnabled(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
