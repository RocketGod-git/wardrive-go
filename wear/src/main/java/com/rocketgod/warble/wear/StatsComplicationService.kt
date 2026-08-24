package com.rocketgod.warble.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class StatsComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, wifi = 128, ble = 214, cell = 17, rate = 42, accent = resolveAccent())

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val p = getSharedPreferences(TILE_PREFS, MODE_PRIVATE)
        return build(
            request.complicationType,
            p.getInt("wifi", 0), p.getInt("ble", 0), p.getInt("cell", 0), p.getInt("rate", 0),
            resolveAccent()
        )
    }

    private fun resolveAccent(): Int {
        val fromPhone = getSharedPreferences(TILE_PREFS, MODE_PRIVATE).getLong("accent", 0L)
        if (fromPhone != 0L) return fromPhone.toInt()
        val fromWatch = getSharedPreferences("wardrive_wear", MODE_PRIVATE).getLong("accent", 0L)
        return if (fromWatch != 0L) fromWatch.toInt() else 0xFF37ECCB.toInt()
    }

    private fun resolveGoal(): Int {
        val g = getSharedPreferences(TILE_PREFS, MODE_PRIVATE).getInt("wifi_goal", 0)
        return if (g > 0) g else RANGE_MAX.toInt()
    }

    private fun dim(c: Int): Int = (c and 0xFF000000.toInt()) or ((c and 0x00FEFEFE) ushr 1)

    private fun text(s: String) = PlainComplicationText.Builder(s).build()

    private fun build(type: ComplicationType, wifi: Int, ble: Int, cell: Int, rate: Int, accent: Int): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = text(wifi.toString()),
                contentDescription = text("New Wi-Fi: $wifi")
            ).setTitle(text("WIFI")).build()

            ComplicationType.RANGED_VALUE -> {

                val max = resolveGoal().toFloat()
                RangedValueComplicationData.Builder(
                    value = wifi.toFloat().coerceIn(0f, max), min = 0f, max = max,
                    contentDescription = text("New Wi-Fi: $wifi")
                ).setText(text(wifi.toString())).setTitle(text("WIFI"))

                    .setColorRamp(ColorRamp(intArrayOf(dim(accent), accent), interpolated = true))
                    .build()
            }

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = text("Wi-Fi $wifi · BT $ble · Cell $cell"),
                contentDescription = text("Wardrive Go live counts")
            ).setTitle(text("Wardrive Go")).build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(
                    Icon.createWithBitmap(renderBadge(wifi, accent)), SmallImageType.ICON
                ).build(),
                contentDescription = text("New Wi-Fi: $wifi")
            ).build()

            else -> null
        }

    private fun renderBadge(wifi: Int, accent: Int): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f

        val apex = size * 0.30f
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        floatArrayOf(size * 0.10f, size * 0.17f, size * 0.24f).forEachIndexed { i, r ->
            arc.strokeWidth = size * (0.030f - i * 0.004f)
            c.drawArc(RectF(cx - r, apex - r, cx + r, apex + r), 215f, 110f, false, arc)
        }
        c.drawCircle(cx, apex, size * 0.024f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })

        val num = wifi.toString()
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.42f
        }
        val maxW = size * 0.86f
        while (numPaint.measureText(num) > maxW && numPaint.textSize > 8f) {
            numPaint.textSize = numPaint.textSize - 2f
        }
        c.drawText(num, cx, size * 0.66f, numPaint)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.13f; letterSpacing = 0.15f
        }
        c.drawText("WIFI", cx, size * 0.87f, label)
        return bmp
    }

    private companion object {

        const val RANGE_MAX = 500f
    }
}
