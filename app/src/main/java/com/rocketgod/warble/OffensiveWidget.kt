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

class OffensiveWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
    }

    companion object {
        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, OffensiveWidget::class.java)) }
                .getOrNull() ?: return
            for (id in ids) runCatching { mgr.updateAppWidget(id, buildFor(context, mgr, id)) }
        }

        private fun buildFor(context: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val hDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return build(context, wDp, hDp)
        }

        private fun build(context: Context, wDp: Int, hDp: Int): RemoteViews {
            val p = context.getSharedPreferences("warble", Context.MODE_PRIVATE)
            val total = p.getInt("w_threat", 0)
            val atk = p.getInt("w_atk", 0); val surv = p.getInt("w_surv", 0); val trk = p.getInt("w_trk", 0)
            val beast = p.getBoolean("w_beast", false)
            val v = RemoteViews(context.packageName, R.layout.widget_offensive)
            v.setTextViewText(R.id.o_count, total.toString())

            v.setTextColor(R.id.o_count, if (total == 0) 0xFF37ECCB.toInt() else 0xFFFF5B52.toInt())
            v.setTextViewText(R.id.o_atk, atk.toString())
            v.setTextViewText(R.id.o_surv, surv.toString())
            v.setTextViewText(R.id.o_trk, trk.toString())
            v.setViewVisibility(R.id.o_beast, if (beast) View.VISIBLE else View.GONE)

            val s = minOf(if (wDp > 0) wDp else 110, if (hDp > 0) hDp else 110).toFloat()
            fun sz(id: Int, factor: Float, lo: Float, hi: Float) =
                v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, (s * factor).coerceIn(lo, hi))
            sz(R.id.o_count, 0.34f, 26f, 64f)
            sz(R.id.o_title, 0.11f, 11f, 16f)
            for (id in intArrayOf(R.id.o_atk, R.id.o_surv, R.id.o_trk)) sz(id, 0.13f, 14f, 24f)
            for (id in intArrayOf(R.id.o_atk_l, R.id.o_surv_l, R.id.o_trk_l)) sz(id, 0.07f, 11f, 14f)

            val tap = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.o_root, tap)
            return v
        }
    }
}
