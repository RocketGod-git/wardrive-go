package com.rocketgod.warble.wear

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

private const val STATS_PATH = "/wardrive/stats"

private const val DEV_EMAIL = "rocketgod666@gmail.com"

private const val ACCENT = "accent"
private const val ACCENT_DEFAULT = 0xFF37ECCBL

private const val ACCENT_MATCH_PHONE = 0L

private val PALETTE = listOf(
    "Teal" to 0xFF37ECCBL,
    "Green" to 0xFF39D353L,
    "Cyan" to 0xFF22D3EEL,
    "Blue" to 0xFF2E9DFFL,
    "Violet" to 0xFF9D7BFFL,
    "Pink" to 0xFFFF5CA8L,
    "Red" to 0xFFFF5B52L,
    "Orange" to 0xFFFF9F40L,
    "Gold" to 0xFFFFD24AL,
    "White" to 0xFFFFFFFFL,
)

data class WatchStats(
    val wifi: Int, val ble: Int, val cell: Int, val rate: Int,
    val speedMps: Float, val distM: Double, val metric: Boolean,
    val bannerEyebrow: String, val bannerTitle: String, val bannerColor: Long
)

private data class DataPoint(val key: String, val label: String)
private val DATA_POINTS = listOf(
    DataPoint("show_banner", "Offensive alerts"),
    DataPoint("show_wifi", "New Wi-Fi"),
    DataPoint("show_ble", "Bluetooth"),
    DataPoint("show_cell", "Cell"),
    DataPoint("show_rate", "Rate (/min)"),
    DataPoint("show_speed", "Speed"),
    DataPoint("show_dist", "Distance"),
    DataPoint("show_clock", "Time / date (swap trip)"),
)
private const val KEEP_AWAKE = "keep_awake"
private const val ALWAYS_ON = "always_on"

private val DEFAULT_OFF = setOf("show_clock")

class WearMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val stats = mutableStateOf<WatchStats?>(null)
    private val showSettings = mutableStateOf(false)

    private val toggles = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val accentPref = mutableStateOf(ACCENT_MATCH_PHONE)
    private val phoneAccent = mutableStateOf(0L)
    private val isAmbient = mutableStateOf(false)
    private val crashReport = mutableStateOf<String?>(null)
    private lateinit var prefs: SharedPreferences

    private var ambientRegistered = false
    private val ambientObserver by lazy {
        androidx.wear.ambient.AmbientLifecycleObserver(this, object : androidx.wear.ambient.AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: androidx.wear.ambient.AmbientLifecycleObserver.AmbientDetails) { isAmbient.value = true }
            override fun onExitAmbient() { isAmbient.value = false }
        })
    }
    private fun applyAmbient() {
        val want = prefs.getBoolean(ALWAYS_ON, true)
        if (want && !ambientRegistered) { lifecycle.addObserver(ambientObserver); ambientRegistered = true }
        else if (!want && ambientRegistered) {
            lifecycle.removeObserver(ambientObserver); ambientRegistered = false; isAmbient.value = false
        }
    }

    private val demo get() = BuildConfig.DEBUG && intent?.getBooleanExtra("demo", false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("wardrive_wear", MODE_PRIVATE)

        for (k in listOf(KEEP_AWAKE, ALWAYS_ON) + DATA_POINTS.map { it.key }) toggles[k] = prefs.getBoolean(k, k !in DEFAULT_OFF)

        accentPref.value = prefs.getLong(ACCENT, ACCENT_MATCH_PHONE)
        phoneAccent.value = getSharedPreferences(TILE_PREFS, MODE_PRIVATE).getLong("accent", 0L)
        crashReport.value = WarbleWearApp.read(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyKeepAwake()
        applyAmbient()
        if (demo) stats.value = WatchStats(
            wifi = 128, ble = 214, cell = 17, rate = 42,
            speedMps = 13.4f, distM = 8046.0, metric = false,
            bannerEyebrow = "SURVEILLANCE", bannerTitle = "Flock Safety Camera", bannerColor = 0xFFFF5252L
        )
        setContent {
            val crash = crashReport.value
            if (crash != null) {
                CrashScreen(crash) {
                    WarbleWearApp.clear(this@WearMainActivity)
                    crashReport.value = null
                }
                return@setContent
            }

            val effectiveAccent = if (accentPref.value == ACCENT_MATCH_PHONE)
                (phoneAccent.value.takeIf { it != 0L } ?: ACCENT_DEFAULT) else accentPref.value
            WearApp(
                s = stats.value,
                on = { key -> toggles[key] ?: (key !in DEFAULT_OFF) },
                accent = effectiveAccent,
                accentSelected = accentPref.value,
                ambient = isAmbient.value,
                showSettings = showSettings.value,
                onOpenSettings = { showSettings.value = true },
                onCloseSettings = { showSettings.value = false },
                onToggle = { key, v ->
                    toggles[key] = v
                    prefs.edit().putBoolean(key, v).apply()
                    if (key == KEEP_AWAKE) applyKeepAwake()
                    if (key == ALWAYS_ON) applyAmbient()
                },
                onAccent = { argb ->
                    accentPref.value = argb
                    prefs.edit().putLong(ACCENT, argb).apply()
                }
            )
        }
    }

    private fun applyKeepAwake() {
        if (prefs.getBoolean(KEEP_AWAKE, true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        applyKeepAwake()
        if (demo) return
        val client = Wearable.getDataClient(this)
        client.addListener(this)
        client.dataItems.addOnSuccessListener { buf ->
            for (i in 0 until buf.count) {
                val item = buf[i]
                if (item.uri.path == STATS_PATH) apply(DataMapItem.fromDataItem(item))
            }
            buf.release()
        }
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == STATS_PATH) {
                apply(DataMapItem.fromDataItem(e.dataItem))
            }
        }
    }

    private fun apply(item: DataMapItem) {
        val m = item.dataMap
        stats.value = WatchStats(
            wifi = m.getInt("wifi", 0), ble = m.getInt("ble", 0), cell = m.getInt("cell", 0),
            rate = m.getInt("rate", 0), speedMps = m.getFloat("speed", -1f), distM = m.getDouble("dist", 0.0),
            metric = m.getBoolean("metric", false),
            bannerEyebrow = m.getString("b_eyebrow") ?: "", bannerTitle = m.getString("b_title") ?: "",
            bannerColor = m.getLong("b_color", 0L)
        )

        m.getLong("accent", 0L).let { if (it != 0L) phoneAccent.value = it }
    }
}

