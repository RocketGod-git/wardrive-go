package com.rocketgod.warble.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.SignalType

private class Ap(val key: String, val ssid: String?, val freq: Int, val centerFreq: Int, val rssi: Int, val widthMHz: Int, val hue: Float)

internal val Band24Color = Color(0xFFFFC24A)
internal val Band5Color = Color(0xFF37D2FF)
internal val Band6Color = Color(0xFFB39DFF)

private fun chFreq(c: Contact): Int? {
    c.frequency?.let { if (it > 0) return it }
    val ch = c.channel ?: return null
    return when {
        ch == 14 -> 2484
        ch in 1..13 -> 2407 + ch * 5
        ch in 32..177 -> 5000 + ch * 5
        else -> null
    }
}

private fun apWidth(freq: Int, std: Int): Int = when {
    freq < 2500 -> 20
    else -> when (std) { 5, 6, 8 -> 80; else -> 40 }
}

@Composable
fun SpectrumAnalyzerTool(contacts: List<Contact>, accent: Color) {
    val aps = remember(contacts) {
        contacts.asSequence()
            .filter { it.type == SignalType.WIFI && it.category != "WiFi Client" }
            .mapNotNull { c ->
                val f = chFreq(c) ?: return@mapNotNull null
                val hue = ((c.key.hashCode() and 0x7fffffff) % 360).toFloat()

                val w = c.channelWidthMhz.takeIf { it > 0 } ?: apWidth(f, c.wifiStandard)

                val cf = c.centerFreqMhz.takeIf { it > 0 } ?: f
                Ap(c.key, c.name?.ifBlank { null }, f, cf, c.rssi, w, hue)
            }
            .toList()
    }
    val band24 = aps.filter { it.freq < 2500 }
    val band5 = aps.filter { it.freq in 2500 until 5925 }
    val band6 = aps.filter { it.freq >= 5925 }
    val total = aps.size

    Column(Modifier.fillMaxSize()) {

        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 36.dp, end = 36.dp)) {
            Icon(Icons.Filled.GraphicEq, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Spectrum Analyzer", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (total > 0) {
                Spacer(Modifier.width(6.dp))
                Text("$total APs", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.background(accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }

        Text("each arch = one AP · ch width · height = signal",
            color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp))

        if (total == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Wifi, null, tint = Palette.muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No Wi-Fi in range yet", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Arches fill in as access points are seen", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                }
            }
            return
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {

            SpectrumBand("2.4 GHz", Band24Color, band24, 2400, 2478, (1..11).toList().toIntArray(), { 2407 + it * 5 }, accent, Modifier.weight(1f))

            if (band5.isNotEmpty() || band6.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    if (band5.isNotEmpty()) {
                        val lo = (band5.minOf { it.freq } - 60).coerceAtMost(5180)
                        val hi = (band5.maxOf { it.freq } + 60).coerceAtLeast(5320)

                        val ch5 = band5.map { (it.freq - 5000) / 5 }.distinct().sorted().toIntArray()

                        SpectrumBand("5 GHz", Band5Color, band5, lo, hi, ch5, { 5000 + it * 5 }, accent, Modifier.weight(1.4f))
                    }
                    if (band6.isNotEmpty()) {
                        if (band5.isNotEmpty()) Spacer(Modifier.width(10.dp))

                        var lo = band6.minOf { it.freq } - 60; var hi = band6.maxOf { it.freq } + 60
                        if (hi - lo < 200) { val mid = (lo + hi) / 2; lo = mid - 100; hi = mid + 100 }
                        val ch6 = band6.map { (it.freq - 5950) / 5 }.distinct().sorted().toIntArray()
                        SpectrumBand("6 GHz", Band6Color, band6, lo, hi, ch6, { 5950 + it * 5 }, accent, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpectrumBand(label: String, bandColor: Color, aps: List<Ap>, freqLo: Int, freqHi: Int, ticks: IntArray, tickToFreq: (Int) -> Int, accent: Color, modifier: Modifier) {

    Column(
        modifier.fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(bandColor.copy(alpha = 0.05f))
            .border(1.dp, bandColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(top = 4.dp, bottom = 2.dp)
    ) {
        Text(label, color = bandColor, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp))
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp)) {
            val span = (freqHi - freqLo).toFloat().coerceAtLeast(1f)
            fun x(freq: Int) = (freq - freqLo) / span * size.width
            val baseY = size.height - 22f * density

            val topRssi = (aps.maxOfOrNull { it.rssi } ?: -30).coerceIn(-72, -30)
            val rangeDb = (topRssi + 95).toFloat().coerceAtLeast(20f)
            fun y(rssi: Int) = baseY - (0.06f + 0.78f * ((rssi.coerceIn(-95, topRssi) + 95).toFloat() / rangeDb)) * (baseY - 6f)

            drawLine(Palette.line, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.5f)
            for (t in ticks) {
                val fx = x(tickToFreq(t))
                drawLine(Palette.line.copy(alpha = 0.35f), Offset(fx, 6f), Offset(fx, baseY), strokeWidth = 1f)
            }

            clipRect(0f, 0f, size.width, baseY) {
                for (ap in aps.sortedBy { it.rssi }) {
                    val c = Color.hsv(ap.hue, 0.62f, 1f)

                    val halfW = ((ap.widthMHz / 2f) / span * size.width).coerceAtLeast(size.width * 0.03f)
                    val xc = x(ap.centerFreq); val xl = xc - halfW; val xr = xc + halfW
                    val peakY = y(ap.rssi)
                    val path = Path().apply {
                        moveTo(xl, baseY)
                        quadraticBezierTo(xc, 2 * peakY - baseY, xr, baseY)
                        close()
                    }
                    drawPath(path, c.copy(alpha = 0.16f))
                    drawPath(path, c.copy(alpha = 0.9f), style = Stroke(width = 2f))
                }
            }

            val lbl = android.graphics.Paint().apply { color = Palette.ink.copy(alpha = 0.7f).toArgb(); textSize = 13f * density; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
            var lastLabelX = -1000f
            for (t in ticks) {
                val fx = x(tickToFreq(t))

                if (fx - lastLabelX < 16f * density) continue
                lastLabelX = fx
                drawContext.canvas.nativeCanvas.drawText("$t", fx, size.height - 5f * density, lbl)
            }

            val nameP = android.graphics.Paint().apply { textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
            val baseTextPx = 14f * density; val minTextPx = 9f * density
            val minDy = 15f * density; val step = 15f * density; val gap = 6f * density

            val placed = ArrayList<FloatArray>()
            for (ap in aps.sortedByDescending { it.rssi }) {
                val nm = ap.ssid ?: continue

                nameP.textSize = baseTextPx
                val w0 = nameP.measureText(nm); val maxW = size.width * 0.8f
                if (w0 > maxW) nameP.textSize = (baseTextPx * maxW / w0).coerceAtLeast(minTextPx)
                val halfW = (nameP.measureText(nm) / 2f + gap).coerceAtMost(size.width / 2f)
                val archX = x(ap.centerFreq)
                val lx = archX.coerceIn(halfW, size.width - halfW)
                val peakY = y(ap.rssi)
                fun clash(atY: Float) = placed.any { kotlin.math.abs(it[0] - lx) < it[1] + halfW && kotlin.math.abs(it[2] - atY) < minDy }
                var ly = peakY - 5f * density
                var tries = 0
                while (tries < 10 && clash(ly)) { ly -= step; tries++ }
                ly = ly.coerceAtLeast(10f * density)
                if (clash(ly)) continue
                placed.add(floatArrayOf(lx, halfW, ly))
                val hue = Color.hsv(ap.hue, 0.62f, 1f)

                if (peakY - ly > step * 0.6f || kotlin.math.abs(lx - archX) > 2f) {
                    drawLine(hue.copy(alpha = 0.5f), Offset(lx, ly + 3f * density), Offset(archX, peakY), strokeWidth = 1.5f)
                }
                nameP.color = hue.toArgb()
                drawContext.canvas.nativeCanvas.drawText(nm, lx, ly, nameP)
                if (placed.size >= 16) break
            }
        }
    }
}
