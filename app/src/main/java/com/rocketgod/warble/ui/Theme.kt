package com.rocketgod.warble.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

object Palette {

    private val D_PAPER = Color(0xFF06090A); private val D_SURFACE = Color(0xFF0E1618)
    private val D_PANEL = Color(0xFF16211F); private val D_INK = Color(0xFFE9F3F0)
    private val D_LINE = Color(0xFF2C3D3B); private val D_MUTED = Color(0xFF94A9A4)
    private val D_GOLD = Color(0xFFFFD24A); private val D_BLUE = Color(0xFF2E9DFF)

    var paper by mutableStateOf(D_PAPER); private set
    var surface by mutableStateOf(D_SURFACE); private set
    var panel by mutableStateOf(D_PANEL); private set
    var ink by mutableStateOf(D_INK); private set
    var line by mutableStateOf(D_LINE); private set
    var muted by mutableStateOf(D_MUTED); private set
    var gold by mutableStateOf(D_GOLD); private set

    var bluetooth by mutableStateOf(D_BLUE); private set
    var cell by mutableStateOf(D_INK); private set

    val glow = Color(0xFF37ECCB)
    val ochre = Color(0xFFE0A53C)
    val danger = Color(0xFFFF5B52)

    fun apply(skin: com.rocketgod.warble.model.Skin) {
        val nInk = skin.inkHex?.let { Color(it) } ?: D_INK
        paper = skin.paperHex?.let { Color(it) } ?: D_PAPER
        surface = skin.surfaceHex?.let { Color(it) } ?: D_SURFACE
        panel = skin.panelHex?.let { Color(it) } ?: D_PANEL
        ink = nInk
        line = skin.lineHex?.let { Color(it) } ?: D_LINE
        muted = skin.mutedHex?.let { Color(it) } ?: D_MUTED
        gold = skin.goldHex?.let { Color(it) } ?: D_GOLD
        bluetooth = skin.bleHex?.let { Color(it) } ?: D_BLUE
        cell = skin.cellHex?.let { Color(it) } ?: nInk
    }
}

object UiFlags {
    var darkMap by mutableStateOf(false)
    var reduceMotion by mutableStateOf(false)

    var offensiveBanners by mutableStateOf(true)
    var captureBanners by mutableStateOf(true)
    var achievementBanners by mutableStateOf(true)
    fun seed(darkMap: Boolean, reduceMotion: Boolean, offensiveBanners: Boolean, captureBanners: Boolean, achievementBanners: Boolean) {
        this.darkMap = darkMap; this.reduceMotion = reduceMotion
        this.offensiveBanners = offensiveBanners; this.captureBanners = captureBanners; this.achievementBanners = achievementBanners
    }
}

object MapLayerFilter {
    var enabled by mutableStateOf(MapMarkerIcon.values().toSet()); private set

    fun seed(ctx: android.content.Context) {
        val saved = ctx.getSharedPreferences("warble", android.content.Context.MODE_PRIVATE)
            .getStringSet("map_layer_filter", null)
        enabled = if (saved == null) MapMarkerIcon.values().toSet()
                  else saved.mapNotNull { runCatching { MapMarkerIcon.valueOf(it) }.getOrNull() }.toSet()
    }
    fun set(ctx: android.content.Context, icons: Set<MapMarkerIcon>) {
        enabled = icons
        ctx.getSharedPreferences("warble", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("map_layer_filter", icons.map { it.name }.toSet()).apply()
    }

    fun toggle(ctx: android.content.Context, icons: Set<MapMarkerIcon>) =
        set(ctx, if (enabled.containsAll(icons)) enabled - icons else enabled + icons)
}

val Mono = FontFamily.Monospace

private fun luminance(c: Color): Double {
    fun lin(v: Float): Double {
        val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}
private fun contrast(a: Color, b: Color): Double {
    val la = luminance(a); val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
fun inkOn(fill: Color): Color =
    if (contrast(Palette.paper, fill) >= contrast(Palette.ink, fill)) Palette.paper else Palette.ink

fun Modifier.glassAction(tint: Color, shape: Shape = RoundedCornerShape(12.dp)): Modifier = this
    .shadow(9.dp, shape, spotColor = tint.copy(alpha = 0.28f), ambientColor = tint.copy(alpha = 0.28f))
    .clip(shape)
    .background(Palette.surface, shape)
    .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.30f), tint.copy(alpha = 0.14f))), shape)
    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.12f), 0.5f to Color.Transparent), shape)
    .border(1.dp, tint.copy(alpha = 0.55f), shape)

fun iconFor(token: String, category: String): ImageVector {

    when {
        token.contains("applewatch") -> return Icons.Filled.Watch
        token.contains("airpod") || token.contains("headphones") -> return Icons.Filled.Headphones
        token.contains("laptop") || token.contains("macbook") -> return Icons.Filled.Computer
        token.contains("ipad") -> return Icons.Filled.Tablet
        token.contains("iphone") -> return Icons.Filled.Smartphone
        token.contains("figure.run") -> return Icons.Filled.FitnessCenter
        token.contains("mappin") -> return Icons.Filled.MyLocation
        token.contains("keyboard") -> return Icons.Filled.Keyboard
    }
    return when (category) {
        "Phone" -> Icons.Filled.Smartphone
        "Computer" -> Icons.Filled.Computer
        "Tablet" -> Icons.Filled.Tablet
        "Watch" -> Icons.Filled.Watch
        "Audio", "Earbuds", "Headphones" -> Icons.Filled.Headphones
        "Speaker" -> Icons.Filled.Speaker
        "TV" -> Icons.Filled.Tv
        "Smart home" -> Icons.Filled.Home
        "Light" -> Icons.Filled.Lightbulb
        "Lock" -> Icons.Filled.Lock
        "Fitness", "Gym" -> Icons.Filled.FitnessCenter
        "Health monitor", "Health" -> Icons.Filled.MonitorHeart
        "Tracker" -> Icons.Filled.MyLocation
        "Beacon" -> Icons.Filled.Sensors
        "Keyboard", "Input" -> Icons.Filled.Keyboard
        "Printer" -> Icons.Filled.Print
        "Camera" -> Icons.Filled.PhotoCamera
        "Vehicle" -> Icons.Filled.DirectionsCar
        "Game", "Controller" -> Icons.Filled.SportsEsports
        "Vacuum", "Robot vacuum" -> Icons.Filled.CleaningServices
        "E-reader" -> Icons.Filled.MenuBook
        "WiFi AP" -> Icons.Filled.Wifi
        "Router" -> Icons.Filled.Router
        "Cell tower" -> Icons.Filled.CellTower
        else -> Icons.Filled.Bluetooth
    }
}
