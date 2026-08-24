package com.rocketgod.warble.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material.icons.filled.LockOpen
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.SendPhase
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.FeedEvent
import com.rocketgod.warble.model.FeedKind
import com.rocketgod.warble.model.FeedTone
import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.model.Skin
import com.rocketgod.warble.model.SortMode
import com.rocketgod.warble.model.TypeFilter
import com.rocketgod.warble.model.Stats
import com.rocketgod.warble.usb.BeastState
import com.rocketgod.warble.usb.GhostEsp

@Composable
fun pressable(onClick: () -> Unit): Pair<Modifier, Float> {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    return Modifier.clickable(interactionSource = src, indication = null) { onClick() } to scale
}

@Composable
private fun RollingNumber(value: Long, color: Color, fontSize: TextUnit, shadow: Shadow? = null, compact: Boolean = false) {
    val text = if (compact) fmtCompact(value) else fmtGrouped(value)
    val style = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = fontSize, color = color, shadow = shadow
    )

    if (UiFlags.reduceMotion) {
        Text(text, style = style, maxLines = 1)
        return
    }

    Row(verticalAlignment = Alignment.Bottom) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        if (targetState >= initialState) {
                            (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                        } else {
                            (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
                        }
                    },
                    label = "digit"
                ) { c -> Text(c.toString(), style = style, maxLines = 1) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunScore(label: String, value: Long, accent: Color, modifier: Modifier, onPickAll: () -> Unit) {
    val scale = remember { Animatable(1f) }
    var prev by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value > prev && !UiFlags.reduceMotion) {

            scale.animateTo(1.9f, spring(dampingRatio = 0.3f, stiffness = 2400f))
            scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 120f))
        }
        prev = value
    }
    val s = scale.value
    val popping = s > 1.05f
    val wobble = (s - 1f) * 6f
    Column(
        modifier.combinedClickable(onClick = onPickAll, onLongClick = onPickAll),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.graphicsLayer { scaleX = s; scaleY = s; rotationZ = wobble }, contentAlignment = Alignment.Center) {
            RollingNumber(value, accent, 25.sp, if (popping) Shadow(accent.copy(alpha = 0.9f), Offset.Zero, 22f) else null)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun Tally(label: String, value: Long, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RollingNumber(value, color, 25.sp, compact = true)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
    }
}

private enum class BannerCard { NEW, SPLIT, HASH, RATE, DISTANCE, OPEN, SPEED }
private class BannerStats(val phoneAp: Int, val extAp: Int, val totalAp: Int, val open: Int)

@Composable
internal fun AutoSizeText(text: String, color: Color, maxSize: TextUnit, minSize: TextUnit = 15.sp,
                        modifier: Modifier = Modifier) {

    var fs by remember(text.length, maxSize) { mutableStateOf(maxSize) }
    Text(text, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = fs,
        maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        onTextLayout = { r -> if (r.hasVisualOverflow && fs.value > minSize.value) fs = (fs.value * 0.92f).sp },
        modifier = modifier)
}

private enum class DashTool(val label: String) { RADAR("Radar"), DEVICE("Device"), LOCATOR("Device Locator"), MAP("Finds Map"), THREAT("Offensive Devices"), CHANNELS("Channel Analyzer"), SPECTRUM("Spectrum Analyzer") }

