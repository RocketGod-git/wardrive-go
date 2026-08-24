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

class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    companion object {

        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, StatsWidget::class.java)) }
                .getOrNull() ?: return
            for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
        }

        private fun abbrev(n: Long) = WidgetFmt.abbrev(n)

        private fun buildFor(context: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val hDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return build(context, wDp, hDp)
        }

        private fun build(context: Context, wDp: Int, hDp: Int): RemoteViews {
            val p = context.getSharedPreferences("warble", Context.MODE_PRIVATE)
            val ap = p.getInt("w_ap", 0)
            val nw = p.getLong("w_nw", 0L); val nb = p.getLong("w_nb", 0L); val nc = p.getLong("w_nc", 0L)
            val beast = p.getBoolean("w_beast", false)
            val v = RemoteViews(context.packageName, R.layout.widget_stats)

            v.setTextViewText(R.id.w_wifi, abbrev(nw))
            v.setTextViewText(R.id.w_bt, abbrev(nb))
            v.setTextViewText(R.id.w_cell, abbrev(nc))
            v.setTextViewText(R.id.w_range, ap.toString())
            v.setViewVisibility(R.id.w_beast, if (beast) View.VISIBLE else View.GONE)

            val s = minOf(if (wDp > 0) wDp else 110, if (hDp > 0) hDp else 110).toFloat()
            fun sz(id: Int, factor: Float, lo: Float, hi: Float) =
                v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, (s * factor).coerceIn(lo, hi))
            for (id in intArrayOf(R.id.w_wifi, R.id.w_bt, R.id.w_cell, R.id.w_range)) sz(id, 0.17f, 16f, 32f)
            for (id in intArrayOf(R.id.w_wifi_l, R.id.w_bt_l, R.id.w_cell_l, R.id.w_range_l)) sz(id, 0.085f, 11f, 15f)

            val tap = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, tap)
            return v
        }
    }
}
