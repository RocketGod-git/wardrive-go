package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.Proximity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SignalDetail(
    c: Contact, accent: Color, onLocate: () -> Unit = {},
    canBlock: Boolean = false, blocked: Boolean = false, onBlockToggle: () -> Unit = {},
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowBack, "back", tint = Palette.ink, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Text(com.rocketgod.warble.classify.NotableDevices.displayName(c.name, c.key, c.companyId, c.category, c.maker) ?: "— not advertised",
                color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth().glassAction(accent)
                .clickable { onLocate() }.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.GpsFixed, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("LOCATE", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        if (canBlock) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().glassAction(if (blocked) Palette.muted else accent)
                    .clickable { onBlockToggle() }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Shield, null, tint = if (blocked) Palette.muted else accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (blocked) "EXCLUDED FROM UPLOADS ✓" else "EXCLUDE FROM UPLOADS",
                    color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        val threat = com.rocketgod.warble.classify.NotableDevices.threat(c.name, c.key, c.companyId, c.category)
        if (threat != null) {
            Section("OFFENSIVE DEVICE", accent)
            Field("Classification", c.category)
            Field("Threat lane", threat.lane.label)
        }

        Section("IDENTITY", accent)
        Field("Name", c.name ?: "— not advertised")
        Field("Manufacturer", c.maker ?: "—")
        Field("Inferred type", c.category)
        Field("Identifier", c.key)

        Section("SIGNAL", accent)
        Field("Current", "${c.rssi} dBm")
        Field("Strongest", "${c.bestRssi} dBm")
        Field("Proximity", when (c.proximity) { Proximity.HOT -> "Hot"; Proximity.WARM -> "Warm"; Proximity.COLD -> "Cold" })
        c.frequency?.let { Field("Frequency", "$it MHz") }
        c.channel?.let { Field("Channel", "$it") }
        com.rocketgod.warble.model.wifiStandardLabel(c.wifiStandard, c.frequency)?.let { Field("Wi-Fi standard", it) }
        c.distanceMm?.let { Field("Distance (RTT)", String.format("%.1f m", it / 1000.0)) }
        c.capabilities?.let { Field("Capabilities", it) }
        Field("Connectable", if (c.connectable) "Yes" else "No")

        Section("ACTIVITY", accent)
        Field("Times logged", "${c.timesSeen}")
        Field("First seen", ts(c.firstSeen))
        Field("Last seen", ts(c.lastSeen))
        if (c.lat != null && c.lng != null)
            Field("Location", String.format("%.5f, %.5f", c.lat, c.lng))

        if (c.serviceUuids.isNotEmpty()) {
            Section("SERVICE UUIDS", accent)
            c.serviceUuids.forEach { u ->
                Text(u, color = Palette.ink, fontFamily = Mono, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "The identifier is an OS-assigned value that rotates for privacy — it can't be used to track anyone.",
            color = Palette.muted, fontFamily = Mono, fontSize = 11.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, accent: Color) {
    Spacer(Modifier.height(16.dp))
    Text(title, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(value, color = Palette.ink, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun ts(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
