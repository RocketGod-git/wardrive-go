package com.rocketgod.warble.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

const val TILE_PREFS = "wear_tile"

class WearStatsListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        var changed = false
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == "/wardrive/stats") {
                val m = DataMapItem.fromDataItem(e.dataItem).dataMap
                getSharedPreferences(TILE_PREFS, MODE_PRIVATE).edit()
                    .putInt("wifi", m.getInt("wifi", 0))
                    .putInt("ble", m.getInt("ble", 0))
                    .putInt("cell", m.getInt("cell", 0))
                    .putInt("rate", m.getInt("rate", 0))
                    .putString("b_title", m.getString("b_title") ?: "")
                    .putLong("accent", m.getLong("accent", 0L))
                    .putInt("wifi_goal", m.getInt("wifi_goal", 0))
                    .apply()
                changed = true
            }
        }
        if (changed) {
            TileService.getUpdater(this).requestUpdate(StatsTileService::class.java)

            runCatching {
                androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                    .create(this, android.content.ComponentName(this, StatsComplicationService::class.java))
                    .requestUpdateAll()
            }
        }
    }
}
