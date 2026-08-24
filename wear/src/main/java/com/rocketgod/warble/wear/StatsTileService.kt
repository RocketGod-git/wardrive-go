package com.rocketgod.warble.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

private const val RES = "1"
private const val TEAL = 0xFF00E5CC.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val MUTED = 0xFFB0B0B0.toInt()

class StatsTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val p = getSharedPreferences(TILE_PREFS, MODE_PRIVATE)
        val wifi = p.getInt("wifi", 0)
        val ble = p.getInt("ble", 0)
        val cell = p.getInt("cell", 0)
        val rate = p.getInt("rate", 0)

        val column = Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(Text.Builder(this, "$wifi").setTypography(Typography.TYPOGRAPHY_DISPLAY1).setColor(argb(TEAL)).build())
            .addContent(Text.Builder(this, "NEW WIFI").setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(argb(WHITE)).build())
            .addContent(Text.Builder(this, "BLE $ble   CELL $cell").setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(argb(MUTED)).build())
            .addContent(Text.Builder(this, "$rate / min").setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(argb(MUTED)).build())
            .build()

        val root = Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .setFreshnessIntervalMillis(60_000)
            .build()

        return CallbackToFutureAdapter.getFuture { completer -> completer.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val res = ResourceBuilders.Resources.Builder().setVersion(RES).build()
        return CallbackToFutureAdapter.getFuture { completer -> completer.set(res); "res" }
    }
}
