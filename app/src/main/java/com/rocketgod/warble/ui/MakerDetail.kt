package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.data.ObservationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MakerDetail(
    maker: String,
    loadRows: suspend (String) -> List<ObservationEntity>,
    accent: Color,
    onSelectObs: (ObservationEntity) -> Unit,
    onBack: () -> Unit
) {

    val rows by produceState(emptyList<ObservationEntity>(), maker) { value = loadRows(maker) }

    val totalObs = rows.sumOf { it.timesSeen.toLong() }
    val inWigle = rows.count { it.inWigle }
    val ble = rows.count { it.type == "BLE" }
    val wifi = rows.count { it.type == "WIFI" }
    val cell = rows.count { it.type == "CELL" }
    val byCategory = rows.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }

    LazyColumn(Modifier.fillMaxWidth().background(Palette.paper).padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ArrowBack, "back", tint = Palette.ink, modifier = Modifier.clickable { onBack() })
                Spacer(Modifier.width(12.dp))
                Text(maker, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 2)
            }
            Text("${rows.size} devices · ${totalObs} observations${if (inWigle > 0) " · $inWigle in WiGLE" else ""}",
                color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(12.dp))
                    .background(Palette.surface, RoundedCornerShape(12.dp)).padding(vertical = 14.dp)
            ) {
                Stat(Icons.Filled.Bluetooth, "Bluetooth", ble, accent, Modifier.weight(1f))
                Stat(Icons.Filled.Wifi, "WiFi", wifi, accent, Modifier.weight(1f))
                Stat(Icons.Filled.CellTower, "Cell", cell, accent, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))

            if (byCategory.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    byCategory.forEach { (cat, n) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp).border(1.dp, Palette.line, RoundedCornerShape(10.dp))
                                .background(Palette.surface, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(iconFor("", cat), null, tint = accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("$n", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.width(5.dp))
                            Text(cat, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            Text("DEVICES", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
        }

        items(rows, key = { it.key }) { o ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelectObs(o) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(iconFor(o.icon, o.category), null, tint = if (o.name.isNullOrBlank()) Palette.muted else accent, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.name ?: "— not advertised", color = Palette.ink, fontFamily = Mono, fontSize = 16.sp, maxLines = 1)
                    Text("${o.category} · seen ${o.timesSeen}× · ${rel(o.lastSeen)}", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
                }
                Text("${o.bestRssi}dBm", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(20.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.35f)))
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, n: Int, accent: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text("$n", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    }
}

private fun rel(millis: Long): String {
    val d = System.currentTimeMillis() - millis
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000}m ago"
        d < 86_400_000 -> "${d / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(millis))
    }
}
