package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.core.GnssStat
import com.rocketgod.warble.core.SatInfo
import com.rocketgod.warble.data.GnssSatEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SatView(
    val id: String,
    val svid: Int,
    val constellation: String,
    val cn0: Float,
    val bestCn0: Float?,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
    val carrierHz: Float,
    val health: Int?,
    val uraIndex: Int?,
    val svConfig: Int?,
    val antiSpoof: Boolean?,
    val firstSeen: Long?,
    val lastSeen: Long?,
    val timesSeen: Long?,
    val leapSeconds: Int?,
    val live: Boolean
)

private fun SatInfo.toView(leap: Int?) = SatView(
    id = "$constellation-$svid", svid = svid, constellation = constellation, cn0 = cn0, bestCn0 = null,
    elevation = elevationDeg, azimuth = azimuthDeg, usedInFix = usedInFix, hasAlmanac = hasAlmanac,
    hasEphemeris = hasEphemeris, carrierHz = carrierFreqHz, health = health, uraIndex = uraIndex,
    svConfig = svConfig, antiSpoof = antiSpoof, firstSeen = null, lastSeen = null, timesSeen = null,
    leapSeconds = leap, live = true
)

private fun GnssSatEntity.toView(leap: Int?) = SatView(
    id = id, svid = svid, constellation = constellation, cn0 = lastCn0, bestCn0 = bestCn0,
    elevation = elevation, azimuth = azimuth, usedInFix = usedInFix, hasAlmanac = hasAlmanac,
    hasEphemeris = hasEphemeris, carrierHz = carrierHz, health = health, uraIndex = uraIndex,
    svConfig = svConfig, antiSpoof = antiSpoof, firstSeen = firstSeen, lastSeen = lastSeen,
    timesSeen = timesSeen, leapSeconds = leap, live = true
)

private fun merge(live: SatView?, logged: GnssSatEntity?, leap: Int?): SatView? {
    if (live == null && logged == null) return null
    if (live == null) return logged!!.toView(leap)
    val l = logged
    return live.copy(
        bestCn0 = l?.bestCn0 ?: live.bestCn0,
        health = live.health ?: l?.health,
        uraIndex = live.uraIndex ?: l?.uraIndex,
        svConfig = live.svConfig ?: l?.svConfig,
        antiSpoof = live.antiSpoof ?: l?.antiSpoof,
        firstSeen = l?.firstSeen, lastSeen = l?.lastSeen, timesSeen = l?.timesSeen,
        live = true
    )
}

