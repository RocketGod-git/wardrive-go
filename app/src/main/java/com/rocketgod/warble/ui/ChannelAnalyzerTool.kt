package com.rocketgod.warble.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.SignalType

private class Chan(val ch: Int, val count: Int, val bestRssi: Int)

private fun bandCh(c: Contact): Pair<Int, Int>? {
    val f = c.frequency
    if (f != null && f > 0) return when {
        f == 2484 -> 0 to 14
        f < 2500 -> 0 to ((f - 2412) / 5 + 1)
        f < 5925 -> 1 to ((f - 5000) / 5)
        f <= 7125 -> 2 to ((f - 5950) / 5)
        else -> null
    }
    val ch = c.channel ?: return null
    if (ch <= 0) return null
    return if (ch <= 14) 0 to ch else 1 to ch
}

@Composable
fun ChannelAnalyzerTool(contacts: List<Contact>, accent: Color) {

    val bands = remember(contacts) {
        val m = Array(3) { HashMap<Int, IntArray>() }
        for (c in contacts) {
            if (c.type != SignalType.WIFI) continue
            if (c.category == "WiFi Client") continue
            val (b, ch) = bandCh(c) ?: continue
            val e = m[b].getOrPut(ch) { intArrayOf(0, -120) }
            e[0]++; if (c.rssi > e[1]) e[1] = c.rssi
        }
        m.map { band -> band.map { (ch, v) -> Chan(ch, v[0], v[1]) } }
    }
    val stats24 = bands[0]; val stats5 = bands[1]; val stats6 = bands[2]
    val total = stats24.sumOf { it.count } + stats5.sumOf { it.count } + stats6.sumOf { it.count }
    val maxCount = (listOf(stats24, stats5, stats6).flatten().maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

    val busy24 = stats24.maxByOrNull { it.count }?.ch
    val busy5 = stats5.maxByOrNull { it.count }?.ch
    val busy6 = stats6.maxByOrNull { it.count }?.ch
    val busiest = listOf(stats24, stats5, stats6).flatten().maxByOrNull { it.count }?.ch

    val by24 = stats24.associateBy { it.ch }
    val band24 = (1..13).map { by24[it] ?: Chan(it, 0, -120) }
    val band5 = stats5.sortedBy { it.ch }
    val band6 = stats6.sortedBy { it.ch }

    Column(Modifier.fillMaxSize()) {

        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 36.dp, end = 36.dp)) {
            Icon(Icons.Filled.BarChart, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Channel Analyzer", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))

            if (total > 0) {
                Spacer(Modifier.width(6.dp))
                Text("$total APs", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.background(accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }

        Text(if (busiest != null) "Live · busiest ch $busiest · normalized to the fullest bar"
             else "Live · scanning for Wi-Fi…",
            color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp))

        if (total == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Wifi, null, tint = Palette.muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No Wi-Fi in range yet", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Bars fill in as access points are seen", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                }
            }
            return
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            BandBars("2.4 GHz", Band24Color, band24, maxCount, busy24, accent, Modifier.weight(1f))

            if (band5.isNotEmpty() || band6.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    if (band5.isNotEmpty()) BandBars("5 GHz", Band5Color, band5, maxCount, busy5, accent, Modifier.weight(1.4f))
                    if (band6.isNotEmpty()) {
                        if (band5.isNotEmpty()) Spacer(Modifier.width(10.dp))
                        BandBars("6 GHz", Band6Color, band6, maxCount, busy6, accent, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BandBars(label: String, bandColor: Color, chans: List<Chan>, maxCount: Int, busiest: Int?, accent: Color, modifier: Modifier) {

    Column(
        modifier.fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(bandColor.copy(alpha = 0.05f))
            .border(1.dp, bandColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(top = 4.dp, bottom = 2.dp, start = 6.dp, end = 6.dp)
    ) {
        Text(label, color = bandColor, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp))

        Row(
            Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (c in chans) Bar(c, maxCount, c.ch == busiest, accent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Bar(c: Chan, maxCount: Int, hot: Boolean, accent: Color, modifier: Modifier) {

    val frac by animateFloatAsState(
        (c.count.toFloat() / maxCount).coerceIn(0f, 1f),
        animationSpec = tween(500), label = "chanBar"
    )
    val barColor = if (hot) accent else accent.copy(alpha = 0.55f)
    Column(
        modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(if (c.count > 0) "${c.count}" else "", color = if (hot) accent else Palette.muted,
            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth(0.74f), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(if (c.count == 0) 0.02f else (0.06f + 0.94f * frac))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(
                        if (c.count == 0) SolidColor(Palette.line.copy(alpha = 0.4f))
                        else Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.35f)))
                    )
            )
        }
        Spacer(Modifier.height(3.dp))
        Text("${c.ch}", color = if (hot) accent else Palette.ink, fontFamily = Mono,
            fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal, fontSize = 10.sp,
            textAlign = TextAlign.Center, maxLines = 1)
    }
}
