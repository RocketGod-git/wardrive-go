package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.SendPhase
import com.rocketgod.warble.core.Leaderboard
import com.rocketgod.warble.data.CategoryCount
import com.rocketgod.warble.data.ExportSessionEntity
import com.rocketgod.warble.data.MakerAggRow
import com.rocketgod.warble.data.ObservationEntity
import com.rocketgod.warble.data.TypeCatRow
import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.model.SortMode
import com.rocketgod.warble.data.PmkidEntity
import com.rocketgod.warble.model.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class NotableSec(val key: String, val label: String, val color: Long, val icon: ImageVector, val noun: String, val brands: Set<String>)

private val NOTABLE_SECS = listOf(
    NotableSec("flock", "FLOCK CAMS", 0xFFFF3B3B, Icons.Filled.Videocam, "cam", setOf("Flock")),
    NotableSec("police", "POLICE CAMS", 0xFFFF6A00, Icons.Filled.LocalPolice, "cam", setOf("Axon", "Digital Ally")),
    NotableSec("alpr", "ALPR CAMS", 0xFFFFB300, Icons.Filled.PhotoCamera, "ALPR cam",
        setOf("Genetec", "Neology", "Sensys Gatso", "Gatso", "Jenoptik", "Redflex")),
    NotableSec("drones", "DRONES", 0xFF16E0FF, Icons.Filled.FlightTakeoff, "drone", setOf("DJI", "Autel", "Parrot", "Skydio")),
    NotableSec("trackers", "TRACKERS", 0xFFFFD23B, Icons.Filled.TrackChanges, "tracker", setOf("Tile", "Apple Find My", "Google Find My", "Samsung SmartTag", "Chipolo", "Globalstar")),
    NotableSec("actioncams", "ACTION CAMS", 0xFF37E36B, Icons.Filled.CameraAlt, "action cam", setOf("GoPro", "Insta360")),
    NotableSec("glasses", "GLASSES", 0xFFB56BFF, Icons.Filled.Visibility, "glasses", setOf("Meta", "Luxottica")),
    NotableSec("flipper", "FLIPPER", 0xFFFF7BE0, Icons.Filled.Memory, "", setOf("Flipper Zero")),
    NotableSec("attacktools", "RF / ATTACK TOOLS", 0xFFFF3B3B, Icons.Filled.Bolt, "", setOf("PandwaRF", "USBKill", "Pwnagotchi")),
)

private fun NotableSec.matches(maker: String?): Boolean {
    val m = maker ?: return false
    return brands.any { b -> m == b || m.startsWith("$b (") }
}

