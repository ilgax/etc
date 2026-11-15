package dev.ilgax.utils.math

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile: collapses the shade and starts Solve (which waits a little longer for the shade to close). */
class SolveTile : TileService() {
    override fun onStartListening() {
        val ready = ScreenGrabber(this).method() != CaptureMethod.None
        qsTile?.apply {
            state = if (ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = getString(if (ready) R.string.math_setup_shizuku_ready else R.string.math_card_not_ready)
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, SolveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SolveActivity.EXTRA_FROM_TILE, true)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
}