@Composable
fun SatelliteListScreen(gnss: GnssStat, logged: List<GnssSatEntity>, accent: Color, onSelect: (String) -> Unit, onBack: () -> Unit) {

    val liveViews = gnss.sats.map { it.toView(gnss.leapSeconds) }
        .groupBy { it.id }.map { (_, g) -> g.maxByOrNull { it.cn0 }!! }
    val rows = if (liveViews.isNotEmpty()) liveViews else logged.map { it.toView(gnss.leapSeconds) }
    val liveNow = liveViews.isNotEmpty()

    Box(Modifier.fillMaxSize().background(Palette.paper)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ArrowBack, "back", tint = Palette.ink, modifier = Modifier.clickable { onBack() })
                    Spacer(Modifier.width(12.dp))
                    Text("SATELLITES", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Text(
                    if (liveNow) "${gnss.usedInFix} used in fix · ${gnss.total} in view — tap a satellite for full detail"
                    else "No live fix — showing ${logged.size} logged satellites. Go outside for a live sky.",
                    color = Palette.muted, fontFamily = Mono, fontSize = 12.sp
                )
                if (gnss.leapSeconds != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("GPS→UTC leap seconds (from broadcast): ${gnss.leapSeconds}", color = accent, fontFamily = Mono, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            val byConst = rows.groupBy { it.constellation }.toList().sortedByDescending { it.second.size }
            byConst.forEach { (cons, sats) ->
                item(key = "h_$cons") {
                    Text("$cons · ${sats.size}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                }
                items(sats.sortedByDescending { it.cn0 }, key = { "${it.id}" }) { s -> SatRow(s, accent, onSelect) }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun SatRow(s: SatView, accent: Color, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(s.id) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.SatelliteAlt, null, tint = if (s.usedInFix) accent else Palette.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${s.constellation} PRN ${s.svid}", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "el ${s.elevation.toInt()}° · az ${s.azimuth.toInt()}°" + if (s.usedInFix) " · in fix" else "",
                color = Palette.muted, fontFamily = Mono, fontSize = 11.sp
            )
        }
        SignalBars(s.cn0, accent)
        Spacer(Modifier.width(8.dp))
        Text(if (s.cn0 > 0f) "${s.cn0.toInt()}" else "—", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(" dB", color = Palette.muted, fontFamily = Mono, fontSize = 10.sp)
        Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(18.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.18f)))
}

@Composable
private fun SignalBars(cn0: Float, accent: Color) {

    val lit = ((cn0 - 15f) / 6f).toInt().coerceIn(0, 5)
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) {
            Box(
                Modifier.padding(end = 2.dp).width(3.dp).height((3 + i * 2).dp)
                    .background(if (i <= lit) accent else Palette.line, RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
fun SatelliteDetailScreen(id: String, gnss: GnssStat, logged: List<GnssSatEntity>, accent: Color, onBack: () -> Unit) {
    val live = gnss.sats.firstOrNull { "${it.constellation}-${it.svid}" == id }?.toView(gnss.leapSeconds)
    val log = logged.firstOrNull { it.id == id }
    val s = merge(live, log, gnss.leapSeconds)
    val df = SimpleDateFormat("MMM d · h:mm a", Locale.US)

    Column(Modifier.fillMaxSize().background(Palette.paper).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowBack, "back", tint = Palette.ink, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Text("SATELLITE", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        if (s == null) {
            Spacer(Modifier.height(16.dp))
            Text("This satellite is no longer in view and isn't in the log.", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
            return
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SatelliteAlt, null, tint = accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("${s.constellation} · PRN ${s.svid}", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Text(if (s.usedInFix) "Currently used in position fix" else "In view, not used in fix",
            color = if (s.usedInFix) accent else Palette.muted, fontFamily = Mono, fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp))

        Spacer(Modifier.height(16.dp))
        Section("SIGNAL & ORBIT", accent)
        Field("PRN / SV ID", "${s.svid}  — the satellite's ID slot as far as your phone is concerned", accent)
        Field("Signal C/N0", if (s.cn0 > 0f) "${"%.1f".format(s.cn0)} dB-Hz" else "—", accent)
        if (s.bestCn0 != null && s.bestCn0 > 0f) Field("Best C/N0 logged", "${"%.1f".format(s.bestCn0)} dB-Hz", accent)
        Field("Elevation", "${"%.1f".format(s.elevation)}°", accent)
        Field("Azimuth", "${"%.1f".format(s.azimuth)}°", accent)
        Field("Carrier frequency", if (s.carrierHz > 0f) "${"%.3f".format(s.carrierHz / 1e6f)} MHz (${bandOf(s.carrierHz)})" else "—", accent)
        Field("Ephemeris (precise orbit)", yesNo(s.hasEphemeris), accent)
        Field("Almanac (coarse orbit)", yesNo(s.hasAlmanac), accent)

        Spacer(Modifier.height(16.dp))
        Section("BROADCAST NAV MESSAGE", accent)
        Text("Decoded live from the GPS L1 C/A broadcast. Blank fields mean this phone's chipset doesn't expose raw nav messages, or the relevant subframe hasn't been received yet.",
            color = Palette.muted, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
        Field("Health bits", s.health?.let { "$it  (${if (it == 0) "healthy" else "reporting a fault"})" } ?: "—", accent)
        Field("URA index", s.uraIndex?.let { "$it  (operator accuracy claim)" } ?: "—", accent)
        Field("SV configuration code", s.svConfig?.let { "$it  (hardware block)" } ?: "—", accent)
        Field("Anti-spoofing (A-S)", s.antiSpoof?.let { if (it) "ON — encrypted P(Y) code active" else "off" } ?: "—", accent)
        Field("Leap seconds (GPS→UTC)", s.leapSeconds?.let { "$it s" } ?: "—", accent)

        if (s.firstSeen != null) {
            Spacer(Modifier.height(16.dp))
            Section("LOG", accent)
            Field("First seen", df.format(Date(s.firstSeen)), accent)
            s.lastSeen?.let { Field("Last seen", df.format(Date(it)), accent) }
            s.timesSeen?.let { Field("Times logged", "$it", accent) }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Section(title: String, accent: Color) {
    Text(title, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Field(label: String, value: String, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
        Text(value, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.18f)))
}

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private fun bandOf(hz: Float): String = when {
    hz > 1.56e9f && hz < 1.59e9f -> "L1"
    hz > 1.20e9f && hz < 1.25e9f -> "L2"
    hz > 1.16e9f && hz < 1.19e9f -> "L5"
    else -> "band"
}
