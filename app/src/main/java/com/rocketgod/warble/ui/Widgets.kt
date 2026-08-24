package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AutoShrinkLink(
    url: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 22.sp,
    minSize: TextUnit = 11.sp,
) {
    val uri = LocalUriHandler.current
    var fs by remember(label, maxSize) { mutableStateOf(maxSize) }
    Text(
        text = label,
        color = accent,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = fs,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        textDecoration = TextDecoration.Underline,
        onTextLayout = { r -> if (r.hasVisualOverflow && fs.value > minSize.value) fs = (fs.value * 0.92f).sp },
        modifier = modifier.fillMaxWidth().clickable { runCatching { uri.openUri(url) } },
    )
}

@Composable
fun CmdBox(label: String, cmd: String, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = accent.copy(alpha = 0.85f), fontFamily = Mono, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier.fillMaxWidth()
                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                .background(Palette.panel, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            SelectionContainer { Text(cmd, color = Palette.ink, fontFamily = Mono, fontSize = 11.sp) }
        }
    }
}
