package com.rocketgod.warble.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

@Composable
fun BeastDiagDialog(accent: Color, provider: () -> String, onClose: () -> Unit,
                    clipProvider: (() -> String)? = null) {
    val clip = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var saved by remember { mutableStateOf<String?>(null) }
    var savedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var saveErr by remember { mutableStateOf<String?>(null) }
    var pulse by remember { mutableStateOf(0) }

    val bump by animateFloatAsState(
        targetValue = if (pulse > 0) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy,
                               stiffness = Spring.StiffnessLow), label = "bump")
    val glow by animateFloatAsState(
        targetValue = if (pulse > 0) 1f else 0f,
        animationSpec = tween(450), label = "glow")
    LaunchedEffect(pulse) { if (pulse > 0) { delay(2600); } }

    fun writeLog(): Pair<android.net.Uri?, String> {
        val payload = (clipProvider ?: provider).invoke()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "wardrive-diag-$stamp.txt"

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val res = ctx.contentResolver
                val uri = res.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: error("MediaStore insert returned null")
                res.openOutputStream(uri)!!.use { it.write(payload.toByteArray()) }
                cv.clear(); cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                res.update(uri, cv, null, null)
                return uri to "Download/$name  (${payload.length} chars)"
            }.onFailure { saveErr = it.message }
        }

        runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val f = java.io.File(dir, name)
            f.writeText(payload)
            return null to "${f.absolutePath}  (${payload.length} chars)"
        }.onFailure { saveErr = it.message }

        val f = java.io.File(ctx.getExternalFilesDir(null), name)
        f.writeText(payload)
        return null to "${f.absolutePath}  (${payload.length} chars)"
    }

    fun shareLog() {
        val uri = savedUri ?: run {
            val (u, path) = writeLog(); savedUri = u; saved = path; pulse++
            u
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (uri != null) {
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(android.content.Intent.EXTRA_TEXT, (clipProvider ?: provider).invoke())
            }
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Wardrive Go diagnostics")
        }
        runCatching {
            ctx.startActivity(android.content.Intent.createChooser(send, "Share diagnostics")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { saveErr = it.message }
    }

    var text by remember { mutableStateOf(provider()) }
    var copied by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        while (true) {
            text = provider()
            delay(400)
        }
    }

    val body = if (text.isBlank()) "No diagnostics yet. Plug in the adapter, wait for the probe, then reopen."
        else {
            val ls = text.split('\n')
            if (ls.size > 1 && ls[0].startsWith("===")) ls[0] + "\n" + ls.drop(1).asReversed().joinToString("\n")
            else ls.asReversed().joinToString("\n")
        }
    val lineCount = if (text.isBlank()) 0 else text.count { it == '\n' } + 1
    Dialog(onDismissRequest = onClose) {
        Surface(color = Palette.panel, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Diagnostic logs", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                if (saved != null) {

                    Row(
                        Modifier.fillMaxWidth()
                            .scale(bump)
                            .background(accent.copy(alpha = 0.16f * glow), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✓", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold,
                            fontSize = 20.sp, modifier = Modifier.scale(0.7f + 0.6f * glow).alpha(glow))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("SAVED", color = accent, fontFamily = Mono,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(saved!!, color = Palette.ink, fontFamily = Mono, fontSize = 12.sp)
                            Text("Tap SHARE to send it straight out — no need to find the file.",
                                color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                        }
                    }
                } else {
                    Text(
                        saveErr?.let { "Save failed: $it" }
                            ?: if (copied >= 0) "Copied $copied chars ✓ (big logs get truncated — use SAVE)"
                            else "Live · $lineCount lines · newest first. SAVE or SHARE for big logs (chronological).",
                        color = if (saveErr != null) Color(0xFFE4794B) else if (copied >= 0) accent else Palette.muted,
                        fontFamily = Mono, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                        .background(Palette.surface, RoundedCornerShape(8.dp)).padding(10.dp)
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(body, color = Palette.ink, fontFamily = Mono, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "COPY", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier
                            .glassAction(accent, RoundedCornerShape(9.dp))
                            .clickable {
                                val payload = (clipProvider ?: provider).invoke()
                                clip.setText(AnnotatedString(payload))
                                copied = payload.length
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "SAVE", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier
                            .glassAction(accent, RoundedCornerShape(9.dp))
                            .clickable {
                                saveErr = null
                                val (u, path) = writeLog()
                                savedUri = u; saved = path; pulse++
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "SHARE", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier
                            .glassAction(accent, RoundedCornerShape(9.dp))
                            .clickable { saveErr = null; shareLog() }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "CLOSE", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