@Composable
fun FieldReportScreen(
    stats: Stats,
    typeCatCounts: List<TypeCatRow>,
    monitorCats: List<CategoryCount>,
    makers: List<MakerAggRow>,
    accent: Color,
    exportSessions: List<ExportSessionEntity>,
    onSelectObs: (ObservationEntity) -> Unit,
    onSelectMaker: (String) -> Unit,
    onMasterMap: (SignalType) -> Unit,
    onCategoryMap: (String, List<String>) -> Unit = { _, _ -> },
    loadCat: suspend (String, String) -> List<ObservationEntity> = { _, _ -> emptyList() },
    loadMonitorCat: suspend (String) -> List<ObservationEntity> = { emptyList() },
    loadNotable: suspend (List<String>) -> List<ObservationEntity> = { emptyList() },
    onExportNew: () -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    onSendWigle: () -> Unit,
    onSendWdgw: () -> Unit,
    onImportWigle: () -> Unit,
    onSettings: () -> Unit,
    newForWigle: Long,
    newForWdgw: Long,
    wigleImporting: Boolean,
    wigleImportCount: Int,
    wiglePhase: com.rocketgod.warble.SendPhase = com.rocketgod.warble.SendPhase.IDLE,
    wdgwPhase: com.rocketgod.warble.SendPhase = com.rocketgod.warble.SendPhase.IDLE,
    satellites: List<com.rocketgod.warble.data.GnssSatEntity> = emptyList(),
    onSelectSat: (String) -> Unit = {},
    cellTowers: List<com.rocketgod.warble.data.CellTowerEntity> = emptyList(),
    onSelectCell: (String) -> Unit = {},
    pmkids: List<PmkidEntity> = emptyList(),
    onPmkids: () -> Unit = {},
    onSelectPmkid: (String) -> Unit = {},
    onPmkidMap: () -> Unit = {},
    onBack: () -> Unit
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val masters = listOf(SignalType.BLE, SignalType.WIFI, SignalType.CELL)

    LazyColumn(Modifier.fillMaxWidth().background(Palette.paper).padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ArrowBack, "back", tint = Palette.ink, modifier = Modifier.clickable { onBack() })
                Spacer(Modifier.width(12.dp))
                Text("FIELD REPORT", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Text("Everything you've logged, categorized. Tap a section to expand.", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            SheetRow("SIGNAL", "TOTAL", "UNIQUE", "NEW", "WiGLE", header = true, accent = accent)
            stats.perType.forEach { t ->
                SheetRow(t.type.label, Leaderboard.fmt(t.totalObservations), Leaderboard.fmt(t.unique),
                    Leaderboard.fmt(t.newThisRun), Leaderboard.fmt(t.inWigle), accent = accent)
            }
            val monCount = monitorCats.sumOf { it.c }
            if (monCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SettingsInputAntenna, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("$monCount captured via external monitor adapter", color = accent, fontFamily = Mono, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn("Export new", Icons.Filled.Download, accent, Modifier.weight(1f), onExportNew)
                ActionBtn("Export all", Icons.Filled.Download, accent, Modifier.weight(1f), onExportAll)
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn("Import file", Icons.Filled.Upload, accent, Modifier.weight(1f), onImport)
                ImportWigleBtn(wigleImporting, wigleImportCount, accent, Modifier.weight(1f), onImportWigle)
            }
            Spacer(Modifier.height(12.dp))

            SyncCard(newForWigle, newForWdgw, wiglePhase, wdgwPhase, accent, onSendWigle, onSendWdgw, onSettings)
            Spacer(Modifier.height(8.dp))
            Text("The badge is new Wi-Fi (what WiGLE scores). A send uploads every new signal — Bluetooth and cell too — then these drop to 0. WiGLE and WDGWars track separately.",
                color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
            if (exportSessions.isNotEmpty()) {
                val exOpen = expanded["m:exported"] == true
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                        .clickable { expanded["m:exported"] = !exOpen }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (exOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.CloudUpload, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("EXPORTED", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${exportSessions.size}", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                }
                if (exOpen) {
                Spacer(Modifier.height(6.dp))
                exportSessions.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CloudUpload, null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (e.dest.isNotBlank()) "Sent to ${e.dest}" else "Exported",
                                color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${fmtExportDate(e.exportedAt)} · ${e.wifi} wifi · ${e.ble} ble · ${e.cell} cell",
                                color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
                        }

                        Text("${e.wifi} Wi-Fi", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.25f)))
                }
                }
            }
        }

        if (pmkids.isNotEmpty()) {
            val pmkidOpen = expanded["m:pmkid"] == true
            item(key = "master_pmkid") {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                        .clickable { expanded["m:pmkid"] = !pmkidOpen }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (pmkidOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))

                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        Text("#", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("HASH CAPTURES", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${pmkids.size}", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    MapButton(accent) { onPmkidMap() }
                }
                if (pmkidOpen) Text("Passive PMKIDs + 4-way handshakes from EAPOL via external adapter. Tap for hashcat 22000 export.",
                    color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
            if (pmkidOpen) items(pmkids.take(10), key = { "pmkid_${it.pmkid}" }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectPmkid(p.pmkid) }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Wifi, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.ssid?.ifBlank { null } ?: "<hidden>", color = Palette.ink, fontFamily = Mono, fontSize = 15.sp, maxLines = 1)
                        Text("${p.bssid} · ch ${p.channel}", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
                    }
                    if (p.lat != null && p.lng != null) {
                        Icon(Icons.Filled.LocationOn, "located", tint = Palette.gold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(18.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.18f)))
            }
            if (pmkidOpen && pmkids.size > 10) {
                item(key = "pmkid_more") {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPmkids() }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("View all ${pmkids.size} captures →", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }

        val catsByType = typeCatCounts.groupBy { it.type }
        masters.forEach { master ->
            val cats = catsByType[master.name].orEmpty().sortedByDescending { it.c }
            val masterTotal = cats.sumOf { it.c }
            val masterLocated = cats.sumOf { it.located }
            if (masterTotal > 0) {
                val mkey = "m:${master.name}"
                val mOpen = expanded[mkey] == true
                item(key = "master_${master.name}") {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                            .clickable { expanded[mkey] = !mOpen }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (mOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Icon(masterIcon(master), null, tint = accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(master.label, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("$masterTotal", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        if (masterLocated > 0) MapButton(accent) { onMasterMap(master) }
                    }
                }
                if (mOpen) cats.forEach { tc ->
                    val cat = tc.category
                    val key = "${master.name}:$cat"
                    val open = expanded[key] == true
                    item(key = "cat_$key") {
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded[key] = !open }.padding(vertical = 11.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Icon(iconFor("", cat), null, tint = if (open) accent else Palette.muted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(cat, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${tc.c}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.3f)))
                        if (open) ExpandedRows({ loadCat(master.name, cat) }, accent, accent, onSelectObs)
                    }
                }
            }
        }

        val monTotal = monitorCats.sumOf { it.c }
        if (monTotal > 0) {
            val monOpen = expanded["m:monitor"] == true
            item(key = "master_monitor") {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                        .clickable { expanded["m:monitor"] = !monOpen }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (monOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Bolt, null, tint = Palette.gold, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("EXTERNAL MONITOR", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("$monTotal", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                }
                if (monOpen) Text("Promiscuous 802.11 capture via an external adapter (AR9271, dual-band MT7612U, or high-gain RT3070) — access points and client stations, including devices the phone radio can't see.",
                    color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
            if (monOpen) monitorCats.sortedByDescending { it.c }.forEach { cc ->
                val cat = cc.category
                val key = "monitor:$cat"
                val open = expanded[key] == true
                item(key = "catmon_$key") {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded[key] = !open }.padding(vertical = 11.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Icon(iconFor("", cat), null, tint = if (open) Palette.gold else Palette.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(cat, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${cc.c}", color = Palette.gold, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.3f)))
                    if (open) ExpandedRows({ loadMonitorCat(cat) }, accent, Palette.gold, onSelectObs)
                }
            }
        }

        if (satellites.isNotEmpty()) {
            val gnssOpen = expanded["m:gnss"] == true
            item(key = "master_gnss") {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                        .clickable { expanded["m:gnss"] = !gnssOpen }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (gnssOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.SatelliteAlt, null, tint = accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("GNSS SATELLITES", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${satellites.size}", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                }
                if (gnssOpen) Text("Every satellite your phone has logged — app-only, never uploaded. Tap a constellation, then a satellite for full detail.",
                    color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
            val byConst = satellites.groupBy { it.constellation }.toList().sortedByDescending { it.second.size }
            if (gnssOpen) byConst.forEach { (cons, sats) ->
                val skey = "gnss:$cons"
                val open = expanded[skey] == true
                item(key = "satc_$cons") {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded[skey] = !open }.padding(vertical = 11.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.SatelliteAlt, null, tint = if (open) accent else Palette.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(cons, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${sats.size}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.3f)))
                }
                if (open) {
                    items(sats.sortedByDescending { it.bestCn0 }, key = { "sat_${it.id}" }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelectSat(s.id) }.padding(start = 20.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.SatelliteAlt, null, tint = if (s.usedInFix) accent else Palette.muted, modifier = Modifier.width(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("PRN ${s.svid}", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("peak ${s.bestCn0.toInt()} dB-Hz" + if (s.hasEphemeris) " · ephemeris" else "",
                                    color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
                            }
                            Text("×${s.timesSeen}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(18.dp))
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.18f)))
                    }
                }
            }
        }

        if (cellTowers.isNotEmpty()) {
            val cellsOpen = expanded["m:cells"] == true
            item(key = "master_cells") {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                        .clickable { expanded["m:cells"] = !cellsOpen }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (cellsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.CellTower, null, tint = accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("CELL TOWERS", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${cellTowers.size}", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                }
                if (cellsOpen) Text("Every cell tower seen, with full identity and signal detail — logged app-only. Tap a technology, then a tower for full detail.",
                    color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
            val byTech = cellTowers.groupBy { it.tech }.toList().sortedByDescending { it.second.size }
            if (cellsOpen) byTech.forEach { (tech, towers) ->
                val ckey = "cells:$tech"
                val open = expanded[ckey] == true
                item(key = "cellc_$tech") {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded[ckey] = !open }.padding(vertical = 11.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.CellTower, null, tint = if (open) accent else Palette.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tech, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${towers.size}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.3f)))
                }
                if (open) {
                    items(towers.sortedByDescending { it.bestDbm }, key = { "cell_${it.id}" }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelectCell(c.id) }.padding(start = 20.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CellTower, null, tint = if (c.registeredEver) accent else Palette.muted, modifier = Modifier.width(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${c.operator} · ${if (c.cid >= 0) "CID ${c.cid}" else "PCI ${c.pci ?: "?"}"}",
                                    color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                                Text("peak ${c.bestDbm} dBm" + (c.band?.let { " · $it" } ?: "") + (if (c.registeredEver) " · serving" else ""),
                                    color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
                            }
                            Text("×${c.timesSeen}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(18.dp))
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.18f)))
                    }
                }
            }
        }

        for (sec in NOTABLE_SECS) {

            val secMakers = makers.filter { sec.matches(it.maker) }.map { it.maker }
            val loadBrands = secMakers.ifEmpty { sec.brands.toList() }
            val count = makers.filter { sec.matches(it.maker) }.sumOf { it.devices }
            val open = expanded["m:${sec.key}"] == true
            val secColor = Color(sec.color)
            item(key = "master_${sec.key}") {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                        .clickable { expanded["m:${sec.key}"] = !open }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(sec.icon, null, tint = secColor, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(sec.label, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text("$count", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    if (count > 0) MapButton(accent) { onCategoryMap(sec.label, loadBrands) }
                }
            }
            if (open) item(key = "rows_${sec.key}") {
                if (count == 0L) Text("Nothing yet", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp))
                else ExpandedRows({ loadNotable(loadBrands) }, accent, secColor, onSelectObs, noun = sec.noun, notable = true)
            }
        }

        if (makers.isNotEmpty()) {
            val makerOpen = expanded["m:maker"] == true
            item(key = "master_maker") {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                        .clickable { expanded["m:maker"] = !makerOpen }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (makerOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("🏭", fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("BY MAKER", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${makers.size}", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
                }
                if (makerOpen) Text("Tap a maker for its full device list", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
            if (makerOpen) items(makers, key = { "m_${it.maker}" }) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectMaker(m.maker) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(m.maker, color = Palette.ink, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${m.devices}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(18.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.25f)))
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun MapButton(accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.border(1.dp, accent, RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Map, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Map", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ExpandedRows(
    loader: suspend () -> List<ObservationEntity>,
    accent: Color, tint: Color, onSelectObs: (ObservationEntity) -> Unit,
    noun: String = "", notable: Boolean = false
) {
    val rows by produceState<List<ObservationEntity>?>(null) { value = loader() }
    val r = rows
    if (r == null) {
        Text("Loading…", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp))
    } else if (r.isEmpty()) {
        Text("Nothing yet", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp))
    } else {
        Column(Modifier.fillMaxWidth()) { r.forEach { o -> DeviceRow(o, accent, tint, noun, notable, onSelectObs) } }
    }
}

@Composable
private fun DeviceRow(o: ObservationEntity, accent: Color, tint: Color, noun: String, notable: Boolean, onSelectObs: (ObservationEntity) -> Unit) {

    val brand = o.maker?.ifBlank { null }
    val type: String; val subtitle: String
    if (notable) {
        type = when {
            brand == null -> o.name?.ifBlank { null } ?: o.category
            noun.isBlank() || brand.contains(noun, true) -> brand
            else -> "$brand $noun"
        }
        val rawName = o.name?.ifBlank { null }?.takeIf { !it.equals(brand, true) && !type.contains(it, true) }
        val tags = buildList {
            rawName?.let { add(it) }
            if (o.lat != null) add("geotagged")
            if (o.inWigle) add("WiGLE")
            if (o.viaMonitor) add("MON")
        }
        subtitle = if (tags.isEmpty()) o.category else tags.joinToString(" · ")
    } else {
        type = o.name ?: "— not advertised"
        subtitle = "${o.maker ?: o.category}${if (o.lat != null) " · geotagged" else ""}${if (o.inWigle) " · WiGLE" else ""}${if (o.viaMonitor) " · MON" else ""}"
    }
    Row(
        Modifier.fillMaxWidth().clickable { onSelectObs(o) }.padding(start = 20.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(iconFor(o.icon, o.category), null, tint = if (!notable && o.name.isNullOrBlank()) Palette.muted else tint, modifier = Modifier.width(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(type, color = Palette.ink, fontFamily = Mono, fontSize = 15.sp, maxLines = 1)
            Text(subtitle, color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
        }
        if (o.viaMonitor) { Icon(Icons.Filled.SettingsInputAntenna, null, tint = accent, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text("${o.bestRssi}dBm", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(18.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.18f)))
}

private fun fmtExportDate(ts: Long): String =
    SimpleDateFormat("MMM d · h:mm a", Locale.US).format(Date(ts))

private fun sortDevices(list: List<ObservationEntity>, sortMode: SortMode): List<ObservationEntity> = when (sortMode) {    SortMode.SIGNAL -> list.sortedByDescending { it.bestRssi }
    SortMode.SEEN -> list.sortedByDescending { it.timesSeen }
    SortMode.RECENT -> list.sortedByDescending { it.lastSeen }
    SortMode.NAME -> list.sortedBy { (it.name ?: it.maker ?: it.category).lowercase() }
}

private fun masterIcon(t: SignalType): ImageVector = when (t) {
    SignalType.BLE -> Icons.Filled.Bluetooth
    SignalType.WIFI -> Icons.Filled.Wifi
    SignalType.CELL -> Icons.Filled.CellTower
}

@Composable
private fun SheetRow(a: String, b: String, c: String, d: String, e: String, header: Boolean = false, accent: Color) {
    val col = if (header) Palette.muted else Palette.ink
    Row(
        Modifier.fillMaxWidth().background(if (header) Palette.panel else Palette.surface).padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Text(a, color = if (header) Palette.muted else accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1.4f))
        Text(b, color = col, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(c, color = col, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(d, color = col, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(e, color = col, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.3f)))
}

@Composable
private fun ActionBtn(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.border(1.dp, Palette.line, RoundedCornerShape(10.dp)).background(Palette.surface, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.width(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.ink, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun ImportWigleBtn(importing: Boolean, count: Int, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.border(1.dp, if (importing) accent else Palette.line, RoundedCornerShape(10.dp))
            .background(Palette.surface, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (importing) {
            androidx.compose.material3.CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text("Syncing $count · stop", color = accent, fontFamily = Mono, fontSize = 12.sp)
        } else {
            Icon(Icons.Filled.CloudDownload, null, tint = accent, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Sync WiGLE (dedup)", color = Palette.ink, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SyncCard(newForWigle: Long, newForWdgw: Long, wiglePhase: com.rocketgod.warble.SendPhase, wdgwPhase: com.rocketgod.warble.SendPhase, accent: Color, onSendWigle: () -> Unit, onSendWdgw: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, Palette.line, RoundedCornerShape(14.dp))
            .background(Palette.surface, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SYNC NEW NETWORKS", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))

            Row(
                Modifier.border(1.dp, Palette.line, RoundedCornerShape(8.dp)).clickable { onSettings() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Settings, null, tint = accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Keys", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        SendPill("WiGLE", newForWigle, wiglePhase, Palette.gold, onSendWigle)
        Spacer(Modifier.height(8.dp))
        SendPill("WDGWars", newForWdgw, wdgwPhase, accent, onSendWdgw)
    }
}

@Composable
private fun SendPill(service: String, count: Long, phase: SendPhase, color: Color, onClick: () -> Unit) {
    val busy = phase == SendPhase.WRITING || phase == SendPhase.UPLOADING || phase == SendPhase.SAVING
    val none = count <= 0L && phase == SendPhase.IDLE
    val fg = if (none) Palette.muted else Palette.ink
    Row(
        Modifier.fillMaxWidth()
            .then(if (none) Modifier.background(Palette.panel, RoundedCornerShape(10.dp))
                  else Modifier.glassAction(color, RoundedCornerShape(10.dp)))
            .clickable(enabled = !none && phase == SendPhase.IDLE) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            phase == SendPhase.SENT -> {
                Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("✓ Sent to $service!", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            busy -> {

                val label = when (phase) {
                    SendPhase.WRITING -> "Writing file…"
                    SendPhase.UPLOADING -> "Uploading → $service…"
                    else -> "Saving…"
                }
                androidx.compose.material3.CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            else -> {
                Icon(Icons.Filled.CloudUpload, null, tint = if (none) Palette.muted else color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (none) "$service — all sent" else "Send new → $service",
                    color = fg, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                Box(
                    Modifier.background(if (none) Color.Transparent else color.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(if (none) "0" else Leaderboard.fmt(count),
                        color = fg, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
