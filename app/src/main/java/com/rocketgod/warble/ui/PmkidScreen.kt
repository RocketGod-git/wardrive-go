package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.data.PmkidEntity
import com.rocketgod.warble.usb.PmkidCapture

@Composable
fun PmkidScreen(
    pmkids: List<PmkidEntity>, accent: Color,
    onSelect: (String) -> Unit, onMap: () -> Unit, onBack: () -> Unit
) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }

    fun payload() = PmkidCapture.hashcat22000(pmkids)

    fun save(): String {
        val body = payload()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "wardrive-pmkids-$stamp.hc22000"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val res = ctx.contentResolver
                val uri = res.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("null uri")
                res.openOutputStream(uri)!!.use { it.write(body.toByteArray()) }
                cv.clear(); cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0); res.update(uri, cv, null, null)
                return "Download/$name"
            }.onFailure { return "save failed: ${it.message}" }
        }
        return runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs(); val f = java.io.File(dir, name); f.writeText(body); f.absolutePath
        }.getOrElse {
            val f = java.io.File(ctx.getExternalFilesDir(null), name); f.writeText(body); f.absolutePath
        }
    }

    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, payload())
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Wardrive Go PMKID hashlist (hashcat 22000)")
        }
        runCatching {
            ctx.startActivity(android.content.Intent.createChooser(send, "Share PMKID hashlist")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { note = "share failed: ${it.message}" }
    }

    Column(Modifier.fillMaxSize().background(Palette.paper).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(Palette.surface, CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.5f), CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, "back", tint = accent, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Text("Captures (${pmkids.size})", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            note ?: "Passive PMKIDs (🔑) + 4-way handshakes (🤝) grabbed when a client associates. Export is hashcat 22000.",
            color = if (note != null) accent else Palette.muted, fontFamily = Mono, fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            val enabled = pmkids.isNotEmpty()
            @Composable
            fun Btn(label: String, onClick: () -> Unit) {
                Text(label, color = if (enabled) inkOn(accent) else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(end = 10.dp)
                        .background(if (enabled) accent else Palette.surface, RoundedCornerShape(9.dp))
                        .clickable(enabled = enabled) { onClick() }
                        .padding(horizontal = 18.dp, vertical = 10.dp))
            }
            Btn("COPY") { clip.setText(AnnotatedString(payload())); note = "Copied ${pmkids.size} hashes ✓" }
            Btn("SAVE") { note = "Saved → ${save()}" }
            Btn("SEND") { share() }
            Row(
                Modifier.border(1.dp, accent, RoundedCornerShape(9.dp)).clickable(enabled = enabled) { onMap() }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Map, null, tint = if (enabled) accent else Palette.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Map", color = if (enabled) accent else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (pmkids.isEmpty()) {
            Text("No PMKIDs captured yet.\nPlug in an external adapter and drive — a PMKID is grabbed passively when a client joins a network.",
                color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(pmkids, key = { it.pmkid }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(p.pmkid) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Wifi, null, tint = accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (p.kind == 2) "🤝 HS" else "🔑 PMKID", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(p.ssid?.ifBlank { null } ?: "<hidden>", color = Palette.ink, fontFamily = Mono, fontSize = 16.sp, maxLines = 1)
                            }
                            Text("${p.bssid} · ch ${p.channel}", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
                        }
                        if (p.lat != null && p.lng != null) {
                            Icon(Icons.Filled.LocationOn, "located", tint = Palette.gold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.width(20.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.35f)))
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}
