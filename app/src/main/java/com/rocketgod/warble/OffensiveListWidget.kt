package com.rocketgod.warble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews

class OffensiveListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { build(context, mgr, id) }
    }

    companion object {

        fun notifyChanged(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, OffensiveListWidget::class.java)) }
                .getOrNull() ?: return
            if (ids.isEmpty()) return
            for (id in ids) runCatching { build(context, mgr, id) }
            runCatching { mgr.notifyAppWidgetViewDataChanged(ids, R.id.ol_list) }
        }

        private fun build(context: Context, mgr: AppWidgetManager, id: Int) {
            val p = context.getSharedPreferences("warble", Context.MODE_PRIVATE)

            val total = p.getInt("w_threat_held", 0)
            val beast = p.getBoolean("w_beast", false)
            val v = RemoteViews(context.packageName, R.layout.widget_offensive_list)
            v.setTextViewText(R.id.ol_count, " · $total seen")
            v.setViewVisibility(R.id.ol_beast, if (beast) View.VISIBLE else View.GONE)

            val svc = Intent(context, OffensiveListService::class.java).apply {
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            v.setRemoteAdapter(R.id.ol_list, svc)
            v.setEmptyView(R.id.ol_list, R.id.ol_empty)

            val open = PendingIntent.getActivity(
                context, 3,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setPendingIntentTemplate(R.id.ol_list, open)
            v.setOnClickPendingIntent(R.id.ol_title, open)

            mgr.updateAppWidget(id, v)
        }
    }
}
