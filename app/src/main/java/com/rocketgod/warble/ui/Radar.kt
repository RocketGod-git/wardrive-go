package com.rocketgod.warble.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.RadarPaint
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Radar(
    contacts: List<Contact>,
    accent: Color,
    modifier: Modifier = Modifier,
    radarPaint: RadarPaint = RadarPaint.ACCENT,
    onSelect: (Contact) -> Unit
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "sweep"
    )
    val ping by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ping"
    )
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "pulse"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val dim = if (maxWidth < maxHeight) maxWidth else maxHeight

        val cx = maxWidth / 2
        val cy = maxHeight / 2

        val maxR = dim / 2 - 18.dp

        Canvas(Modifier.fillMaxSize()) {
            drawGrid(accent)
            rotate(sweep, Offset(size.width / 2, size.height / 2)) {
                val cx = size.width / 2; val cy = size.height / 2
                val insetPx = 6.dp.toPx()
                val diam = size.width - 2 * insetPx
                val r = diam / 2
                val sweepDeg = 130f
                val f = sweepDeg / 360f

                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        f * 0.5f to accent.copy(alpha = 0.05f),
                        f * 0.8f to accent.copy(alpha = 0.20f),
                        f * 0.95f to accent.copy(alpha = 0.42f),
                        f to accent.copy(alpha = 0.72f),
                        (f + 0.0006f) to Color.Transparent,
                        1f to Color.Transparent,
                        center = Offset(cx, cy)
                    ),
                    startAngle = 0f, sweepAngle = sweepDeg, useCenter = true,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(diam, diam)
                )

                val a = Math.toRadians(sweepDeg.toDouble())
                val tip = Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
                drawLine(
                    brush = Brush.linearGradient(listOf(accent.copy(alpha = 0.12f), accent), start = Offset(cx, cy), end = tip),
                    start = Offset(cx, cy), end = tip, strokeWidth = 3f, cap = StrokeCap.Round
                )
            }

            val insetPx = 6.dp.toPx()
            val pr = (size.width / 2 - insetPx) * ping
            drawCircle(
                accent.copy(alpha = (1f - ping) * 0.7f), radius = pr,
                center = Offset(size.width / 2, size.height / 2), style = Stroke(width = 2f)
            )

            drawCircle(
                accent, radius = 1.5.dp.toPx() + pulse * 0.5f,
                center = Offset(size.width / 2, size.height / 2)
            )
        }

        for (c in contacts) {
            key(c.key) { Blip(c, cx, cy, maxR, accent, radarPaint, onSelect) }
        }
    }
}

private fun blipHue(key: String): Color {
    var h = 0; for (ch in key) h = h * 31 + ch.code
    return Color.hsv((((h % 360) + 360) % 360).toFloat(), 0.75f, 1f)
}

private val WIGLE_SIGNAL = intArrayOf(0x00FF00, 0x55FF00, 0xAAFF00, 0xFFFF00, 0xFFAA00, 0xFF5500, 0xFF0000)
private fun signalColor(strength: Float): Color {
    val idx = (((1f - strength) * (WIGLE_SIGNAL.size - 1)).toInt()).coerceIn(0, WIGLE_SIGNAL.size - 1)
    return Color(0xFF000000.toInt() or WIGLE_SIGNAL[idx])
}

@Composable
private fun Blip(c: Contact, cx: androidx.compose.ui.unit.Dp, cy: androidx.compose.ui.unit.Dp, maxR: androidx.compose.ui.unit.Dp, accent: Color, radarPaint: RadarPaint, onSelect: (Contact) -> Unit) {
    val angle = Math.toRadians(c.angle.toDouble())
    val targetDist = maxR.value * (1f - c.strength)
    val animDist by animateFloatAsState(
        targetValue = targetDist,
        animationSpec = if (c.strength > 0.2f) tween(550, easing = FastOutSlowInEasing) else snap(),
        label = "blipDist"
    )
    val x = cx + (cos(angle) * animDist).dp
    val y = cy + (sin(angle) * animDist).dp

    val haloSize = (14 + c.strength * 7).dp
    val iconSize = (9 + c.strength * 5).dp
    val col = when (radarPaint) {
        RadarPaint.SIGNAL -> signalColor(c.strength)
        RadarPaint.RAINBOW -> if (c.identified) blipHue(c.key) else Palette.muted
        else -> if (c.identified) accent else Palette.muted
    }
    Box(
        Modifier
            .offset(x = x - haloSize / 2, y = y - haloSize / 2)
            .size(haloSize)
            .background(
                Brush.radialGradient(listOf(col.copy(alpha = 0.42f), Color.Transparent)),
                CircleShape
            )
            .clickable(
                interactionSource = remember(c.key) { MutableInteractionSource() },
                indication = null
            ) { onSelect(c) },
        contentAlignment = Alignment.Center
    ) {
        Icon(iconFor(c.icon, c.category), c.category, tint = col, modifier = Modifier.size(iconSize))

        if (c.viaMonitor) {
            Icon(
                Icons.Filled.Bolt, "monitor", tint = Palette.gold,
                modifier = Modifier.align(Alignment.TopEnd).size(iconSize * 0.72f)
            )
        }
    }
}

private fun DrawScope.drawGrid(accent: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val maxR = size.width / 2 - 6.dp.toPx()
    for (i in 1..3) {
        drawCircle(accent.copy(alpha = 0.22f), radius = maxR * i / 3f, center = c, style = Stroke(1f))
    }
    drawLine(accent.copy(alpha = 0.16f), Offset(c.x - maxR, c.y), Offset(c.x + maxR, c.y), 1f)
    drawLine(accent.copy(alpha = 0.16f), Offset(c.x, c.y - maxR), Offset(c.x, c.y + maxR), 1f)
}
