package com.rocketgod.warble

object WidgetFmt {

    fun abbrev(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1000.0)
        else -> n.toString()
    }

    fun dist(meters: Double, metric: Boolean): String = if (metric) {
        if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.toInt()} m"
    } else {
        val ft = meters * 3.28084
        if (ft >= 5280) "%.1f mi".format(ft / 5280.0) else "${ft.toInt()} ft"
    }

    fun speed(mps: Float, metric: Boolean): String = when {
        mps < 0f -> "—"
        metric -> "${(mps * 3.6f).toInt()} km/h"
        else -> "${(mps * 2.23694f).toInt()} mph"
    }

    fun speedNum(mps: Float, metric: Boolean): String = when {
        mps < 0f -> "—"
        metric -> (mps * 3.6f).toInt().toString()
        else -> (mps * 2.23694f).toInt().toString()
    }
    fun speedUnit(metric: Boolean) = if (metric) "km/h" else "mph"

    fun distNum(meters: Double, metric: Boolean): String = if (metric) {
        if (meters >= 1000) "%.1f".format(meters / 1000.0) else meters.toInt().toString()
    } else {
        val ft = meters * 3.28084
        if (ft >= 5280) "%.1f".format(ft / 5280.0) else ft.toInt().toString()
    }
    fun distUnit(meters: Double, metric: Boolean): String = if (metric) {
        if (meters >= 1000) "km" else "m"
    } else {
        if (meters * 3.28084 >= 5280) "mi" else "ft"
    }
}
