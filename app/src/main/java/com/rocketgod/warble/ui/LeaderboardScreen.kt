package com.rocketgod.warble.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rocketgod.warble.core.Leaderboard
import com.rocketgod.warble.model.Stats
import com.rocketgod.warble.net.WdgwStats
import com.rocketgod.warble.net.WigleStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin
import kotlin.random.Random

private val gold = Color(0xFFE0A53C)
private val silver = Color(0xFFC9D4D1)
private val bronze = Color(0xFFC8804A)
private fun medal(rank: Int, accent: Color) = when (rank) { 1 -> gold; 2 -> silver; 3 -> bronze; else -> accent }

private fun monogram(name: String): String {
    val n = name.trim()
    if (n.isEmpty()) return "?"
    return n.take(2).uppercase()
}

private fun pct(p: Double): String = if (p >= 0.1) String.format("%.2f%%", p) else String.format("%.3f%%", p)

private class Mote(val x: Float, val y: Float, val r: Float, val spd: Float, val ph: Float)
private class Spark(val ang: Float, val spd: Float, val ph: Float, val r: Float)

@Composable
private fun frameTime(): Float {
    val t by produceState(0f) {
        while (true) withInfiniteAnimationFrameNanos { value = it / 1_000_000_000f }
    }
    return t
}

@Composable
private fun LeaderboardBackdrop(accent: Color, modifier: Modifier) {
    val motes = remember {
        List(48) { Mote(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat() * 2.2f,
            0.01f + Random.nextFloat() * 0.06f, Random.nextFloat() * 6.283f) }
    }
    val t = frameTime()
    Canvas(modifier) {
        val w = size.width; val h = size.height

        drawRect(Brush.verticalGradient(
            0f to accent.copy(alpha = 0.22f),
            0.30f to accent.copy(alpha = 0.07f),
            0.7f to Color.Transparent), size = size)

        val aura = Offset(w * 0.5f, h * 0.14f)
        val beat = 0.20f + 0.12f * (0.5f + 0.5f * sin(t * 0.9f))
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = beat), Color.Transparent),
            center = aura, radius = w * 0.85f), radius = w * 0.85f, center = aura)

        for (k in 0..3) {
            var p = (t * 0.16f + k / 4f) % 1f; if (p < 0) p += 1f
            drawCircle(accent.copy(alpha = (1 - p) * 0.28f), radius = p * w * 1.0f, center = aura, style = Stroke(1.6f))
        }

        for (m in motes) {
            var fy = (m.y - t * m.spd) % 1f; if (fy < 0) fy += 1f
            val x = (m.x + 0.02f * sin(t * 0.5f + m.ph)) * w
            val y = fy * h
            val a = 0.12f + 0.34f * (0.5f + 0.5f * sin(t + m.ph))
            drawCircle(accent.copy(alpha = a), radius = m.r, center = Offset(x, y))
        }

        val sweepX = (((t * 0.06f) % 1.5f) - 0.25f) * w
        drawRect(Brush.linearGradient(
            listOf(Color.Transparent, accent.copy(alpha = 0.10f), Color.Transparent),
            start = Offset(sweepX - w * 0.18f, 0f), end = Offset(sweepX + w * 0.18f, h)), size = size)

        drawRect(Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f)),
            center = Offset(w * 0.5f, h * 0.42f), radius = w * 0.95f), size = size)
    }
}

@Composable
private fun ChampionFX(color: Color, modifier: Modifier) {
    val sparks = remember {
        List(16) { Spark(-0.6f + Random.nextFloat() * 1.2f, 0.4f + Random.nextFloat() * 0.5f,
            Random.nextFloat() * 1.5f, 1.2f + Random.nextFloat() * 1.6f) }
    }
    val t = frameTime()
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height * 0.62f)
        for (k in 0..2) {
            var phase = (t * 0.6f + k / 3f) % 1f
            if (phase < 0) phase += 1f
            val rad = phase * size.width * 0.55f
            drawCircle(color.copy(alpha = (1 - phase) * 0.35f), radius = rad, center = c, style = Stroke(1.2f))
        }
        for (s in sparks) {
            val cycle = ((t * s.spd + s.ph) % 1.4f) / 1.4f
            val x = c.x + sin(s.ang) * 26f
            val y = c.y - cycle * size.height * 0.6f
            drawCircle(color.copy(alpha = (1 - cycle) * 0.85f), radius = s.r, center = Offset(x, y))
        }
    }
}

@Composable
private fun AnimatedTrophy(rank: Int, accent: Color, champion: Boolean, ghost: Boolean) {
    val m = medal(rank, accent)
    val sz = if (champion) 84 else 56
    if (ghost) {
        Icon(Icons.Filled.EmojiEvents, null, tint = m.copy(alpha = 0.2f), modifier = Modifier.size(sz.dp))
        return
    }
    val t = frameTime()
    val phase = t * 2f + rank
    val bob = sin(phase) * (if (champion) 6f else 4f)
    val glow = 0.16f + 0.30f * (0.5f + 0.5f * sin(phase * 1.1f))
    val pulse = if (champion) 1f + 0.05f * sin(phase * 1.3f) else 1f
    Box(Modifier.size((sz * 1.35f).dp), contentAlignment = Alignment.Center) {
        if (champion) ChampionFX(m, Modifier.size((sz * 1.35f).dp))
        Box(Modifier.size((sz * 1.15f).dp).background(
            Brush.radialGradient(listOf(m.copy(alpha = glow), Color.Transparent)), CircleShape))
        Icon(Icons.Filled.EmojiEvents, null, tint = m, modifier = Modifier.size(sz.dp).graphicsLayer {
            translationY = bob; scaleX = pulse; scaleY = pulse
        })
    }
}

