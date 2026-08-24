package com.rocketgod.warble.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.SignalType

@Composable
fun CoverGlance(contacts: List<Contact>, scanning: Boolean, hasFix: Boolean, sats: Int, speedMps: Float, useMetric: Boolean, accent: Color) {
    val ap = contacts.count { it.type == SignalType.WIFI && it.category != "WiFi Client" }
    val bt = contacts.count { it.type == SignalType.BLE }
    val cell = contacts.count { it.type == SignalType.CELL }
    val spd = if (useMetric) "${(speedMps * 3.6f).toInt()} km/h" else "${(speedMps * 2.237f).toInt()} mph"

    val tr = rememberInfiniteTransition(label = "cover")
    val pulse by tr.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")

    Column(
        Modifier.fillMaxSize().background(Palette.paper).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (scanning) accent.copy(alpha = pulse) else Palette.line))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Bolt, null, tint = accent, modifier = Modifier.size(15.dp))
            Text("WARDRIVE", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))

        Text("$ap", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 68.sp, maxLines = 1)
        Text("APs in range", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            GlancePip(Icons.Filled.Bluetooth, bt, accent)
            GlancePip(Icons.Filled.CellTower, cell, accent)
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, null, tint = if (hasFix) accent else Palette.muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (hasFix) "$sats sats · $spd" else "no fix", color = Palette.ink, fontFamily = Mono, fontSize = 13.sp,
                maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GlancePip(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text("$count", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1)
    }
}
