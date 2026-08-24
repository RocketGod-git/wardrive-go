package com.rocketgod.warble

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray

class OffensiveListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {

        private var rows: List<Pair<String, String>> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            val s = ctx.getSharedPreferences("warble", Context.MODE_PRIVATE)
                .getString("w_threat_list", "") ?: ""
            rows = if (s.isBlank()) emptyList() else runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    o.optString("l", "Device") to o.optString("n", "SURVEILLANCE")
                }
            }.getOrDefault(emptyList())
        }

        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val (label, lane) = rows[position]
            val v = RemoteViews(ctx.packageName, R.layout.widget_offensive_item)
            v.setTextViewText(R.id.oi_name, label)
            v.setTextViewText(R.id.oi_lane, laneLabel(lane))
            v.setInt(R.id.oi_dot, "setBackgroundColor", laneColor(lane))

            v.setOnClickFillInIntent(R.id.oi_root, Intent())
            return v
        }

        private fun laneColor(lane: String): Int = when (lane) {
            "ATTACK" -> 0xFFFF5B52.toInt()
            "TRACKING" -> 0xFFFFD23B.toInt()
            else -> 0xFFFFB300.toInt()
        }

        private fun laneLabel(lane: String): String = when (lane) {
            "ATTACK" -> "attack"
            "TRACKING" -> "track"
            else -> "snoop"
        }
    }
}
