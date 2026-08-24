package com.rocketgod.warble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

class ToolConsoleWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { build(context, mgr, id) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        runCatching { build(context, mgr, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_PAGE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val page = intent.getIntExtra(EXTRA_PAGE, 0)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                context.getSharedPreferences("warble", Context.MODE_PRIVATE).edit()
                    .putInt("w_console_page_$id", page).apply()
                runCatching { build(context, AppWidgetManager.getInstance(context), id) }
            }
        }
    }

    companion object {
        private const val ACTION_PAGE = "com.rocketgod.warble.CONSOLE_PAGE"
        private const val EXTRA_PAGE = "page"

        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, ToolConsoleWidget::class.java)) }
                .getOrNull() ?: return
            for (id in ids) runCatching { build(context, mgr, id) }
        }

        private fun abbrev(n: Long) = WidgetFmt.abbrev(n)

        private fun build(context: Context, mgr: AppWidgetManager, id: Int) {
            val p = context.getSharedPreferences("warble", Context.MODE_PRIVATE)
            val page = p.getInt("w_console_page_$id", 0).coerceIn(0, 2)
            val v = RemoteViews(context.packageName, R.layout.widget_console)

            val active = 0xFFE9F3F0.toInt(); val idle = 0xFF6E827D.toInt()
            v.setTextColor(R.id.c_tab0, if (page == 0) 0xFF37ECCB.toInt() else idle)
            v.setTextColor(R.id.c_tab1, if (page == 1) 0xFFFF5B52.toInt() else idle)
            v.setTextColor(R.id.c_tab2, if (page == 2) 0xFFFFC24A.toInt() else idle)
            v.setOnClickPendingIntent(R.id.c_tab0, tab(context, id, 0))
            v.setOnClickPendingIntent(R.id.c_tab1, tab(context, id, 1))
            v.setOnClickPendingIntent(R.id.c_tab2, tab(context, id, 2))

            val ap = p.getInt("w_ap", 0)
            val nw = p.getLong("w_nw", 0L); val nb = p.getLong("w_nb", 0L); val nc = p.getLong("w_nc", 0L)
            val scan = p.getBoolean("w_scan", false); val beast = p.getBoolean("w_beast", false)
            val threat = p.getInt("w_threat", 0)
            val atk = p.getInt("w_atk", 0); val surv = p.getInt("w_surv", 0); val trk = p.getInt("w_trk", 0)

            val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val w = if (wDp > 0) wDp else 200
            val short = w < 220

            val big: String; val sub: String; val line: String; val bigColor: Int
            when (page) {
                0 -> {
                    big = abbrev(nw); sub = "Wi‑Fi found"; bigColor = 0xFF37ECCB.toInt()
                    line = when {
                        w < 220 -> "${abbrev(nb)} BT"
                        else -> "${abbrev(nb)} BT · ${abbrev(nc)} cell"
                    }
                }
                1 -> {
                    big = threat.toString(); sub = if (threat == 0) "all clear" else "hostile nearby"
                    bigColor = if (threat == 0) 0xFF37ECCB.toInt() else 0xFFFF5B52.toInt()
                    fun part(n: Int, lng: String, srt: String) = if (n <= 0) null else "$n ${if (short) srt else lng}"
                    val parts = listOfNotNull(part(atk, "attack", "atk"), part(surv, "snoop", "snp"), part(trk, "track", "trk"))
                    line = if (parts.isEmpty()) "—" else parts.joinToString(" · ")
                }
                else -> {
                    big = ap.toString(); sub = "APs in range"; bigColor = 0xFF37ECCB.toInt()
                    line = when {
                        beast -> "⚡ Beast · ${if (scan) "scanning" else "idle"}"
                        scan -> "scanning"
                        else -> "scanner idle"
                    }
                }
            }
            v.setTextViewText(R.id.c_big, big)
            v.setTextColor(R.id.c_big, bigColor)
            v.setTextViewText(R.id.c_sub, sub)
            v.setTextViewText(R.id.c_line, line)
            v.setViewVisibility(R.id.c_beast, if (beast) View.VISIBLE else View.GONE)

            val hDp = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            val s = minOf(if (wDp > 0) wDp else 140, if (hDp > 0) hDp else 110).toFloat()
            fun sz(vid: Int, factor: Float, lo: Float, hi: Float) =
                v.setTextViewTextSize(vid, TypedValue.COMPLEX_UNIT_SP, (s * factor).coerceIn(lo, hi))
            sz(R.id.c_big, 0.32f, 24f, 56f)
            sz(R.id.c_sub, 0.11f, 12f, 16f)
            sz(R.id.c_line, 0.11f, 12f, 16f)
            for (vid in intArrayOf(R.id.c_tab0, R.id.c_tab1, R.id.c_tab2)) sz(vid, 0.075f, 11f, 14f)

            val open = PendingIntent.getActivity(
                context, 4,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.c_body, open)

            mgr.updateAppWidget(id, v)
        }

        private fun tab(context: Context, id: Int, page: Int): PendingIntent {
            val i = Intent(context, ToolConsoleWidget::class.java).apply {
                action = ACTION_PAGE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(EXTRA_PAGE, page)
                data = Uri.parse("console://$id/$page")
            }
            return PendingIntent.getBroadcast(
                context, id * 10 + page, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
