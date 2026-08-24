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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.classify.NotableDevices
import com.rocketgod.warble.classify.ThreatHit
import com.rocketgod.warble.classify.ThreatLane
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.SignalType

private fun laneIcon(lane: ThreatLane): ImageVector = when (lane) {
    ThreatLane.ATTACK -> Icons.Filled.Warning
    ThreatLane.SURVEILLANCE -> Icons.Filled.Videocam
    ThreatLane.TRACKING -> Icons.Filled.TrackChanges
}

@Composable
fun ThreatScopeTool(contacts: List<Contact>, accent: Color, onSelect: (Contact) -> Unit) {

    val threats: List<Pair<Contact, ThreatHit>> = remember(contacts) {
        contacts.asSequence()
            .filter { it.type != SignalType.CELL }
            .mapNotNull { c -> NotableDevices.threat(c.name, c.key, c.companyId, c.category)?.let { c to it } }
            .sortedWith(compareBy({ it.second.lane.ordinal }, { -it.first.rssi }))
            .toList()
    }

    Column(Modifier.fillMaxSize()) {

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, bottom = 8.dp)) {
            Icon(Icons.Filled.Security, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Offensive Devices", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            if (threats.isNotEmpty()) Text(
                "${threats.size}", color = inkOn(Color(ThreatLane.ATTACK.colorArgb)),
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier
                    .background(Color(ThreatLane.ATTACK.colorArgb), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }

        if (threats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Security, null, tint = Palette.muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("All clear", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("No offensive devices in range", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(threats, key = { "threat_" + it.first.key }) { (c, t) ->
                    ThreatRow(c, t, accent, onSelect)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

@Composable
private fun ThreatRow(c: Contact, t: ThreatHit, accent: Color, onSelect: (Contact) -> Unit) {
    val laneColor = Color(t.lane.colorArgb)
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(c) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            Modifier.size(30.dp).background(laneColor.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(laneIcon(t.lane), null, tint = laneColor, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(10.dp))

        val label = t.label
        val maker = c.maker?.ifBlank { null }
        val headline = if (maker != null && maker.contains(label, true) && maker.length > label.length) maker else label
        val name = c.name?.ifBlank { null }?.takeIf { !it.equals(headline, true) && !it.equals(label, true) }
        val sub = name ?: t.lane.label
        Column(Modifier.weight(1f)) {

            Text(headline, color = laneColor, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (c.type == SignalType.BLE) Icons.Filled.Bluetooth else Icons.Filled.Wifi,
                    null, tint = Palette.muted, modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(sub, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
            }
        }
        Text("${c.rssi}dBm", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