@Composable
private fun LiveBanner(
    feedIn: FeedEvent?, accent: Color, scanning: Boolean,
    contacts: List<Contact>, newWifi: Long, speedMps: Float?, bearingDeg: Float?,
    distanceM: Double, ratePerMin: Int, externalEngaged: Boolean,
    bannerMode: Int, onCycleMode: () -> Unit, useMetric: Boolean = false,
    wiglePhase: SendPhase = SendPhase.IDLE, wdgwPhase: SendPhase = SendPhase.IDLE,
    uploadStatus: String? = null,
    hashesThisRun: Long = 0L,
) {

    val feed = feedIn?.takeUnless { e ->
        when (e.kind) {
            com.rocketgod.warble.model.FeedKind.SPOTTED -> !UiFlags.offensiveBanners
            com.rocketgod.warble.model.FeedKind.CAPTURE -> !UiFlags.captureBanners
            com.rocketgod.warble.model.FeedKind.MILESTONE,
            com.rocketgod.warble.model.FeedKind.UNLOCK,
            com.rocketgod.warble.model.FeedKind.BEST,
            com.rocketgod.warble.model.FeedKind.RANK -> !UiFlags.achievementBanners
        }
    }

    val tone = feed?.colorArgb?.let { Color(it) } ?: when {
        feed?.kind == com.rocketgod.warble.model.FeedKind.CAPTURE -> Color(0xFFFF2BD1)
        feed?.tone == FeedTone.GOLD || feed?.tone == FeedTone.HOT -> Palette.gold
        else -> accent
    }
    val ink = inkOn(tone)

    val pop = remember { Animatable(1f) }
    LaunchedEffect(feed) {
        if (feed != null && !UiFlags.reduceMotion) {

            val capture = feed.kind == com.rocketgod.warble.model.FeedKind.CAPTURE || feed.kind == com.rocketgod.warble.model.FeedKind.SPOTTED
            pop.snapTo(if (capture) 1.22f else 1.18f)
            pop.animateTo(1f, spring(dampingRatio = if (capture) 0.4f else 0.32f, stiffness = if (capture) 420f else 480f))
        }
    }
    AnimatedContent(
        targetState = feed,
        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
        label = "banner"
    ) { e ->
        val isCapture = e?.kind == com.rocketgod.warble.model.FeedKind.CAPTURE || e?.kind == com.rocketgod.warble.model.FeedKind.SPOTTED
        Box(
            Modifier.fillMaxWidth().height(56.dp)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .then(if (e != null) Modifier.shadow(18.dp, RoundedCornerShape(12.dp), spotColor = tone, ambientColor = tone) else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .background(tone)
                .clickable { onCycleMode() }

                .then(if (isCapture) Modifier else Modifier.padding(start = 20.dp, end = 26.dp)),
            contentAlignment = Alignment.CenterStart
        ) {

            if (!isCapture) androidx.compose.foundation.Canvas(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))) {
                val gap = 11.dp.toPx(); var gx = -size.height
                while (gx < size.width) {
                    drawLine(ink.copy(alpha = 0.04f),
                        start = androidx.compose.ui.geometry.Offset(gx, size.height),
                        end = androidx.compose.ui.geometry.Offset(gx + size.height, 0f), strokeWidth = 1f)
                    gx += gap
                }
            }
            if (e == null) {

                val bs = run {
                    var phoneAp = 0; var extAp = 0; var totalAp = 0; var open = 0
                    for (c in contacts) if (c.type == com.rocketgod.warble.model.SignalType.WIFI) {
                        if (c.category != "WiFi Client") {
                            totalAp++
                            if (c.liveByPhone) phoneAp++
                            if (c.liveByMonitor) extAp++
                            if (isOpenWifi(c.capabilities)) open++
                        }
                    }
                    BannerStats(phoneAp, extAp, totalAp, open)
                }
                val allCards = BannerCard.values()

                val autoCards = if (hashesThisRun > 0) allCards.toList() else allCards.filter { it != BannerCard.HASH }
                var tick by remember { mutableStateOf(0) }
                LaunchedEffect(bannerMode) { if (bannerMode == 0) while (true) { kotlinx.coroutines.delay(4000); tick++ } }
                val card = if (bannerMode == 0) autoCards[tick % autoCards.size]
                           else allCards[(bannerMode - 1).coerceIn(0, allCards.size - 1)]
                val uploadStep = uploadStatus
                if (uploadStep != null) {

                    val tr = rememberInfiniteTransition(label = "uplPulse")
                    val a by tr.animateFloat(0.5f, 1f,
                        infiniteRepeatable(tween(600), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(uploadStep, color = ink.copy(alpha = a), fontFamily = Mono, fontWeight = FontWeight.Bold,
                            fontSize = 17.sp, maxLines = 1, softWrap = false)
                    }
                } else if (!scanning) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Paused", color = ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1)
                    }
                } else {
                    AnimatedContent(
                        targetState = card,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "card",
                        modifier = Modifier.fillMaxWidth()
                    ) { c ->
                        if (c == BannerCard.SPEED) {

                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    Text(if ((speedMps ?: 0f) > 1f) headingLabel(bearingDeg) else "—",
                                        color = ink.copy(alpha = 0.78f), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, softWrap = false)
                                }
                                Text(speedLabel(speedMps, useMetric), color = ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 24.sp, maxLines = 1, softWrap = false)
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                    Text(distanceLabel(distanceM, useMetric), color = ink.copy(alpha = 0.78f), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        } else if (c == BannerCard.NEW) {

                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Wifi, null, tint = ink.copy(alpha = 0.65f), modifier = Modifier.size(26.dp))
                                AutoSizeText("+$newWifi", ink, maxSize = 32.sp, minSize = 18.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.Wifi, null, tint = ink.copy(alpha = 0.65f), modifier = Modifier.size(26.dp))
                            }
                        } else {
                            val txt = when (c) {
                                BannerCard.NEW -> "+$newWifi new WiFi"
                                BannerCard.SPLIT -> if (externalEngaged) "📱 ${bs.phoneAp} · ⚡ ${bs.extAp} · 🔀 ${bs.totalAp}"
                                                    else "📱 ${bs.phoneAp} APs"
                                BannerCard.HASH -> "🔑 $hashesThisRun captured"
                                BannerCard.RATE -> "$ratePerMin new/min"
                                BannerCard.DISTANCE -> "${distanceLabel(distanceM, useMetric)} driven"
                                BannerCard.OPEN -> "${bs.open} open WiFi"
                                BannerCard.SPEED -> ""
                            }
                            AutoSizeText(txt, ink, maxSize = 30.sp, minSize = 15.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } else if (e.kind == com.rocketgod.warble.model.FeedKind.CAPTURE || e.kind == com.rocketgod.warble.model.FeedKind.SPOTTED) {
                CaptureExplosion(e)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(34.dp).background(ink.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(feedIcon(e.kind), null, tint = ink, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(e.eyebrow, color = ink.copy(alpha = 0.72f), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(e.title, color = ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                }
            }
        }
    }
}

private fun uploadStepLabel(w: SendPhase, d: SendPhase): String? {
    fun one(svc: String, p: SendPhase): String? = when (p) {
        SendPhase.WRITING -> "✍️ Writing $svc file"
        SendPhase.UPLOADING -> "📡 Sending to $svc"
        SendPhase.SAVING -> "💾 Saving $svc"
        SendPhase.SENT -> "✅ Sent to $svc"
        SendPhase.IDLE -> null
    }
    val ws = one("WiGLE", w); val ds = one("WDGWars", d)
    return when {
        ws != null && ds != null && w == d -> when (w) {
            SendPhase.WRITING -> "✍️ Writing upload files"
            SendPhase.UPLOADING -> "📡 Sending → WiGLE + WDGWars"
            SendPhase.SAVING -> "💾 Saving upload"
            SendPhase.SENT -> "✅ Sent to WiGLE + WDGWars"
            SendPhase.IDLE -> null
        }
        ws != null && ds != null -> "$ws · $ds"
        else -> ws ?: ds
    }
}

private fun isOpenWifi(caps: String?): Boolean {
    if (caps.isNullOrBlank()) return false
    return !caps.contains("WPA") && !caps.contains("WEP") && !caps.contains("RSN") && !caps.contains("SAE") && !caps.contains("EAP")
}

private fun distanceLabel(m: Double, metric: Boolean): String =
    if (metric) "%.1f km".format(m / 1000.0) else "%.1f mi".format(m / 1609.344)

private fun speedLabel(mps: Float?, metric: Boolean): String =
    mps?.let { if (metric) "${Math.round(it * 3.6f)} km/h" else "${Math.round(it * 2.23694f)} mph" }
        ?: if (metric) "0 km/h" else "0 mph"

private fun headingLabel(bearing: Float?): String {
    val b = bearing ?: return "—"
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[Math.round((b % 360f) / 45f) % 8]
}

@Composable
private fun CaptureExplosion(e: FeedEvent) {

    val neon = e.colorArgb?.let { Color(it) } ?: Color(0xFFFF2BD1)
    val neon2 = androidx.compose.ui.graphics.lerp(neon, Color.White, 0.4f)
    val bg1 = androidx.compose.ui.graphics.lerp(neon, Color.Black, 0.86f)
    val bg2 = androidx.compose.ui.graphics.lerp(neon, Color.Black, 0.74f)

    val label = if (e.eyebrow.isBlank()) e.title else "${e.eyebrow} ${e.title}"
    val (fs, ls) = when {
        label.length > 28 -> 13.sp to 0.5.sp
        label.length > 22 -> 15.sp to 1.sp
        label.length > 16 -> 18.sp to 1.5.sp
        else              -> 22.sp to 2.sp
    }

    if (UiFlags.reduceMotion) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(bg1, bg2, bg1)))
                .border(2.dp, neon, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Black,
                fontSize = fs, letterSpacing = ls, maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                style = TextStyle(shadow = Shadow(neon2, blurRadius = 24f)))
        }
        return
    }

    val tr = rememberInfiniteTransition(label = "cap")
    val pulse by tr.animateFloat(0f, 1f,
        infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    val beamPos by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1150, easing = LinearEasing)), label = "beam")
    val sweep by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1050, easing = LinearEasing)), label = "sweep")
    val textScale = 1f + 0.04f * pulse
    val glow = 0.55f + 0.45f * pulse

    Box(
        Modifier.fillMaxSize()
            .shadow(26.dp, RoundedCornerShape(12.dp), spotColor = neon.copy(alpha = glow), ambientColor = neon)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(bg1, bg2, bg1))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 2.5.dp.toPx()
            val rr = androidx.compose.ui.geometry.RoundRect(
                stroke, stroke, size.width - stroke, size.height - stroke,
                androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
            )
            val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(rr) }
            val pm = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, true) }
            val total = pm.length
            if (total > 0f) {
                drawPath(path, neon.copy(alpha = 0.2f + 0.3f * pulse), style = Stroke(width = 1.5.dp.toPx()))
                val beamLen = total * 0.26f
                for (b in 0..1) {
                    val head = ((beamPos + b * 0.5f) % 1f) * total
                    val seg = androidx.compose.ui.graphics.Path()
                    val end = head + beamLen
                    if (end <= total) pm.getSegment(head, end, seg, true)
                    else { pm.getSegment(head, total, seg, true); pm.getSegment(0f, end - total, seg, true) }
                    drawPath(seg, neon.copy(alpha = 0.55f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(seg, Color.White, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            val bx = -0.25f * size.width + sweep * 1.5f * size.width
            drawRect(Brush.linearGradient(
                0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent,
                start = Offset(bx - 70f, 0f), end = Offset(bx + 70f, 0f)))
        }

        Text(
            label,
            color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Black,
            fontSize = fs, letterSpacing = ls, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp)
                .graphicsLayer { scaleX = textScale; scaleY = textScale },
            style = TextStyle(shadow = Shadow(neon2, blurRadius = 24f))
        )
    }
}

private fun feedIcon(k: FeedKind): ImageVector = when (k) {
    FeedKind.MILESTONE -> Icons.Filled.Timeline
    FeedKind.BEST -> Icons.Filled.LocalFireDepartment
    else -> Icons.Filled.EmojiEvents
}

@Composable
fun Dashboard(
    stats: Stats,
    liveCount: Int,
    contacts: List<Contact>,
    categoryTally: List<Pair<String, Int>>,
    feed: FeedEvent?,
    skin: Skin,
    scanning: Boolean,
    beast: BeastState,
    fwStatus: String? = null,
    wigleLifeWifi: Long = -1L,
    wigleLifeBt: Long = -1L,
    wigleLifeCell: Long = -1L,
    gnss: com.rocketgod.warble.core.GnssStat = com.rocketgod.warble.core.GnssStat(0, 0, emptyList()),
    newWifi: Long = 0L,
    newBle: Long = 0L,
    newCell: Long = 0L,
    onSatellites: () -> Unit = {},
    sortMode: SortMode,
    onSort: (SortMode) -> Unit,
    typeFilter: TypeFilter,
    onFilter: (TypeFilter) -> Unit,
    maxWifi: Boolean = false,
    useMetric: Boolean = false,
    blockedKeys: Set<String> = emptySet(),
    onSelect: (Contact) -> Unit,
    onLeaderboards: () -> Unit,
    onFieldReport: () -> Unit,
    onQuickUpload: () -> Unit = {},
    wiglePhase: SendPhase = SendPhase.IDLE,
    wdgwPhase: SendPhase = SendPhase.IDLE,
    uploadStatus: String? = null,
    hashesThisRun: Long = 0L,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    radarCollapsed: Boolean = false,
    onRadarCollapsed: (Boolean) -> Unit = {},
    speedMps: Float? = null,
    bearingDeg: Float? = null,
    distanceM: Double = 0.0,
    discoveryRate: Int = 0,
    bannerMode: Int = 0,
    onCycleBannerMode: () -> Unit = {},
    locateRequest: String? = null,
    onLocateHandled: () -> Unit = {},
    toolsOnTop: Boolean = false,
    seenCollapsed: Boolean = false,
    onSeenCollapsed: (Boolean) -> Unit = {},
    toolOrd: Int = 0,
    onToolOrd: (Int) -> Unit = {},
    currentPose: () -> Triple<Double, Double, Float?>? = { null },
    loadMapPointsIn: suspend (Double, Double, Double, Double) -> List<MapPoint> = { _, _, _, _ -> emptyList() },
    onOpenFullMap: () -> Unit = {},
    loadMapMarkersIn: suspend (Double, Double, Double, Double) -> List<MapMarker> = { _, _, _, _ -> emptyList() },
    onOpenMapMarker: (MapMarker) -> Unit = {},
    onOpenMapPoint: (String) -> Unit = {},
    mapSeedCenter: suspend () -> Pair<Double, Double>? = { null }
) {
    val accent = Color(skin.accentHex)

    val wifiColor = skin.wifiHex?.let { Color(it) } ?: accent
    val deep = Color(skin.deepHex)

    val tools = if (beast is BeastState.Engaged) listOf(DashTool.RADAR, DashTool.DEVICE, DashTool.LOCATOR, DashTool.MAP, DashTool.THREAT, DashTool.CHANNELS, DashTool.SPECTRUM)
                else listOf(DashTool.RADAR, DashTool.LOCATOR, DashTool.MAP, DashTool.THREAT, DashTool.CHANNELS, DashTool.SPECTRUM)
    val tool = DashTool.values().getOrNull(toolOrd)?.takeIf { it in tools } ?: DashTool.RADAR

    var trackKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(locateRequest) {
        val k = locateRequest ?: return@LaunchedEffect
        trackKey = k
        onToolOrd(DashTool.LOCATOR.ordinal)
        onRadarCollapsed(false)
        listState.animateScrollToItem(0)
        onLocateHandled()
    }

    LaunchedEffect(skin.accentHex) { GhostEsp.accentArgb = skin.accentHex.toInt() }

    var excludedOnly by remember { mutableStateOf(false) }
    val liveSorted = remember(contacts, sortMode, typeFilter, excludedOnly, blockedKeys) {
        var filtered = typeFilter.type?.let { t -> contacts.filter { it.type == t } } ?: contacts
        if (excludedOnly) filtered = filtered.filter { it.key.lowercase() in blockedKeys }
        when (sortMode) {
            SortMode.SIGNAL -> filtered.sortedByDescending { it.rssi }
            SortMode.SEEN -> filtered.sortedByDescending { it.timesSeen }
            SortMode.RECENT -> filtered.sortedByDescending { it.lastSeen }
            SortMode.NAME -> filtered.sortedBy { (it.name ?: it.maker ?: it.category).lowercase() }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().background(Palette.paper).padding(horizontal = 16.dp)) {
        item {

            Column(if (!radarCollapsed) Modifier.fillParentMaxHeight() else Modifier) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Wifi, null, tint = accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("War", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Text("drive", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Text(" Go", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Spacer(Modifier.width(8.dp))
                if (beast is BeastState.Engaged) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.background(accent, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Bolt, null, tint = inkOn(accent), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("BEAST", color = inkOn(accent), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.weight(1f))

                Icon(Icons.Filled.Settings, "settings", tint = Palette.muted, modifier = Modifier.size(26.dp).clickable { onHelp() })
            }

            when {
                beast is BeastState.Engaged && fwStatus != null ->
                    Text("⚡ ${reconcileHeader(fwStatus, contacts)}", color = accent, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp))
                fwStatus != null ->
                    Text("Adapter disconnected · on-board scanning", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            LiveBanner(feed, accent, scanning, contacts, newWifi, speedMps, bearingDeg,
                distanceM, discoveryRate, beast is BeastState.Engaged, bannerMode, onCycleBannerMode, useMetric,
                wiglePhase, wdgwPhase, uploadStatus = uploadStatus, hashesThisRun = hashesThisRun)
            Spacer(Modifier.height(12.dp))

            val sessionCard: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                Column(
                    Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(14.dp))
                        .background(Palette.surface, RoundedCornerShape(14.dp)).padding(vertical = 14.dp, horizontal = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RunScore("NEW WIFI", newWifi, wifiColor, Modifier.weight(1f), onPickAll = {})
                        VDivider()
                        Tally("NEW BLUETOOTH", newBle, Palette.bluetooth, Modifier.weight(1f))
                        VDivider()
                        Tally("NEW CELL", newCell, Palette.cell, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.4f)))
                    Spacer(Modifier.height(12.dp))
                    SatelliteContent(gnss, accent, onSatellites)
                }
            }

            val toolCenter: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                if (!radarCollapsed) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .background(Palette.surface, RoundedCornerShape(12.dp)).padding(12.dp)
                    ) {
                        when (tool) {
                            DashTool.RADAR -> Radar(contacts, accent, radarPaint = skin.radarPaint, onSelect = onSelect)
                            DashTool.DEVICE -> DeviceReadout(beast, fwStatus, contacts, accent)
                            DashTool.LOCATOR -> DeviceLocator(
                                target = if (trackKey != null) contacts.firstOrNull { it.key == trackKey }
                                         else contacts.maxByOrNull { it.smoothRssi },
                                chosen = trackKey != null,
                                accent = accent,
                                onChooseDevice = { scope.launch { listState.animateScrollToItem(1) } }
                            )
                            DashTool.MAP -> FindsMapTool(
                                accent = accent,
                                currentPose = currentPose,
                                loadPointsIn = loadMapPointsIn,
                                onOpenFull = onOpenFullMap,
                                loadMarkersIn = loadMapMarkersIn,
                                onMarkerTap = onOpenMapMarker,
                                onOpenPoint = onOpenMapPoint,
                                seedCenter = mapSeedCenter
                            )
                            DashTool.THREAT -> ThreatScopeTool(contacts, accent, onSelect = onSelect)
                            DashTool.CHANNELS -> ChannelAnalyzerTool(contacts, accent)
                            DashTool.SPECTRUM -> SpectrumAnalyzerTool(contacts, accent)
                        }

                        if (tools.size > 1) {
                            ToolSwitchButton(
                                accent,
                                Modifier.align(Alignment.TopStart).zIndex(1f).padding(6.dp)
                            ) { onToolOrd(tools[(tools.indexOf(tool).coerceAtLeast(0) + 1) % tools.size].ordinal) }
                        }

                        Box(
                            Modifier.align(Alignment.TopEnd).zIndex(1f).padding(6.dp).size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Palette.surface, RoundedCornerShape(8.dp))
                                .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                                .clickable { onRadarCollapsed(true) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Remove, "collapse tool center", tint = accent, modifier = Modifier.size(18.dp)) }
                    }
                } else {
                    RadarCollapsedBar(stats, liveCount, accent) { onRadarCollapsed(false) }
                }
            }
            if (toolsOnTop) {
                toolCenter(); Spacer(Modifier.height(12.dp)); sessionCard()
            } else {
                sessionCard(); Spacer(Modifier.height(12.dp)); toolCenter()
            }
            }
            Spacer(Modifier.height(12.dp))

            UploadRunButton(accent, deep, cooking = wiglePhase != SendPhase.IDLE || wdgwPhase != SendPhase.IDLE, onQuickUpload)
            Spacer(Modifier.height(12.dp))
            LeaderboardButton(accent, deep, onLeaderboards)
            Spacer(Modifier.height(12.dp))
            FieldReportButton(accent, deep, onFieldReport)
            Spacer(Modifier.height(14.dp))

            if (categoryTally.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    CategoryChip(Icons.Filled.QueryStats, "${stats.runs}", "runs", accent)

                    categoryTally.filterNot { it.first in setOf("WiFi AP", "WiFi Client", "Cell tower") }
                        .take(10).forEach { (cat, n) -> CategoryChip(iconFor("", cat), "$n", cat, accent) }
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(
                Modifier.fillMaxWidth().clickable { onSeenCollapsed(!seenCollapsed) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("CURRENTLY SEEN (${contacts.size})", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(if (seenCollapsed) "Tap to expand the live list" else "Live now — drops into the field report after 30s of quiet",
                        color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
                }
                Icon(if (seenCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    if (seenCollapsed) "expand" else "collapse", tint = accent, modifier = Modifier.size(28.dp))
            }
            if (!seenCollapsed) {
            Spacer(Modifier.height(8.dp))

            run {
                val now = System.currentTimeMillis()
                fun live(c: Contact) = now - c.lastSeen < 10_000L
                val intAp = contacts.count { it.type == SignalType.WIFI && it.category != "WiFi Client" && it.liveByPhone }
                val extAp = contacts.count { it.type == SignalType.WIFI && it.category != "WiFi Client" && it.liveByMonitor }
                val extCli = contacts.count { it.type == SignalType.WIFI && it.category == "WiFi Client" && it.liveByMonitor }
                val bt = contacts.count { it.type == SignalType.BLE && live(it) }
                val cell = contacts.count { it.type == SignalType.CELL && live(it) }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TallyPip(Icons.Filled.Wifi, intAp, "AP", accent, bolt = false)
                    TallyPip(Icons.Filled.Wifi, extAp, "AP", Palette.gold, bolt = true)
                    TallyPip(Icons.Filled.Wifi, extCli, "client", Palette.gold, bolt = true)
                    TallyPip(Icons.Filled.Bluetooth, bt, "BT", accent, bolt = false)
                    TallyPip(Icons.Filled.CellTower, cell, "cell", accent, bolt = false)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text("sort", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp, top = 8.dp))
                SortMode.values().forEach { m ->
                    val sel = m == sortMode
                    Box(
                        Modifier.padding(end = 6.dp)
                            .border(1.dp, if (sel) Color.Transparent else Palette.line, RoundedCornerShape(6.dp))
                            .background(if (sel) accent else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { onSort(m) }.padding(horizontal = 12.dp, vertical = 7.dp)
                    ) { Text(m.label, color = if (sel) inkOn(accent) else Palette.muted, fontFamily = Mono, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text("filter", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp, top = 8.dp))
                TypeFilter.values().forEach { f ->

                    val sel = f == typeFilter && !excludedOnly
                    Box(
                        Modifier.padding(end = 6.dp)
                            .border(1.dp, if (sel) Color.Transparent else Palette.line, RoundedCornerShape(6.dp))
                            .background(if (sel) accent else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { onFilter(f); excludedOnly = false }.padding(horizontal = 12.dp, vertical = 7.dp)
                    ) { Text(f.label, color = if (sel) inkOn(accent) else Palette.muted, fontFamily = Mono, fontSize = 13.sp) }
                }

                Box(
                    Modifier.padding(end = 6.dp)
                        .border(1.dp, if (excludedOnly) Color.Transparent else Palette.line, RoundedCornerShape(6.dp))
                        .background(if (excludedOnly) accent else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { excludedOnly = !excludedOnly }.padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text("Excluded", color = if (excludedOnly) inkOn(accent) else Palette.muted, fontFamily = Mono, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(6.dp))
            if (liveSorted.isEmpty()) {

                val offByMaxWifi = maxWifi && (typeFilter == TypeFilter.BLE || typeFilter == TypeFilter.CELL)
                val msg = if (offByMaxWifi)
                    "Max Wi-Fi mode is on, so ${typeFilter.label} scanning is off. Turn off Max Wi-Fi in Settings to see these."
                else "Nothing in range right now."
                Text(msg, color = if (offByMaxWifi) accent else Palette.muted, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
            }
            }
        }
        if (!seenCollapsed) items(liveSorted, key = { "live_" + it.key }) { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(c) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Icon(iconFor(c.icon, c.category), null, tint = if (c.identified) accent else Palette.muted, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name ?: "— not advertised", color = Palette.ink, fontFamily = Mono, fontSize = 16.sp, maxLines = 1)
                    Text(c.maker ?: c.category, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
                }

                if (c.seenByMonitor && !c.seenByPhone) {
                    Icon(Icons.Filled.Bolt, "monitor", tint = Palette.gold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text("${c.rssi}dBm", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))

                Icon(Icons.Filled.GpsFixed, "locate device",
                    tint = if (c.key == trackKey) accent else Palette.muted,
                    modifier = Modifier.size(22.dp).clickable {
                        trackKey = c.key
                        onToolOrd(DashTool.LOCATOR.ordinal)
                        onRadarCollapsed(false)
                        scope.launch { listState.animateScrollToItem(0) }
                    })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line.copy(alpha = 0.35f)))
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun VDivider() { Box(Modifier.width(1.dp).height(52.dp).background(Palette.line)) }

@Composable
private fun TallyPip(icon: ImageVector, value: Int, label: String, tint: Color, bolt: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        if (bolt) Icon(Icons.Filled.Bolt, null, tint = Palette.gold, modifier = Modifier.size(13.dp))
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text("$value", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(3.dp))
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SignalTally(icon: ImageVector, label: String, n: Long, accent: Color, selected: Boolean, modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier
            .combinedClickable(onClick = onPick, onLongClick = onPick)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(fmtGrouped(n), color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = if (selected) accent else Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    }
}

@Composable
private fun CategoryChip(icon: ImageVector, value: String, label: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp).border(1.dp, Palette.line, RoundedCornerShape(10.dp))
            .background(Palette.surface, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(value, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.width(5.dp))
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun LeaderboardButton(accent: Color, deep: Color, onClick: () -> Unit) {
    val (mod, scale) = pressable(onClick)
    val tint = trophyTint(accent)
    Box(mod.scale(scale).fillMaxWidth().glassAction(accent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            ShinyTrophy(tint, 18.dp, 0)
            Spacer(Modifier.width(14.dp))
            Text("VIEW LEADERBOARDS", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(14.dp))
            ShinyTrophy(tint, 18.dp, 600)
        }
        SheenSweep(12.dp)
    }
}

@Composable
private fun ToolSwitchButton(accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val hint = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (UiFlags.reduceMotion) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        repeat(3) {
            hint.animateTo(1f, tween(430, easing = FastOutSlowInEasing))
            hint.animateTo(0f, tween(430, easing = FastOutSlowInEasing))
        }
    }
    val p = hint.value
    Box(
        modifier.size(30.dp).scale(1f + 0.12f * p)
            .shadow((7f * p).dp, RoundedCornerShape(8.dp), spotColor = accent, ambientColor = accent)

            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surface, RoundedCornerShape(8.dp))
            .border((1f + 1.6f * p).dp, lerp(Palette.line, accent, p), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Filled.Apps, "switch tool", tint = accent, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun RadarCollapsedBar(stats: Stats, liveCount: Int, accent: Color, onExpand: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp)).padding(14.dp)
    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Apps, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Tools hidden", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Still scanning — tap + to show", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
            }
            Box(
                Modifier.size(34.dp).background(accent, RoundedCornerShape(9.dp)).clickable { onExpand() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Add, "show tools", tint = inkOn(accent), modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun DeviceLocator(target: Contact?, chosen: Boolean, accent: Color, onChooseDevice: () -> Unit) {

    var last by remember { mutableStateOf(target) }
    if (target != null) last = target
    val shown = target ?: last
    val live = target != null
    if (shown == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (chosen) "Searching…" else "Nothing in range to locate", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                ChooseDeviceChip(accent, onChooseDevice)
            }
        }
        return
    }
    val rssi = Math.round(shown.smoothRssi).toInt()
    val rssiNow = androidx.compose.runtime.rememberUpdatedState(rssi)
    val liveNow = androidx.compose.runtime.rememberUpdatedState(live)

    var prev by remember { mutableStateOf(rssi) }
    var trend by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1000); if (liveNow.value) { trend = rssiNow.value - prev; prev = rssiNow.value } }
    }
    val frac = ((rssi + 100) / 70f).coerceIn(0.02f, 1f)
    val gaugeColor = when {
        !live -> Palette.muted
        rssi >= -60 -> Color(0xFF7CFC00); rssi >= -75 -> Color(0xFFFFC400); else -> Color(0xFFFF5B52)
    }
    val (distLabel, distSub) = when {
        rssi >= -50 -> "Very Close" to "< 1 m"
        rssi >= -60 -> "Close" to "~1-3 m"
        rssi >= -70 -> "Nearby" to "~3-8 m"
        rssi >= -80 -> "Far" to "~8-20 m"
        else -> "Very Far" to "faint signal"
    }
    val trendLabel = when { !live -> "Signal lost"; trend > 2 -> "Getting closer"; trend < -2 -> "Getting weaker"; else -> "Signal stable" }

    var vibrate by remember { mutableStateOf(true) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vibrate) {
        if (!vibrate) return@LaunchedEffect
        val vib = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        while (vibrate) {
            if (liveNow.value) runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    vib?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") vib?.vibrate(40)
            }
            val r = rssiNow.value
            kotlinx.coroutines.delay(if (!liveNow.value) 700L else when { r >= -50 -> 250L; r >= -60 -> 450L; r >= -70 -> 800L; r >= -80 -> 1400L; else -> 2200L })
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(150.dp)) {
                val sw = 15.dp.toPx()
                drawArc(Palette.line, -90f, 360f, false, style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(gaugeColor, -90f, 360f * frac, false, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$rssi dB", color = gaugeColor, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, maxLines = 1)
                Text(trendLabel, color = if (live) Palette.muted else Color(0xFFFF5B52), fontFamily = Mono, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(if (live) distLabel else "Out of range", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
        Text(if (live) "Approximate distance · $distSub" else "last known signal", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Text(shown.name ?: "Unknown device", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        Text(shown.maker ?: shown.category, color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(8.dp))
                    .clickable { vibrate = !vibrate }.padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Icon(Icons.Filled.Vibration, null, tint = if (vibrate) accent else Palette.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (vibrate) "Vibrating" else "Silent", color = if (vibrate) accent else Palette.muted, fontFamily = Mono, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            ChooseDeviceChip(accent, onChooseDevice)
        }
    }
}

@Composable
private fun ChooseDeviceChip(accent: Color, onChoose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(8.dp))
            .clickable { onChoose() }.padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Icon(Icons.Filled.GpsFixed, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Choose device", color = accent, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun DeviceReadout(beast: BeastState, fwStatus: String?, contacts: List<Contact>, accent: Color) {
    val a = (beast as? BeastState.Engaged)?.adapter
    if (a == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bolt, null, tint = Palette.muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text("No external device", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Plug in a supported adapter over USB-OTG", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
            }
        }
        return
    }
    val now = System.currentTimeMillis()

    fun live(c: Contact) = now - c.lastSeenByMonitor < 12_000L
    val apList = contacts.filter { it.type == SignalType.WIFI && it.category != "WiFi Client" && it.liveByMonitor }
    val ble = contacts.count { it.type == SignalType.BLE && live(it) }

    fun is5(c: Contact): Boolean {
        c.frequency?.let { return it >= 4900 }
        c.channel?.let { return it >= 32 }
        return false
    }
    val apRaw = apList.size
    val cliRaw = contacts.count { it.type == SignalType.WIFI && it.category == "WiFi Client" && it.liveByMonitor }
    val band5Raw = apList.count { is5(it) }

    var heldAp by remember { mutableStateOf(0) }; var heldCli by remember { mutableStateOf(0) }
    var heldB5 by remember { mutableStateOf(0) }; var heldAt by remember { mutableStateOf(0L) }
    val anyLive = apRaw > 0 || cliRaw > 0
    if (anyLive) { heldAp = apRaw; heldCli = cliRaw; heldB5 = band5Raw; heldAt = now }
    val idle = now - heldAt > 6_000L
    val ap = if (anyLive || idle) apRaw else heldAp
    val cli = if (anyLive || idle) cliRaw else heldCli
    val band5 = if (anyLive || idle) band5Raw else heldB5
    val band24 = ap - band5
    val serialEsp = a.idHex.contains("303a", true)
    val dualBand = serialEsp || band5 > 0
    var fx by remember { mutableStateOf(GhostEsp.ledFx) }

    val title = deviceName(fwStatus) ?: a.chipsetName

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Filled.Bolt, null, tint = accent, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Text("${a.chipsetName}  ·  ${a.idHex}", color = accent, fontFamily = Mono, fontSize = 12.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            ReadoutStat(ap, "AP", accent)
            ReadoutStat(cli, "client", accent)
            ReadoutStat(ble, "BLE", accent)
        }
        if (dualBand) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BandChip("2.4 GHz", band24, accent)
                BandChip("5 GHz", band5, accent)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("live from this device · last 12 s", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)

        val isMonitorNic = a.vid == 0x0cf3 || a.vid == 0x0e8d || (a.vid == 0x0bda && a.pid == 0x0811) || a.vid == 0x148f
        if (isMonitorNic) {
            val dualBandCap = a.vid == 0x0e8d

            var lock by remember(a.idHex) { mutableStateOf(com.rocketgod.warble.usb.UsbBeast.lockChannel) }
            var dwell by remember(a.idHex) { mutableStateOf(com.rocketgod.warble.usb.UsbBeast.dwellMs) }
            Spacer(Modifier.height(18.dp))
            Text("CHANNEL", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ControlChip("Hop", lock == null, accent) { lock = null; com.rocketgod.warble.usb.UsbBeast.setLock(null) }
                com.rocketgod.warble.usb.UsbBeast.lockableChannels(dualBandCap).forEach { ch ->
                    Spacer(Modifier.width(6.dp))
                    ControlChip("$ch", lock == ch, accent) { lock = ch; com.rocketgod.warble.usb.UsbBeast.setLock(ch) }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(if (lock == null) "hopping all channels" else "locked to channel $lock · deep dwell on one target",
                color = Palette.muted, fontFamily = Mono, fontSize = 10.sp)
            Spacer(Modifier.height(14.dp))
            Text("DWELL PER CHANNEL", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {

                fun dLbl(v: Int) = if (v >= 1000) "${v / 1000} s" else "${v}ms"
                val defMs = com.rocketgod.warble.usb.UsbBeast.defaultDwellMs
                val opts = buildList {
                    add(dLbl(defMs) to 0)
                    listOf(150, 350, 700, 1000).filter { it != defMs }.forEach { add(dLbl(it) to it) }
                }
                opts.forEachIndexed { i, (lbl, v) ->
                    if (i > 0) Spacer(Modifier.width(6.dp))
                    ControlChip(lbl, dwell == v, accent) { dwell = v; com.rocketgod.warble.usb.UsbBeast.setDwell(v) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("The first option is this adapter's tuned default; longer dwell catches more clients/handshakes per channel but revisits slower. One beacon interval is ~102ms — below that you miss beacons. The phone handles fast discovery; this adapter does DEEP capture.",
                color = Palette.muted, fontFamily = Mono, fontSize = 10.sp)

            val seenS = ((com.rocketgod.warble.usb.UsbBeast.seenWindowMs + 3000L) / 1000L).coerceAtLeast(1L)
            Spacer(Modifier.height(3.dp))
            Text("At this setting a passed AP stays counted for ~${seenS}s (≈ one sweep). Camp-busy or a channel-lock shortens it.",
                color = accent.copy(alpha = 0.85f), fontFamily = Mono, fontSize = 10.sp)
        }

        if (serialEsp) {
            Spacer(Modifier.height(22.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (fx) accent.copy(alpha = 0.16f) else Palette.surface)
                    .border(1.dp, if (fx) accent else Palette.line, RoundedCornerShape(10.dp))
                    .clickable { fx = !fx; GhostEsp.ledFx = fx }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Filled.Bolt, null, tint = if (fx) accent else Palette.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (fx) "Board FX · ON" else "Board FX · off", color = if (fx) accent else Palette.muted,
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text("LEDs + LCD accent match your skin", color = Palette.muted, fontFamily = Mono, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
    }
}

private fun reconcileHeader(fw: String, contacts: List<Contact>): String {
    if (" · live · " !in fw || " AP" !in fw) return fw
    val chip = fw.substringBefore(" · live · ")
    val ch = fw.substringAfter(" · ch ", "")
    val chSuffix = if (ch.isNotEmpty()) " · ch $ch" else ""
    val ap = contacts.count { it.type == SignalType.WIFI && it.category != "WiFi Client" && it.liveByMonitor }
    return if (" client " in fw) {
        val cli = contacts.count { it.type == SignalType.WIFI && it.category == "WiFi Client" && it.liveByMonitor }
        "$chip · live · $ap AP · $cli client$chSuffix"
    } else {
        "$chip · live · $ap AP$chSuffix"
    }
}

private fun deviceName(fw: String?): String? {
    val s = fw?.trim().orEmpty()
    if (s.isBlank()) return null
    val cut = listOfNotNull(
        s.indexOf("· live", ignoreCase = true).takeIf { it >= 0 },
        Regex("·\\s*\\d").find(s)?.range?.first
    ).minOrNull()
    val base = (if (cut != null && cut > 0) s.substring(0, cut) else s).trim().trimEnd('·').trim()
    return base.ifBlank { null }
}

@Composable
private fun BandChip(label: String, count: Int, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.border(1.dp, Palette.line, RoundedCornerShape(9.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text("$count", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.width(7.dp))
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun ControlChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent else Palette.surface)
            .border(1.dp, if (selected) accent else Palette.line, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (selected) inkOn(accent) else Palette.ink, fontFamily = Mono,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
    }
}

@Composable
private fun ReadoutStat(value: Int, label: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp)
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
    }
}

@Composable
private fun UploadRunButton(accent: Color, deep: Color, cooking: Boolean, onClick: () -> Unit) {

    if (cooking) { CookingButton(); return }

    val (mod, scale) = pressable(onClick)
    Box(mod.scale(scale).fillMaxWidth().glassAction(accent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.CloudUpload, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text("UPLOAD THIS RUN", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(14.dp))
            Icon(Icons.Filled.CloudUpload, null, tint = accent, modifier = Modifier.size(20.dp))
        }
        SheenSweep(12.dp)
    }
}

private class FireP {
    var x = 0f; var y = 1f; var vx = 0f; var vy = 0f; var life = 0f; var max = 1f; var size = 0f; var seed = 0f; var ember = false
}

@Composable
private fun CookingButton() {
    val tr = rememberInfiniteTransition(label = "fireGlow")
    val glow by tr.animateFloat(0.5f, 1f,
        infiniteRepeatable(tween(480), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow")
    val white = Color.White; val yellow = Color(0xFFFFE07A); val orange = Color(0xFFFF7A00)
    val red = Color(0xFFE53000); val deepred = Color(0xFF7A1500)
    val rnd = remember { kotlin.random.Random(0x1E) }
    val fire = remember { Array(48) { FireP() } }
    var frame by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val t0 = withFrameNanos { it }; var last = t0
        while (true) {
            val n = withFrameNanos { it }
            val dt = ((n - last) / 1e9f).coerceIn(0f, 0.05f); last = n
            val tt = (n - t0) / 1e9f
            for (p in fire) {
                if (p.life <= 0f) {
                    p.ember = rnd.nextFloat() < 0.16f
                    p.max = (0.55f + rnd.nextFloat() * 0.6f) * (if (p.ember) 1.5f else 1f)
                    p.life = p.max
                    p.x = 0.1f + rnd.nextFloat() * 0.8f
                    p.y = 0.98f + rnd.nextFloat() * 0.05f
                    p.vy = -(0.5f + rnd.nextFloat() * 0.7f) * (if (p.ember) 1.6f else 1f)
                    p.vx = (rnd.nextFloat() - 0.5f) * 0.3f
                    p.size = (0.07f + rnd.nextFloat() * 0.1f) * (if (p.ember) 0.45f else 1f)
                    p.seed = rnd.nextFloat() * 6.283f
                } else {
                    p.vy -= 0.28f * dt
                    p.vx += sin(p.seed + tt * 7f) * 0.7f * dt
                    p.x += p.vx * dt; p.y += p.vy * dt; p.life -= dt
                }
            }
            frame++
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(9.dp), spotColor = orange.copy(alpha = glow), ambientColor = red)
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.verticalGradient(listOf(red, deepred)))
    ) {
        Canvas(Modifier.matchParentSize()) {
            frame.let { }
            val w = size.width; val h = size.height; val s = minOf(w, h)
            for (p in fire) {
                if (p.life <= 0f) continue
                val f = (p.life / p.max).coerceIn(0f, 1f)
                val col = when {
                    f > 0.75f -> lerp(yellow, white, (f - 0.75f) / 0.25f)
                    f > 0.45f -> lerp(orange, yellow, (f - 0.45f) / 0.3f)
                    else -> lerp(deepred, orange, f / 0.45f)
                }
                val a = (f * 1.5f).coerceIn(0f, 1f) * (if (p.ember) 1f else 0.85f)
                val rad = p.size * s * (0.5f + 0.7f * f)
                val c = Offset(p.x * w, p.y * h)
                drawCircle(col.copy(alpha = a * 0.35f), radius = rad * 1.8f, center = c)
                drawCircle(col.copy(alpha = a), radius = rad, center = c)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("COOKING", color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold,
                fontSize = 18.sp, letterSpacing = 4.sp)
        }
        SheenSweep(9.dp)
        Box(Modifier.matchParentSize().border(1.dp, Color.White.copy(alpha = 0.35f * glow), RoundedCornerShape(9.dp)))
    }
}

@Composable
private fun FieldReportButton(accent: Color, deep: Color, onClick: () -> Unit) {

    val (mod, scale) = pressable(onClick)
    Box(mod.scale(scale).fillMaxWidth().glassAction(accent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            TwinkleIcon(Icons.Filled.BarChart, accent, 18.dp, 0)
            Spacer(Modifier.width(14.dp))
            Text("VIEW FIELD REPORT", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(14.dp))
            TwinkleIcon(Icons.Filled.BarChart, accent, 18.dp, 600)
        }
        SheenSweep(12.dp)
    }
}

@Composable
private fun TwinkleIcon(icon: ImageVector, tint: Color, sizeDp: androidx.compose.ui.unit.Dp, staggerMs: Int) {
    val tr = rememberInfiniteTransition(label = "twinkleIcon")
    val tw by tr.animateFloat(
        0.45f, 1f,
        infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse,
            initialStartOffset = androidx.compose.animation.core.StartOffset(staggerMs)),
        label = "twIcon"
    )
    Icon(icon, null, tint = tint.copy(alpha = tw), modifier = Modifier.size(sizeDp))
}

@Composable
private fun ShinyTrophy(tint: Color, sizeDp: androidx.compose.ui.unit.Dp, staggerMs: Int) {
    val metal = Brush.linearGradient(
        listOf(tint.copy(alpha = 0.7f), tint, Color.White.copy(alpha = 0.95f), tint, tint.copy(alpha = 0.65f))
    )
    val tr = rememberInfiniteTransition(label = "twinkle")
    val tw by tr.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse,
            initialStartOffset = androidx.compose.animation.core.StartOffset(staggerMs)),
        label = "tw"
    )
    Box(contentAlignment = Alignment.TopEnd) {
        Icon(Icons.Filled.EmojiEvents, null, tint = Palette.paper.copy(alpha = 0.6f),
            modifier = Modifier.size(sizeDp).offset(y = 1.dp))
        Icon(Icons.Filled.EmojiEvents, null, tint = Color.Unspecified, modifier = Modifier.size(sizeDp)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); drawRect(metal, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop) })
        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White,
            modifier = Modifier.size(sizeDp * 0.5f).offset(x = sizeDp * 0.18f, y = -(sizeDp * 0.12f))
                .graphicsLayer { alpha = tw; scaleX = tw; scaleY = tw })
    }
}

@Composable
private fun BoxScope.SheenSweep(corner: androidx.compose.ui.unit.Dp) {
    val travel = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(140)
        travel.animateTo(1f, tween(900, easing = androidx.compose.animation.core.LinearEasing))
    }
    androidx.compose.foundation.Canvas(Modifier.matchParentSize().clip(RoundedCornerShape(corner))) {
        val p = travel.value
        val x = -0.2f * size.width + p * (1.4f * size.width)
        val bw = size.width * 0.3f
        rotate(18f, pivot = androidx.compose.ui.geometry.Offset(x, size.height / 2f)) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                    startX = x - bw / 2f, endX = x + bw / 2f
                ),
                topLeft = androidx.compose.ui.geometry.Offset(x - bw / 2f, -size.height * 0.7f),
                size = androidx.compose.ui.geometry.Size(bw, size.height * 2.4f)
            )
        }
    }
}

private fun trophyTint(accent: Color): Color {
    val max = maxOf(accent.red, accent.green, accent.blue)
    val min = minOf(accent.red, accent.green, accent.blue)
    val sat = if (max == 0f) 0f else (max - min) / max
    return if (max > 0.85f && sat < 0.25f) Palette.paper else Palette.gold
}

private fun unique(stats: Stats, type: SignalType): Long =
    stats.perType.firstOrNull { it.type == type }?.unique ?: 0

private fun newOf(stats: Stats, type: SignalType): Long =
    stats.perType.firstOrNull { it.type == type }?.newThisRun ?: 0L

private fun hue(accent: Color, index: Int): Color = when (index) {
    0 -> accent
    1 -> androidx.compose.ui.graphics.lerp(accent, Color.White, 0.42f)
    else -> androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.34f)
}

@Composable
private fun SatelliteContent(gnss: com.rocketgod.warble.core.GnssStat, accent: Color, onSatellites: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onSatellites() }.padding(horizontal = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SatelliteAlt, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("SATELLITES", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (gnss.total > 0) "${gnss.usedInFix} in fix · ${gnss.total} seen" else "waiting for GPS fix…",
                color = Palette.muted, fontFamily = Mono, fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.size(16.dp))
        }
        if (gnss.constellations.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                gnss.constellations.forEachIndexed { i, (name, n) ->
                    val tint = hue(accent, i % 3)
                    Row(
                        Modifier.padding(end = 8.dp)
                            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$n", color = tint, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(name, color = Palette.ink, fontFamily = Mono, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

fun fmtGrouped(n: Long): String {
    val s = n.toString()
    val sb = StringBuilder()
    var count = 0
    for (i in s.indices.reversed()) {
        sb.append(s[i]); count++
        if (count % 3 == 0 && i != 0) sb.append(',')
    }
    return sb.reverse().toString()
}

fun fmtCompact(n: Long): String = when {
    n >= 1_000_000 -> (n / 1_000_000.0).let { if (it >= 100) "%.0fM".format(it) else "%.1fM".format(it) }
    n >= 100_000 -> "%.0fk".format(n / 1000.0)
    else -> fmtGrouped(n)
}
