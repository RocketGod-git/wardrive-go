package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
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
fun PmkidDetailScreen(p: PmkidEntity, accent: Color, onBack: () -> Unit, onMap: (() -> Unit)? = null) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val line = remember(p) { PmkidCapture.hashcat22000(listOf(p)) }

    fun save(): String {
        val name = "wardrive-pmkid-${p.bssid.replace(":", "")}.hc22000"
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
                res.openOutputStream(uri)!!.use { it.write(line.toByteArray()) }
                cv.clear(); cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0); res.update(uri, cv, null, null)
                return "Download/$name"
            }.onFailure { return "save failed: ${it.message}" }
        }
        return runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs(); val f = java.io.File(dir, name); f.writeText(line); f.absolutePath
        }.getOrElse {
            val f = java.io.File(ctx.getExternalFilesDir(null), name); f.writeText(line); f.absolutePath
        }
    }

    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, line)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "PMKID · ${p.ssid ?: p.bssid}")
        }
        runCatching {
            ctx.startActivity(android.content.Intent.createChooser(send, "Share PMKID")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { note = "share failed: ${it.message}" }
    }

    Column(Modifier.fillMaxSize().background(Palette.paper).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(Palette.surface, CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.5f), CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, "back", tint = accent, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(p.ssid?.ifBlank { null } ?: "<hidden>", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(note ?: (if (p.kind == 2) "Passive 4-way handshake (WPA*02) · hashcat 22000" else "Passive PMKID (WPA*01) · hashcat 22000"),
            color = if (note != null) accent else Palette.muted, fontFamily = Mono, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(12.dp))
                .background(Palette.surface, RoundedCornerShape(12.dp)).padding(14.dp)
        ) {
            Field("BSSID (AP)", p.bssid, accent)
            Field("STA (client)", p.sta, accent)
            Field("Channel", "${p.channel}${if (p.channel <= 14) " (2.4 GHz)" else " (5 GHz)"}", accent)
            Field("Signal", "${p.rssi} dBm", accent)
            val located = p.lat != null && p.lng != null
            val loc = if (located) "%.6f, %.6f".format(p.lat, p.lng) else "not located"
            Field("Location", loc, accent)
            if (located && onMap != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.clickable { onMap() }
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Place, null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("View capture on map", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            val when_ = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(p.firstSeen))
            Field("Captured", when_, accent)
        }
        Spacer(Modifier.height(14.dp))

        Text("HASHCAT 22000", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(10.dp))
                .background(Palette.surface, RoundedCornerShape(10.dp)).padding(12.dp)
        ) {
            SelectionContainer {
                Text(line, color = Palette.ink, fontFamily = Mono, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun Btn(label: String, onClick: () -> Unit) {
                Text(label, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(end = 10.dp)
                        .glassAction(accent, RoundedCornerShape(9.dp))
                        .clickable { onClick() }
                        .padding(horizontal = 18.dp, vertical = 10.dp))
            }
            Btn("COPY") { clip.setText(AnnotatedString(line)); note = "Copied ✓" }
            Btn("SAVE") { note = "Saved → ${save()}" }
            Btn("SEND") { share() }
        }

        Spacer(Modifier.height(18.dp))
        Text("CRACK IT · hashcat mode 22000", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        CmdBox("Linux / macOS", "hashcat -m 22000 capture.hc22000 wordlist.txt", accent)
        Spacer(Modifier.height(6.dp))
        CmdBox("Windows · PowerShell", ".\\hashcat.exe -m 22000 capture.hc22000 wordlist.txt", accent)

        Spacer(Modifier.height(14.dp))
        Text("CRACK ONLINE", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        AutoShrinkLink("https://handshakecrack.com/", "handshakecrack.com", accent, maxSize = 24.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(label: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, color = Palette.ink, fontFamily = Mono, fontSize = 13.sp)
        }
    }
}
