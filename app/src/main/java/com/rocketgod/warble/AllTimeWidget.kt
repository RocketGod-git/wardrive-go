package com.rocketgod.warble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews

class AllTimeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    companion object {
        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, AllTimeWidget::class.java)) }
                .getOrNull() ?: return
            for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
        }

        private fun buildFor(context: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val p = context.getSharedPreferences("warble", Context.MODE_PRIVATE)
            val v = RemoteViews(context.packageName, R.layout.widget_alltime)
            v.setTextViewText(R.id.at_wifi, WidgetFmt.abbrev(p.getLong("w_life_wifi", 0L)))
            v.setTextViewText(R.id.at_bt, WidgetFmt.abbrev(p.getLong("w_life_bt", 0L)))
            v.setTextViewText(R.id.at_cell, WidgetFmt.abbrev(p.getLong("w_life_cell", 0L)))
            v.setTextViewText(R.id.at_total, WidgetFmt.abbrev(p.getLong("w_life_total", 0L)))

            val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val hDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0

            val s = minOf(if (wDp > 0) wDp / 4 else 70, if (hDp > 0) hDp else 70).toFloat()
            fun sz(vid: Int, factor: Float, lo: Float, hi: Float) =
                v.setTextViewTextSize(vid, TypedValue.COMPLEX_UNIT_SP, (s * factor).coerceIn(lo, hi))
            for (vid in intArrayOf(R.id.at_wifi, R.id.at_bt, R.id.at_cell, R.id.at_total)) sz(vid, 0.32f, 15f, 30f)
            for (vid in intArrayOf(R.id.at_wifi_l, R.id.at_bt_l, R.id.at_cell_l, R.id.at_total_l)) sz(vid, 0.17f, 10f, 14f)

            val tap = PendingIntent.getActivity(
                context, 6,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.at_root, tap)
            return v
        }
    }
}