@Composable
private fun PodiumSlot(rank: Int, name: String, score: String, monogram: String, pedestal: Int, champion: Boolean, ghost: Boolean, accent: Color, modifier: Modifier) {
    val m = medal(rank, accent)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height((if (champion) 116 else 84).dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedTrophy(rank, accent, champion, ghost)
        }
        Spacer(Modifier.height(4.dp))
        Text(name, color = if (ghost) Palette.muted else Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, maxLines = 1)
        Text(score, color = if (ghost) Palette.muted else accent, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = (if (champion) 17 else 13).sp, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(pedestal.dp)
                .background(Brush.verticalGradient(listOf(m.copy(alpha = 0.45f), m.copy(alpha = 0.08f))), RoundedCornerShape(5.dp))
                .border(1.dp, m.copy(alpha = 0.55f), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.TopCenter
        ) { Text("$rank", color = m, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

private enum class Tab(val label: String) { USERS("USERS"), TEAMS("TEAMS"), YOU("YOU") }
private enum class Source(val label: String) { WIGLE("WiGLE"), WDGW("WDGWars") }

@Composable
fun LeaderboardScreen(
    stats: Stats,
    accent: Color,
    wigleName: String,
    wigleToken: String,
    wdgwKey: String,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onWdgwUsername: (String?) -> Unit = {}
) {
    var source by remember { mutableStateOf(Source.WIGLE) }
    var tab by remember { mutableStateOf(Tab.USERS) }
    var sort by remember { mutableStateOf(WigleStats.SORT_ALLTIME) }

    var selectedGroup by remember { mutableStateOf<WigleStats.WGroup?>(null) }

    var selectedUser by remember { mutableStateOf<WigleStats.UserDetail?>(null) }

    LaunchedEffect(tab, source) {
        selectedUser = null
        if (source != Source.WIGLE || tab != Tab.TEAMS) selectedGroup = null
    }

    fun popOne() {
        when {
            selectedUser != null -> selectedUser = null
            selectedGroup != null -> selectedGroup = null
            else -> onBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) { popOne() }
    val hasCreds = wigleName.isNotBlank() && wigleToken.isNotBlank()
    val hasWdgw = wdgwKey.isNotBlank()

    val standings by produceState<WigleStats.Standings?>(initialValue = null, sort) {
        value = null
        value = withContext(Dispatchers.IO) { WigleStats.standings(sort, 0, 100, wigleName, wigleToken) }
    }
    val groups by produceState<WigleStats.Groups?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { WigleStats.groups(wigleName, wigleToken) }
    }
    val personal by produceState<WigleStats.Personal?>(initialValue = null) {
        if (hasCreds) value = withContext(Dispatchers.IO) { WigleStats.user(wigleName, wigleToken) }
    }

    val race by produceState<List<WigleStats.WUser>>(initialValue = emptyList(), personal?.rank) {
        val p = personal
        value = if (p != null && p.rank > 0)
            withContext(Dispatchers.IO) { WigleStats.raceWindow(p.rank, p.name, 5, wigleName, wigleToken) }
        else emptyList()
    }

    val wdgwBoard by produceState<WdgwStats.Board?>(initialValue = null, wdgwKey) {
        value = if (hasWdgw) withContext(Dispatchers.IO) { WdgwStats.leaderboard(wdgwKey) } else null
    }
    val wdgwMe by produceState<WdgwStats.Me?>(initialValue = null, wdgwKey) {
        value = if (hasWdgw) withContext(Dispatchers.IO) { WdgwStats.me(wdgwKey) } else null
    }

    LaunchedEffect(wdgwMe?.name) { onWdgwUsername(wdgwMe?.name) }

    Box(Modifier.fillMaxSize().background(Palette.paper)) {
        LeaderboardBackdrop(accent, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {

            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).background(Palette.surface, RoundedCornerShape(8.dp)).border(1.dp, Palette.line, RoundedCornerShape(8.dp)).clickable { popOne() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.ArrowBack, "back", tint = Palette.ink, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${source.label.uppercase()} · LIVE STANDINGS", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("LEADERBOARD", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }

            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabPill(Icons.Filled.Public, "WiGLE", source == Source.WIGLE, accent, Modifier.weight(1f)) { source = Source.WIGLE }
                TabPill(Icons.Filled.Bolt, "WDGWars", source == Source.WDGW, accent, Modifier.weight(1f)) { source = Source.WDGW }
            }
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabPill(Icons.Filled.Person, "USERS", tab == Tab.USERS, accent, Modifier.weight(1f)) { tab = Tab.USERS }
                TabPill(Icons.Filled.Group, if (source == Source.WDGW) "GANGS" else "TEAMS", tab == Tab.TEAMS, accent, Modifier.weight(1f)) { tab = Tab.TEAMS }
                TabPill(Icons.Filled.EmojiEvents, "YOU", tab == Tab.YOU, accent, Modifier.weight(1f)) { tab = Tab.YOU }
            }
            Spacer(Modifier.height(12.dp))

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                when (source) {
                    Source.WIGLE -> selectedUser?.let { u ->
                        UserDetailScreen(u, accent) { selectedUser = null }
                    } ?: when (tab) {
                        Tab.USERS -> UsersTab(standings, sort, personal, hasCreds, accent, onSettings, onUser = { selectedUser = it }) { sort = it }
                        Tab.TEAMS -> selectedGroup?.let { g ->
                            TeamMembersTab(g, personal?.name ?: "", wigleName, wigleToken, accent, onUser = { selectedUser = it }) { selectedGroup = null }
                        } ?: TeamsTab(groups, accent) { selectedGroup = it }
                        Tab.YOU -> YouTab(stats, personal, race, hasCreds, accent, onSettings, onUser = { selectedUser = it })
                    }
                    Source.WDGW -> when (tab) {
                        Tab.USERS -> WdgwUsersTab(wdgwBoard, wdgwMe, hasWdgw, accent, onSettings)
                        Tab.TEAMS -> WdgwGangsTab(wdgwBoard, hasWdgw, accent, onSettings)
                        Tab.YOU -> WdgwYouTab(wdgwBoard, wdgwMe, hasWdgw, accent, onSettings)
                    }
                }
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

@Composable
private fun ConnectPrompt(title: String, body: String, accent: Color, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .background(Palette.surface.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .clickable { onSettings() }.padding(16.dp)
    ) {
        Text(title, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Settings, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open API settings", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

private enum class WView(val label: String) { TODAY("TODAY"), WEEK("WEEK"), ALLTIME("ALL-TIME"), CELLS("CELLS") }

@Composable
private fun WdgwUsersTab(board: WdgwStats.Board?, me: WdgwStats.Me?, hasWdgw: Boolean, accent: Color, onSettings: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    if (!hasWdgw) { ConnectPrompt("Connect WDGWars", "The wdgwars.pl leaderboard needs your WDGWars API key. Add it to load the board and upload there.", accent, onSettings); return }
    if (board == null) { Loading(accent); return }
    if (!board.ok) { ErrorBox(board.message.ifBlank { "No WDGWars leaderboard data." }, accent); return }

    var view by remember { mutableStateOf(WView.TODAY) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniToggle("TODAY", view == WView.TODAY, accent, Modifier.weight(1f)) { view = WView.TODAY }
        MiniToggle("WEEK", view == WView.WEEK, accent, Modifier.weight(1f)) { view = WView.WEEK }
        MiniToggle("ALL-TIME", view == WView.ALLTIME, accent, Modifier.weight(1f)) { view = WView.ALLTIME }
        MiniToggle("CELLS", view == WView.CELLS, accent, Modifier.weight(1f)) { view = WView.CELLS }
    }
    Spacer(Modifier.height(14.dp))

    val list = when (view) {
        WView.TODAY -> board.today; WView.WEEK -> board.week; WView.ALLTIME -> board.allTime; WView.CELLS -> board.cells
    }
    val unit = when (view) {
        WView.TODAY -> "captures today"; WView.WEEK -> "captures this week"; WView.ALLTIME -> "total captures"; WView.CELLS -> "cell towers"
    }
    if (list.isEmpty()) { Text("No data for this board yet.", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp); return }

    val myName = me?.name ?: ""
    WdgwPodium(list.map { it.name to it.score }, accent)
    Spacer(Modifier.height(8.dp))
    Text("wdgwars.pl · $unit${asOf(board.asOf)}", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    Spacer(Modifier.height(16.dp))

    if (me != null && myName.isNotBlank() && view != WView.CELLS && list.none { it.name.equals(myName, true) }) {
        val (r, sc) = when (view) {
            WView.TODAY -> me.rankToday to me.recentToday
            WView.WEEK -> me.rankWeek to me.recent7d
            WView.ALLTIME -> (me.rankAllTime ?: board.allTime.firstOrNull { it.name.equals(myName, true) }?.rank) to me.total
            else -> null to 0L
        }
        if (r != null) { YouChip(r.toLong(), myName, Leaderboard.fmt(sc), accent); Spacer(Modifier.height(16.dp)) }
    }
    val maxVal = (list.maxOfOrNull { it.score } ?: 1L).coerceAtLeast(1L)
    list.drop(3).forEach { h ->
        val mine = myName.isNotBlank() && h.name.equals(myName, true)
        val sub = if (view == WView.ALLTIME) "wifi ${Leaderboard.fmt(h.wifi)} · ble ${Leaderboard.fmt(h.ble)}" else null
        RankRow(h.rank, h.name + (if (h.patron) " ★" else ""), h.score, maxVal, mine, accent, sub = sub)
    }
}

@Composable
private fun WdgwGangsTab(board: WdgwStats.Board?, hasWdgw: Boolean, accent: Color, onSettings: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    if (!hasWdgw) { ConnectPrompt("Connect WDGWars", "The wdgwars.pl leaderboard needs your WDGWars API key.", accent, onSettings); return }
    if (board == null) { Loading(accent); return }
    if (!board.ok) { ErrorBox(board.message.ifBlank { "No WDGWars leaderboard data." }, accent); return }
    val gangs = board.gangs
    if (gangs.isEmpty()) { Text("No gang data.", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp); return }
    WdgwPodium(gangs.map { it.name to it.apCount }, accent)
    Spacer(Modifier.height(8.dp))
    Text("wdgwars.pl · gangs · APs discovered${asOf(board.asOf)}", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    Spacer(Modifier.height(16.dp))
    val maxVal = (gangs.maxOfOrNull { it.apCount } ?: 1L).coerceAtLeast(1L)
    gangs.drop(3).forEach { g ->
        RankRow(g.rank, g.name, g.apCount, maxVal, false, accent, sub = "${Leaderboard.fmt(g.members)} members")
    }
}

@Composable
private fun WdgwYouTab(board: WdgwStats.Board?, me: WdgwStats.Me?, hasWdgw: Boolean, accent: Color, onSettings: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    if (!hasWdgw) { ConnectPrompt("Connect WDGWars", "Add your WDGWars API key to see your rank, totals and gang here.", accent, onSettings); return }
    if (me == null) { Loading(accent); return }

    val myRank = me.rankAllTime ?: board?.allTime?.firstOrNull { it.name.equals(me.name, true) }?.rank
    val gangRow = me.gang?.let { g -> board?.gangs?.firstOrNull { it.name.equals(g, true) } }
    val topN = me.rankTopN ?: 50

    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, accent, RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Palette.surface)), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(me.name.ifBlank { "You" }, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 34.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth())
        Text("WDGWars · all-time", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                AutoSizeText(if (myRank != null) "#${fmtGrouped(myRank.toLong())}" else "—",
                    gold, maxSize = 46.sp, minSize = 18.sp, modifier = Modifier.fillMaxWidth())
                Text("YOUR RANK", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                AutoSizeText(if (gangRow != null) "#${fmtGrouped(gangRow.rank.toLong())}" else "—",
                    accent, maxSize = 46.sp, minSize = 18.sp, modifier = Modifier.fillMaxWidth())
                Text("GANG RANK", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        if (myRank == null) {
            Spacer(Modifier.height(8.dp))
            Text("Outside the top $topN — WDGWars only ranks the top $topN, so keep climbing to earn a number.",
                color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BigStat("Total", me.total, accent)
            BigStat("Wi-Fi", me.wifi, accent)
            BigStat("BLE", me.ble, accent)
        }
        Spacer(Modifier.height(14.dp))
        Text("${fmtGrouped(me.recentToday)} today · ${fmtGrouped(me.recent7d)} this week",
            color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    }
    Spacer(Modifier.height(22.dp))

    if (gangRow != null) {
        Text("YOUR GANG", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(me.gangRole?.let { "you are ${it} · APs discovered" } ?: "APs discovered",
            color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        val maxVal = (board?.gangs?.maxOfOrNull { it.apCount } ?: gangRow.apCount).coerceAtLeast(1L)
        RankRow(gangRow.rank, gangRow.name, gangRow.apCount, maxVal, true, accent,
            sub = "${Leaderboard.fmt(gangRow.members)} members")
        Spacer(Modifier.height(22.dp))
    }

    Text("THIS ACCOUNT", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BigStat("Reinforced", me.reinforceTotal, accent)
        BigStat("Cracked", me.cracked, accent)
        BigStat("Credits", me.credits, accent)
    }
}

private fun asOf(s: String): String = if (s.isBlank()) "" else " · as of $s"

@Composable
private fun WdgwPodium(rows: List<Pair<String, Long>>, accent: Color) {
    val top = rows.take(3)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
        PodiumSlot(2, top.getOrNull(1)?.first ?: "—", top.getOrNull(1)?.let { Leaderboard.fmt(it.second) } ?: "—",
            top.getOrNull(1)?.let { monogram(it.first) } ?: "?", 90, champion = false, ghost = top.size < 2, accent = accent, modifier = Modifier.weight(1f))
        PodiumSlot(1, top.getOrNull(0)?.first ?: "—", top.getOrNull(0)?.let { Leaderboard.fmt(it.second) } ?: "—",
            top.getOrNull(0)?.let { monogram(it.first) } ?: "?", 128, champion = true, ghost = top.isEmpty(), accent = accent, modifier = Modifier.weight(1f))
        PodiumSlot(3, top.getOrNull(2)?.first ?: "—", top.getOrNull(2)?.let { Leaderboard.fmt(it.second) } ?: "—",
            top.getOrNull(2)?.let { monogram(it.first) } ?: "?", 70, champion = false, ghost = top.size < 3, accent = accent, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun UsersTab(
    standings: WigleStats.Standings?,
    sort: String,
    personal: WigleStats.Personal?,
    hasCreds: Boolean,
    accent: Color,
    onSettings: () -> Unit,
    onUser: (WigleStats.UserDetail) -> Unit,
    onSort: (String) -> Unit
) {

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniToggle("ALL-TIME", sort == WigleStats.SORT_ALLTIME, accent, Modifier.weight(1f)) { onSort(WigleStats.SORT_ALLTIME) }
        MiniToggle("THIS MONTH", sort == WigleStats.SORT_MONTH, accent, Modifier.weight(1f)) { onSort(WigleStats.SORT_MONTH) }
    }
    Spacer(Modifier.height(12.dp))

    if (standings == null) { Loading(accent); return }
    if (!standings.ok || standings.users.isEmpty()) { ErrorBox(standings.message, accent); return }

    val month = sort == WigleStats.SORT_MONTH
    val users = standings.users
    fun value(u: WigleStats.WUser) = if (month) u.monthCount else u.wifiGps
    val unit = if (month) "new this month" else "Wi-Fi discovered"
    val myName = standings.myName.ifBlank { personal?.name ?: "" }

    fun openUser(u: WigleStats.WUser) {
        val mine = u.self || (myName.isNotBlank() && u.name.equals(myName, true))
        if (mine && personal != null) onUser(WigleStats.detailFromPersonal(personal))
        else onUser(WigleStats.detailFromUser(u, month))
    }

    val top = users.take(3)
    fun tapU(u: WigleStats.WUser?) = u?.let { Modifier.clickable { openUser(it) } } ?: Modifier
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
        PodiumSlot(2, top.getOrNull(1)?.name ?: "—", top.getOrNull(1)?.let { Leaderboard.fmt(value(it)) } ?: "—",
            top.getOrNull(1)?.let { monogram(it.name) } ?: "?", 90, champion = false, ghost = top.size < 2, accent = accent, modifier = Modifier.weight(1f).then(tapU(top.getOrNull(1))))
        PodiumSlot(1, top.getOrNull(0)?.name ?: "—", top.getOrNull(0)?.let { Leaderboard.fmt(value(it)) } ?: "—",
            top.getOrNull(0)?.let { monogram(it.name) } ?: "?", 128, champion = true, ghost = top.isEmpty(), accent = accent, modifier = Modifier.weight(1f).then(tapU(top.getOrNull(0))))
        PodiumSlot(3, top.getOrNull(2)?.name ?: "—", top.getOrNull(2)?.let { Leaderboard.fmt(value(it)) } ?: "—",
            top.getOrNull(2)?.let { monogram(it.name) } ?: "?", 70, champion = false, ghost = top.size < 3, accent = accent, modifier = Modifier.weight(1f).then(tapU(top.getOrNull(2))))
    }
    Spacer(Modifier.height(8.dp))
    Text("${Leaderboard.fmt(standings.totalUsers)} wardrivers ranked · $unit · excl. anonymous",
        color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    Spacer(Modifier.height(16.dp))

    if (personal != null && myName.isNotBlank() && users.none { it.self || it.name.equals(myName, true) }) {
        YouChip(rank = if (month) personal.monthRank else personal.rank, name = myName,
            value = if (month) "#${fmtGrouped(personal.monthRank)}" else fmtGrouped(personal.wifiGps),
            accent = accent)
        Spacer(Modifier.height(16.dp))
    }

    val rest = users.drop(3)
    val maxVal = (users.maxOfOrNull { value(it) } ?: 1L).coerceAtLeast(1L)
    rest.forEach { u ->
        val mine = u.self || (myName.isNotBlank() && u.name.equals(myName, true))

        val sub = if (month)
            "all-time ${fmtGrouped(u.wifiGps)}" + (if (u.wifiPercent > 0) " · ${pct(u.wifiPercent)} of WiGLE" else "")
        else if (u.wifiPercent > 0) "${pct(u.wifiPercent)} of all WiGLE Wi-Fi" else null
        RankRow(u.rank.toInt(), u.name, value(u), maxVal, mine, accent, sub = sub,
            onClick = { openUser(u) })
    }
    if (!hasCreds) {
        Spacer(Modifier.height(14.dp))
        ConnectPrompt("See your own rank",
            "Add your WiGLE API Name and Token to highlight your position on this board.",
            accent, onSettings)
    }
}

@Composable
private fun TeamsTab(groups: WigleStats.Groups?, accent: Color, onSelectGroup: (WigleStats.WGroup) -> Unit) {
    Spacer(Modifier.height(4.dp))
    if (groups == null) { Loading(accent); return }
    if (!groups.ok || groups.groups.isEmpty()) { ErrorBox(groups.message, accent); return }

    val list = groups.groups
    val top = list.take(3)
    fun tap(g: WigleStats.WGroup?) = g?.let { Modifier.clickable { onSelectGroup(it) } } ?: Modifier
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
        PodiumSlot(2, top.getOrNull(1)?.name ?: "—", top.getOrNull(1)?.let { Leaderboard.fmt(it.discovered) } ?: "—",
            top.getOrNull(1)?.let { monogram(it.name) } ?: "?", 90, champion = false, ghost = top.size < 2, accent = accent, modifier = Modifier.weight(1f).then(tap(top.getOrNull(1))))
        PodiumSlot(1, top.getOrNull(0)?.name ?: "—", top.getOrNull(0)?.let { Leaderboard.fmt(it.discovered) } ?: "—",
            top.getOrNull(0)?.let { monogram(it.name) } ?: "?", 128, champion = true, ghost = top.isEmpty(), accent = accent, modifier = Modifier.weight(1f).then(tap(top.getOrNull(0))))
        PodiumSlot(3, top.getOrNull(2)?.name ?: "—", top.getOrNull(2)?.let { Leaderboard.fmt(it.discovered) } ?: "—",
            top.getOrNull(2)?.let { monogram(it.name) } ?: "?", 70, champion = false, ghost = top.size < 3, accent = accent, modifier = Modifier.weight(1f).then(tap(top.getOrNull(2))))
    }
    Spacer(Modifier.height(8.dp))
    Text("${Leaderboard.fmt(list.size.toLong())} teams · tap a team for member ranking", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    Spacer(Modifier.height(16.dp))

    val maxVal = (list.maxOfOrNull { it.discovered } ?: 1L).coerceAtLeast(1L)
    list.drop(3).forEachIndexed { i, g ->
        RankRow(i + 4, g.name, g.discovered, maxVal, false, accent,
            sub = "${Leaderboard.fmt(g.members)} members · owner ${g.owner} · total ${fmtGrouped(g.total)}", onClick = { onSelectGroup(g) })
    }
}

@Composable
private fun TeamMembersTab(group: WigleStats.WGroup, myName: String, wigleName: String, wigleToken: String, accent: Color, onUser: (WigleStats.UserDetail) -> Unit, onBack: () -> Unit) {
    val members by produceState<WigleStats.GroupMembers?>(initialValue = null, group.id) {
        value = withContext(Dispatchers.IO) { WigleStats.groupMembers(group.id, myName, wigleName, wigleToken) }
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onBack() }.padding(vertical = 6.dp)) {
        Icon(Icons.Filled.ArrowBack, "back to teams", tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(group.name, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
    }
    Text("${Leaderboard.fmt(group.members)} members · owner ${group.owner} · ranked by Wi-Fi discovered",
        color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    Spacer(Modifier.height(12.dp))
    val m = members
    when {
        m == null -> Loading(accent)
        !m.ok -> ErrorBox(m.message, accent)
        m.members.isEmpty() -> ErrorBox("No members returned for this team.", accent)
        else -> {
            val maxVal = (m.members.maxOfOrNull { it.discovered } ?: 1L).coerceAtLeast(1L)
            m.members.forEach { mem ->
                TeamMemberRow(mem, maxVal, accent) { onUser(WigleStats.detailFromMember(mem, group.name)) }
            }
        }
    }
}

@Composable
private fun TeamMemberRow(mem: WigleStats.GroupMember, maxVal: Long, accent: Color, onClick: () -> Unit) {
    val mine = mem.self
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, if (mine) accent else Palette.line, RoundedCornerShape(10.dp))
            .background((if (mine) accent.copy(alpha = 0.12f) else Palette.surface.copy(alpha = 0.8f)), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${mem.rank}", color = if (mine) accent else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mem.name, color = if (mine) accent else Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp, maxLines = 1)
                if (mine) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Bolt, null, tint = accent, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).background(Palette.panel, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth((mem.discovered.toFloat() / maxVal).coerceIn(0.02f, 1f)).height(5.dp)
                    .background(accent, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.height(5.dp))

            Text("${fmtGrouped(mem.discovered)} Wi-Fi discovered", color = if (mine) accent else Palette.ink,
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            Text("total ${fmtGrouped(mem.total)} · this month ${fmtGrouped(mem.monthCount)} · last month ${fmtGrouped(mem.prevMonthCount)}",
                color = Palette.muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
            if (mem.firstSeen.isNotBlank() || mem.lastSeen.isNotBlank()) {
                Text("active ${mem.firstSeen.ifBlank { "?" }} → ${mem.lastSeen.ifBlank { "?" }}",
                    color = Palette.muted.copy(alpha = 0.8f), fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun UserDetailScreen(d: WigleStats.UserDetail, accent: Color, onBack: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onBack() }.padding(vertical = 6.dp)) {
        Icon(Icons.Filled.ArrowBack, "back", tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Back", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
    Spacer(Modifier.height(6.dp))

    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, accent, RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Palette.surface)), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(d.name, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(if (d.self) "WiGLE · you · ${d.context}" else "WiGLE · ${d.context}",
            color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        if (d.rank > 0 || d.monthRank > 0) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    RankWithDelta(if (d.rank > 0) "#${fmtGrouped(d.rank)}" else "—", d.rankDelta, gold)
                    Text("ALL-TIME", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f)) {
                    RankWithDelta(if (d.monthRank > 0) "#${fmtGrouped(d.monthRank)}" else "—", d.monthRankDelta, accent)
                    Text("THIS MONTH", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        } else if (d.teamRank > 0) {
            Text("#${d.teamRank}", color = gold, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp)
            Text("RANK IN ${d.context.uppercase()}", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.height(16.dp))
        Text("${fmtGrouped(d.wifiGps)} Wi-Fi discovered", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        val wifiSub = buildString {
            if (d.wifiTotal > 0) append("total ${fmtGrouped(d.wifiTotal)}")
            if (d.wifiPercent > 0) { if (isNotEmpty()) append(" · "); append("${pct(d.wifiPercent)} of all WiGLE") }
        }
        if (wifiSub.isNotBlank()) Text(wifiSub, color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
        if (d.bt > 0 || d.cell > 0 || d.totalWiFiLocations > 0) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (d.bt > 0) BigStat("Bluetooth", d.bt, accent)
                if (d.cell > 0) BigStat("Cell", d.cell, accent)
                if (d.totalWiFiLocations > 0) BigStat("Observations", d.totalWiFiLocations, accent)
            }
        }
    }
    Spacer(Modifier.height(18.dp))

    Text("ACTIVITY", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    DetailRow("This month", fmtGrouped(d.monthCount), accent)
    DetailRow("Last month", fmtGrouped(d.prevMonthCount), accent)
    if (d.monthCount > 0 || d.prevMonthCount > 0) {
        val md = d.monthDelta
        Text((if (md >= 0) "▲ ${fmtGrouped(md)}" else "▼ ${fmtGrouped(-md)}") + " vs last month",
            color = if (md >= 0) raceUp else raceDown, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }
    if (d.btTotal > 0) DetailRow("Bluetooth (incl. no-GPS)", fmtGrouped(d.btTotal), accent)
    if (d.cellTotal > 0) DetailRow("Cell (incl. no-GPS)", fmtGrouped(d.cellTotal), accent)
    if (d.firstSeen.isNotBlank()) DetailRow("First upload", d.firstSeen, accent)
    if (d.lastSeen.isNotBlank()) DetailRow("Last upload", d.lastSeen, accent)
    Spacer(Modifier.height(10.dp))
    Text("Live from WiGLE · tap Back to return", color = Palette.muted, fontFamily = Mono, fontSize = 10.sp)
}

@Composable
private fun RankWithDelta(text: String, delta: Long, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AutoSizeText(text, color, maxSize = 40.sp, minSize = 16.sp, modifier = Modifier.weight(1f))
        if (delta != 0L) {
            val up = delta > 0
            Spacer(Modifier.width(6.dp))
            Text((if (up) "▲" else "▼") + fmtGrouped(kotlin.math.abs(delta)),
                color = if (up) raceUp else raceDown, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = Palette.ink, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun YouTab(stats: Stats, personal: WigleStats.Personal?, race: List<WigleStats.WUser>, hasCreds: Boolean, accent: Color, onSettings: () -> Unit, onUser: (WigleStats.UserDetail) -> Unit) {
    Spacer(Modifier.height(4.dp))
    if (personal != null) {
        HeroCard(personal, accent)
        Spacer(Modifier.height(18.dp))
        RacePanel(race, accent, onUser)
        Spacer(Modifier.height(22.dp))
    } else if (!hasCreds) {
        ConnectPrompt("Connect WiGLE",
            "Add your WiGLE API Name and Token to see your global rank, monthly standing and totals here.",
            accent, onSettings)
        Spacer(Modifier.height(22.dp))
    }

    Text("THIS DEVICE", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Leaderboard.records(stats).forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(r.label, color = Palette.ink, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(r.value, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(18.dp))

    Text("THIS RUN  ${Leaderboard.runUnlockedCount(stats)}/${Leaderboard.runAchievements.size}",
        color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Text("since your last WiGLE send — resets when you send to WiGLE", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    Spacer(Modifier.height(8.dp))
    Leaderboard.runAchievements.forEach { a -> AchievementRow(a, stats, accent, gold) }
    Spacer(Modifier.height(18.dp))

    Text("ACHIEVEMENTS  ${Leaderboard.unlockedCount(stats)}/${Leaderboard.achievements.size}",
        color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Leaderboard.achievements.forEach { a -> AchievementRow(a, stats, accent, gold) }
}

@Composable
private fun AchievementRow(a: com.rocketgod.warble.core.Achievement, stats: Stats, accent: Color, gold: Color) {
    val got = a.unlocked(stats)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, if (got) accent else Palette.line, RoundedCornerShape(10.dp))
            .background(Palette.surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (got) Icons.Filled.EmojiEvents else Icons.Filled.Lock, null,
            tint = if (got) gold else Palette.muted, modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = if (got) Palette.ink else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(a.detail, color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
            if (!got) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.panel, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(a.progress(stats)).height(4.dp).background(accent, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

private val raceUp = Color(0xFF5CF55C)
private val raceDown = Color(0xFFFF3B57)

@Composable
private fun HeroCard(p: WigleStats.Personal, accent: Color) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, accent, RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Palette.surface)), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {

        Text(
            p.name.ifBlank { "You" }, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = 34.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text("WiGLE · all-time", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AutoSizeText("#${fmtGrouped(p.rank)}", gold, maxSize = 46.sp, minSize = 18.sp, modifier = Modifier.weight(1f))
                    val d = p.rankDelta
                    if (d != 0L) {
                        val up = d > 0
                        Spacer(Modifier.width(6.dp))
                        Text((if (up) "▲" else "▼") + fmtGrouped(kotlin.math.abs(d)),
                            color = if (up) raceUp else raceDown, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                Text("ALL-TIME", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                AutoSizeText("#${fmtGrouped(p.monthRank)}", accent, maxSize = 46.sp, minSize = 18.sp, modifier = Modifier.fillMaxWidth())
                Text("THIS MONTH", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            BigStat("Wi-Fi", p.wifiGps, accent, Modifier.weight(1f))
            BigStat("Bluetooth", p.bt, accent, Modifier.weight(1f))
            BigStat("Cell", p.cell, accent, Modifier.weight(1f))
        }
        if (p.totalWiFiLocations > 0) {
            Spacer(Modifier.height(14.dp))
            Text("${fmtGrouped(p.totalWiFiLocations)} total Wi-Fi observations logged",
                color = Palette.muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BigStat(label: String, value: Long, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AutoSizeText(fmtGrouped(value), accent, maxSize = 24.sp, minSize = 11.sp, modifier = Modifier.fillMaxWidth())
        Text(label, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun RacePanel(rows: List<WigleStats.WUser>, accent: Color, onUser: (WigleStats.UserDetail) -> Unit) {
    if (rows.isEmpty()) return
    Text("THE RACE", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
    Text("Wi-Fi discovered · you vs the five above and below · tap a rival", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    Spacer(Modifier.height(10.dp))
    val maxVal = (rows.maxOfOrNull { it.wifiGps } ?: 1L).coerceAtLeast(1L)
    Column { rows.forEach { u -> RaceRow(u, maxVal, accent) { if (!u.self) onUser(WigleStats.detailFromUser(u)) } } }
}

@Composable
private fun RaceRow(u: WigleStats.WUser, maxVal: Long, accent: Color, onClick: () -> Unit) {
    val mine = u.self
    val frac by animateFloatAsState(
        targetValue = (u.wifiGps.toFloat() / maxVal).coerceIn(0.03f, 1f),
        animationSpec = tween(900, easing = FastOutSlowInEasing), label = "raceBar"
    )

    val glow by rememberInfiniteTransition(label = "raceGlow").animateFloat(
        0.35f, 0.9f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "raceGlowV"
    )
    val borderColor = if (mine) accent.copy(alpha = glow) else Palette.line
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .border(if (mine) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .background((if (mine) accent.copy(alpha = 0.16f) else Palette.surface.copy(alpha = 0.7f)), RoundedCornerShape(10.dp))
            .then(if (mine) Modifier else Modifier.clickable { onClick() })
            .padding(horizontal = 12.dp, vertical = if (mine) 12.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AutoSizeText("#${fmtGrouped(u.rank)}", if (mine) accent else Palette.muted,
            maxSize = if (mine) 15.sp else 13.sp, minSize = 9.sp, modifier = Modifier.width(58.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (mine) "YOU" else u.name, color = if (mine) accent else Palette.ink, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, fontSize = if (mine) 15.sp else 13.sp, maxLines = 1)
                if (mine) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Bolt, null, tint = accent, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(if (mine) 7.dp else 5.dp).background(Palette.panel, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(frac).height(if (mine) 7.dp else 5.dp)
                    .background(if (mine) accent else accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp)))
            }
        }
        Spacer(Modifier.width(10.dp))

        Text(fmtGrouped(u.wifiGps), color = if (mine) accent else Palette.ink, fontFamily = Mono,
            fontWeight = FontWeight.Bold, fontSize = if (mine) 14.sp else 12.sp)
    }
}

@Composable
private fun RankRow(rank: Int, name: String, value: Long, maxVal: Long, mine: Boolean, accent: Color, sub: String?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, if (mine) accent else Palette.line, RoundedCornerShape(10.dp))
            .background((if (mine) accent.copy(alpha = 0.12f) else Palette.surface.copy(alpha = 0.8f)), RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$rank", color = Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (mine) accent else Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).background(Palette.panel, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth((value.toFloat() / maxVal).coerceIn(0.02f, 1f)).height(5.dp)
                    .background(accent, RoundedCornerShape(3.dp)))
            }

            Text(fmtGrouped(value), color = if (mine) accent else Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, color = Palette.muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun YouChip(rank: Long, name: String, value: String, accent: Color) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, accent, RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Bolt, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("YOU · #${fmtGrouped(rank)}", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.weight(1f))
        Text("$name  $value", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Loading(accent: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text("Pulling live WiGLE standings…", color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorBox(message: String, accent: Color) {
    Column(
        Modifier.fillMaxWidth().padding(top = 30.dp)
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
            .background(Palette.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp)
    ) {
        Text("Couldn't reach WiGLE", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(message.ifBlank { "No connection or no data returned." }, color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("Check your internet connection and try again.", color = Palette.muted, fontFamily = Mono, fontSize = 11.sp)
    }
}

@Composable
private fun TabPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .background(if (on) accent.copy(alpha = 0.20f) else Palette.surface, RoundedCornerShape(8.dp))
            .border(1.dp, if (on) accent.copy(alpha = 0.7f) else Palette.line, RoundedCornerShape(8.dp))
            .clickable { onClick() }.padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (on) accent else Palette.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) accent else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun MiniToggle(label: String, on: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (on) accent.copy(alpha = 0.18f) else Palette.surface, RoundedCornerShape(7.dp))
            .border(1.dp, if (on) accent else Palette.line, RoundedCornerShape(7.dp))
            .clickable { onClick() }.padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) accent else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