@Composable
private fun WearApp(
    s: WatchStats?, on: (String) -> Boolean, accent: Long, accentSelected: Long, ambient: Boolean, showSettings: Boolean,
    onOpenSettings: () -> Unit, onCloseSettings: () -> Unit,
    onToggle: (String, Boolean) -> Unit, onAccent: (Long) -> Unit
) {
    MaterialTheme {

        androidx.activity.compose.BackHandler(enabled = showSettings) { onCloseSettings() }

        if (showSettings) SettingsScreen(on, accent, accentSelected, onToggle, onAccent, onCloseSettings)
        else StatsScreen(s, on, accent, ambient, onOpenSettings)
    }
}

@Composable
private fun CrashScreen(text: String, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    MaterialTheme {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    "Last run crashed",
                    color = Color(0xFFFF5B52),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            item {
                Text(
                    "Send it to the dev, or copy / share it, so it can be fixed.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                )
            }
            item {

                var sent by remember { mutableStateOf(false) }
                Chip(
                    onClick = {
                        runCatching {
                            val req = com.google.android.gms.wearable.PutDataMapRequest.create("/wardrive/crash").apply {
                                dataMap.putString("text", text)
                                dataMap.putLong("t", System.currentTimeMillis())
                            }
                            com.google.android.gms.wearable.Wearable.getDataClient(ctx).putDataItem(req.asPutDataRequest())
                        }
                        sent = true
                    },
                    label = { Text(if (sent) "Sent to phone ✓" else "Send to phone") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
                )
            }
            item {
                Chip(
                    onClick = {

                        val subject = "Wardrive Go (watch) crash"
                        val mail = Intent(Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEV_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        if (runCatching { ctx.startActivity(mail) }.isFailure) {
                            runCatching {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf(DEV_EMAIL))
                                    putExtra(Intent.EXTRA_SUBJECT, subject)
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }, "Send to dev"))
                            }
                        }
                    },
                    label = { Text("Send to dev (on watch)") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            item {
                Chip(
                    onClick = { clip.setText(AnnotatedString(text)) },
                    label = { Text("Copy") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            item {
                Chip(
                    onClick = {
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Wardrive Go (watch) crash")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share crash"))
                        }
                    },
                    label = { Text("Share") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            item {
                Chip(
                    onClick = onDismiss,
                    label = { Text("Dismiss") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)
                )
            }
            item {
                Text(
                    text,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StatsScreen(s: WatchStats?, on: (String) -> Boolean, accent: Long, ambient: Boolean, onOpenSettings: () -> Unit) {
    val accentColor = Color(accent.toInt())

    var hintTick by remember { mutableStateOf(0) }
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(hintTick) { if (hintTick > 0) { showHint = true; delay(1400); showHint = false } }

    val bannerQueue = remember { mutableStateListOf<Triple<String, String, Long>>() }
    var currentBanner by remember { mutableStateOf<Triple<String, String, Long>?>(null) }
    val incomingBanner = s?.takeIf { it.bannerTitle.isNotBlank() }
        ?.let { Triple(it.bannerEyebrow, it.bannerTitle, it.bannerColor) }
    LaunchedEffect(incomingBanner) {
        if (incomingBanner != null && incomingBanner != currentBanner && incomingBanner != bannerQueue.lastOrNull()) {
            bannerQueue.add(incomingBanner)
        }
    }
    LaunchedEffect(bannerQueue.size, currentBanner) {
        if (currentBanner == null && bannerQueue.isNotEmpty()) currentBanner = bannerQueue.removeAt(0)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .alpha(if (ambient) 0.6f else 1f)
            .combinedClickable(onClick = { hintTick++ }, onLongClick = onOpenSettings),
        contentAlignment = Alignment.Center
    ) {

        val u = minOf(maxWidth, maxHeight).value
        if (s == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Wardrive Go", fontWeight = FontWeight.Bold, fontSize = (u * 0.10f).sp)
                Spacer(Modifier.height((u * 0.03f).dp))
                Text("Waiting for phone…", fontSize = (u * 0.06f).sp)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding((u * 0.05f).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                if (on("show_banner")) {
                    Box(Modifier.fillMaxWidth().height((u * 0.17f).dp), contentAlignment = Alignment.Center) {
                        val cur = currentBanner
                        if (cur != null) {
                            val c = if (cur.third != 0L) Color(cur.third.toInt()) else accentColor

                            key(cur) { BannerPlayer(cur.first, cur.second, c, u, ambient) { currentBanner = null } }
                        }
                    }
                }

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val v = minOf(maxWidth, maxHeight).value
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (on("show_wifi")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${s.wifi}", color = accentColor, fontWeight = FontWeight.Bold, fontSize = (v * 0.34f).sp, maxLines = 1)
                                Text("NEW WIFI", fontSize = (v * 0.07f).sp)
                            }
                        }
                        val row = buildList {
                            if (on("show_ble")) add("BLE" to s.ble)
                            if (on("show_cell")) add("CELL" to s.cell)
                            if (on("show_rate")) add("/MIN" to s.rate)
                        }
                        if (row.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                row.forEachIndexed { i, (label, value) ->
                                    if (i > 0) Spacer(Modifier.width((v * 0.05f).dp))
                                    Stat(label, value, (v * 0.14f).sp, (v * 0.06f).sp)
                                }
                            }
                        }
                        if (on("show_clock")) {

                            var now by remember { mutableStateOf(0L) }
                            LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
                            val time = remember(now / 60_000) { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)) }
                            val date = remember(now / 60_000) { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)) }
                            if (now != 0L) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(time, color = accentColor, fontWeight = FontWeight.Bold, fontSize = (v * 0.17f).sp, maxLines = 1)
                                    Text(date, fontSize = (v * 0.07f).sp, maxLines = 1)
                                }
                            }
                        } else {
                            val showSpeed = on("show_speed"); val showDist = on("show_dist")
                            if (showSpeed || showDist) {

                                val spd = if (s.speedMps < 0f) "--" else
                                    if (s.metric) "%.0f km/h".format(s.speedMps * 3.6f) else "%.0f mph".format(s.speedMps * 2.237f)
                                val dst = if (s.metric) "%.1f km".format(s.distM / 1000.0) else "%.1f mi".format(s.distM / 1609.34)
                                val line = when {
                                    showSpeed && showDist -> "$spd · $dst"
                                    showSpeed -> spd
                                    else -> dst
                                }
                                Text(line, fontSize = (v * 0.09f).sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showHint, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.background(Color(0xCC1C1C1C), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = accentColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("hold", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    on: (String) -> Boolean, accent: Long, accentSelected: Long,
    onToggle: (String, Boolean) -> Unit, onAccent: (Long) -> Unit, onClose: () -> Unit
) {
    val accentColor = Color(accent.toInt())
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        item { doneChip(onClose) }
        item { Text("Watch stats", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        item { toggle("Keep screen on", on(KEEP_AWAKE), accentColor) { onToggle(KEEP_AWAKE, it) } }
        item { toggle("Always on (uses battery)", on(ALWAYS_ON), accentColor) { onToggle(ALWAYS_ON, it) } }
        DATA_POINTS.forEach { dp ->
            item { toggle(dp.label, on(dp.key), accentColor) { onToggle(dp.key, it) } }
        }
        item { Text("Accent color", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }

        item { colorChip("Match phone app", accent, selected = accentSelected == ACCENT_MATCH_PHONE) { onAccent(ACCENT_MATCH_PHONE) } }
        PALETTE.forEach { (name, argb) ->
            item { colorChip(name, argb, selected = accentSelected == argb) { onAccent(argb) } }
        }
        item { doneChip(onClose) }
    }
}

@Composable
private fun doneChip(onClose: () -> Unit) {
    Chip(
        onClick = onClose,
        label = { Text("Done") },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun colorChip(name: String, argb: Long, selected: Boolean, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(if (selected) "$name ✓" else name, fontSize = 13.sp) },
        icon = { Box(Modifier.size(20.dp).background(Color(argb.toInt()), CircleShape)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun toggle(label: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onChange,
        label = { Text(label, fontSize = 13.sp) },
        toggleControl = { BadassSwitch(checked, accent) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BadassSwitch(checked: Boolean, accent: Color) {
    val p by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "switch"
    )
    val cp = p.coerceIn(0f, 1f)
    val trackW = 36.dp; val trackH = 20.dp; val thumb = 15.dp; val pad = 2.5.dp
    val track = androidx.compose.ui.graphics.lerp(Color(0xFF484848), accent, cp)
    val thumbColor = androidx.compose.ui.graphics.lerp(Color(0xFFDDDDDD), Color.White, cp)
    Box(
        Modifier.size(trackW, trackH)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(track),
        contentAlignment = Alignment.CenterStart
    ) {

        val x = androidx.compose.ui.unit.lerp(pad, trackW - thumb - pad, p)

        Box(Modifier.offset(x = x - 2.dp).size(thumb + 4.dp).alpha(cp * 0.55f).background(accent, CircleShape))
        Box(Modifier.offset(x = x).size(thumb).background(thumbColor, CircleShape))
    }
}

@Composable
private fun BannerPlayer(eyebrow: String, title: String, color: Color, u: Float, ambient: Boolean, onDone: () -> Unit) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (eyebrow.isNotBlank()) Text(
            eyebrow, color = color, fontSize = (u * 0.045f).sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (ambient) {

            LaunchedEffect(Unit) { delay(3000); onDone() }
            Text(
                title, color = color, fontSize = (u * 0.075f).sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds(), contentAlignment = Alignment.CenterStart) {
            val containerPx = constraints.maxWidth
            var textPx by remember { mutableStateOf(0) }
            val fits = textPx == 0 || textPx <= containerPx
            val scroll = remember { Animatable(0f) }

            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(textPx, containerPx) {
                if (textPx <= 0 || containerPx <= 0) return@LaunchedEffect
                if (textPx <= containerPx) {
                    ready = true
                    delay(1500)
                } else {
                    scroll.snapTo(containerPx.toFloat())
                    ready = true
                    val speed = with(density) { 48.dp.toPx() }
                    val dur = ((containerPx + textPx) / speed * 1000f).toInt().coerceAtLeast(1500)
                    scroll.animateTo(-textPx.toFloat(), tween(dur, easing = LinearEasing))
                }
                delay(500)
                onDone()
            }
            Text(
                title, color = color, fontSize = (u * 0.075f).sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false,
                onTextLayout = { textPx = it.size.width },
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .alpha(if (ready) 1f else 0f)
                    .offset { IntOffset(if (fits) (containerPx - textPx) / 2 else scroll.value.roundToInt(), 0) }
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, valueSize: TextUnit, labelSize: TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontWeight = FontWeight.Bold, fontSize = valueSize, maxLines = 1)
        Text(label, fontSize = labelSize, maxLines = 1)
    }
}
