package com.rocketgod.warble.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.usb.BeastState
import kotlinx.coroutines.delay

@Composable
fun BoxScope.BeastOverlay(beast: BeastState, accent: androidx.compose.ui.graphics.Color) {
    var engage by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }
    var adapter by remember { mutableStateOf<com.rocketgod.warble.usb.Adapter?>(null) }
    var prevId by remember { mutableStateOf<String?>(null) }

    val engagedAdapter = (beast as? BeastState.Engaged)?.adapter
    LaunchedEffect(engagedAdapter?.idHex) {
        val was = prevId; prevId = engagedAdapter?.idHex
        if (engagedAdapter != null) {
            adapter = engagedAdapter
            removed = false; engage = true; delay(if (engagedAdapter.isAr9271) 2600 else 4200); engage = false
        } else if (was != null) {
            engage = false; removed = true; delay(2800); removed = false
        }
    }

    AnimatedVisibility(engage, enter = fadeIn(tween(160)), exit = fadeOut(tween(320)), modifier = Modifier.matchParentSize()) {
        val a = adapter

        val name = a?.chipsetName ?: ""

        val serial = a != null && ((a.vid == 0x10c4 && a.pid == 0xea60) || (a.vid == 0x303a && a.pid == 0x1001))
        val mt = name.startsWith("MediaTek MT7612U")
        val engaged = a?.supported == true
        val hue = if (engaged) accent else Palette.ochre
        Box(Modifier.fillMaxSize().background(Palette.paper.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            val tr = rememberInfiniteTransition(label = "beast")
            val pulse by tr.animateFloat(0.88f, 1.16f, infiniteRepeatable(tween(620), RepeatMode.Reverse), label = "pulse")
            val ring by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ring")
            Canvas(Modifier.size(280.dp)) {
                for (k in 0..2) {
                    var ph = (ring + k / 3f) % 1f; if (ph < 0) ph += 1f
                    drawCircle(hue.copy(alpha = (1 - ph) * 0.5f), radius = ph * size.minDimension * 0.5f, style = Stroke(2.2f))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                Icon(Icons.Filled.Bolt, null, tint = hue, modifier = Modifier.size(66.dp).graphicsLayer { scaleX = pulse; scaleY = pulse })
                Spacer(Modifier.height(10.dp))
                val title = if (engaged) "BEAST MODE" else "WRONG ADAPTER"
                Text(title, color = hue, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (engaged) 40.sp else 30.sp,
                    style = TextStyle(shadow = Shadow(hue.copy(alpha = 0.9f), Offset.Zero, 34f)))
                Spacer(Modifier.height(10.dp))

                Text(a?.name ?: "External radio", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)

                if (a != null) {
                    Text("${a.chipsetName}  ·  ${a.idHex}", color = hue, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        !engaged -> "Wardrive Go drives the Atheros AR9271 (ath9k_htc), MediaTek MT7612U (mt76x2u), and ESP32 Marauder boards over serial. This one is detected but has no driver — swap to a supported adapter."
                        serial -> "serial wardriver engaged · passive Wi-Fi scan"
                        mt -> "dual-band monitor mode ready · mt76x2u (2.4 + 5 GHz)"
                        else -> "monitor mode ready"
                    },
                    color = if (engaged) Palette.muted else Palette.ink, fontFamily = Mono, fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    AnimatedVisibility(
        removed, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().background(Palette.ochre, RoundedCornerShape(12.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Usb, null, tint = inkOn(Palette.ochre), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("External adapter removed · back to internal radio",
                color = inkOn(Palette.ochre), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
