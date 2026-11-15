package dev.ilgax.tabremote.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.ilgax.tabremote.protocol.Command

class RemoteTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val layout = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(getString(R.string.tile_title))
                    .setFontStyle(font(13f, 0xFFB0BEC5.toInt()))
                    .build(),
            )
            .addContent(spacer(4f))
            .addContent(row(R.string.tile_lock to Command.LOCK, R.string.tile_play to Command.PLAY_PAUSE))
            .addContent(spacer(4f))
            .addContent(row(R.string.tile_vol_down to Command.VOLUME_DOWN, R.string.tile_vol_up to Command.VOLUME_UP))
            .addContent(spacer(4f))
            .addContent(row(R.string.tile_ring to Command.RING, R.string.tile_close to Command.KILL_APP))
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun row(a: Pair<Int, Command>, b: Pair<Int, Command>) = LayoutElementBuilders.Row.Builder()
        .addContent(button(a.first, a.second))
        .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
        .addContent(button(b.first, b.second))
        .build()

    private fun button(label: Int, command: Command): LayoutElementBuilders.LayoutElement {
        val launch = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(CommandActivity::class.java.name)
                    .addKeyToExtraMapping(CommandActivity.EXTRA_CMD, ActionBuilders.intExtra(command.id))
                    .build(),
            )
            .build()
        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder().setId("cmd_${command.id}").setOnClick(launch).build(),
            )
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(0xFF2B3A4C.toInt()))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(17f)).build())
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(66f))
            .setHeight(dp(34f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(modifiers)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(getString(label))
                    .setMaxLines(1)
                    .setFontStyle(font(12f, 0xFFFFFFFF.toInt()))
                    .build(),
            )
            .build()
    }

    private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    private fun font(size: Float, color: Int) = LayoutElementBuilders.FontStyle.Builder()
        .setSize(sp(size))
        .setColor(argb(color))
        .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
