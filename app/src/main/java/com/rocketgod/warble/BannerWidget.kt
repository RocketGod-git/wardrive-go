package com.rocketgod.warble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

class BannerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    companion object {
        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, BannerWidget::class.java)) }
                .getOrNull() ?: return
            for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
        }

        private fun abbrev(n: Long) = WidgetFmt.abbrev(n)

        private fun buildFor(context: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val p = context.getSharedPreferences("warble", Context.MODE_PRIVATE)
            val ap = p.getInt("w_ap", 0)
            val nw = p.getLong("w_nw", 0L); val nb = p.getLong("w_nb", 0L); val nc = p.getLong("w_nc", 0L)
            val threat = p.getInt("w_threat", 0)
            val scan = p.getBoolean("w_scan", false); val beast = p.getBoolean("w_beast", false)
            val eyebrow = p.getString("w_b_eyebrow", "") ?: ""
            val title = p.getString("w_b_title", "") ?: ""
            val color = p.getLong("w_b_color", 0L)
            val rate = p.getInt("w_rate", 0); val spd = p.getFloat("w_speed", -1f); val dist = p.getFloat("w_dist", 0f)
            val metric = p.getBoolean("use_metric", java.util.Locale.getDefault().country !in setOf("US", "GB", "LR", "MM"))

            val v = RemoteViews(context.packageName, R.layout.widget_banner)

            v.setTextViewText(R.id.b_wifi, abbrev(nw))
            v.setTextViewText(R.id.b_bt, abbrev(nb))
            v.setTextViewText(R.id.b_cell, abbrev(nc))
            v.setTextViewText(R.id.b_ap, ap.toString())

            v.setTextViewText(R.id.b_rate, rate.toString())
            v.setTextViewText(R.id.b_speed, WidgetFmt.speedNum(spd, metric))
            v.setTextViewText(R.id.b_speed_u, WidgetFmt.speedUnit(metric))
            v.setTextViewText(R.id.b_dist, WidgetFmt.distNum(dist.toDouble(), metric))
            v.setTextViewText(R.id.b_dist_u, WidgetFmt.distUnit(dist.toDouble(), metric))
            v.setTextViewText(R.id.b_threat, threat.toString())
            v.setViewVisibility(R.id.b_beast, if (beast) View.VISIBLE else View.GONE)

            val teal = 0xFF37ECCB.toInt()
            if (title.isNotBlank()) {
                val c = if (color != 0L) color.toInt() else teal
                v.setTextViewText(R.id.b_eyebrow, eyebrow.ifBlank { "DETECTED" })
                v.setTextColor(R.id.b_eyebrow, c)
                v.setTextViewText(R.id.b_line, title)
                v.setInt(R.id.b_accent, "setBackgroundColor", c)
            } else {
                v.setTextViewText(R.id.b_eyebrow, if (scan) "LIVE" else "IDLE")
                v.setTextColor(R.id.b_eyebrow, if (scan) teal else 0xFF6E827D.toInt())
                v.setTextViewText(R.id.b_line, if (scan) "Scanning — no detections yet" else "Scanner idle")
                v.setInt(R.id.b_accent, "setBackgroundColor", if (scan) teal else 0xFF6E827D.toInt())
            }

            val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val hDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            val s = minOf(if (wDp > 0) wDp else 300, if (hDp > 0) hDp else 150).toFloat()
            fun sz(vid: Int, factor: Float, lo: Float, hi: Float) =
                v.setTextViewTextSize(vid, TypedValue.COMPLEX_UNIT_SP, (s * factor).coerceIn(lo, hi))
            for (vid in intArrayOf(R.id.b_wifi, R.id.b_bt, R.id.b_cell, R.id.b_ap,
                    R.id.b_rate, R.id.b_speed, R.id.b_dist, R.id.b_threat)) sz(vid, 0.13f, 15f, 28f)

            for (vid in intArrayOf(R.id.b_wifi_l, R.id.b_bt_l, R.id.b_cell_l, R.id.b_ap_l,
                    R.id.b_rate_l, R.id.b_speed_u, R.id.b_dist_u, R.id.b_threat_l)) sz(vid, 0.055f, 11f, 14f)
            sz(R.id.b_line, 0.06f, 11f, 16f)

            val tap = PendingIntent.getActivity(
                context, 5,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.b_root, tap)
            return v
        }
    }
}
