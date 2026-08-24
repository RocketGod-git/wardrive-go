package com.rocketgod.warble

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.rocketgod.warble.classify.NotableHit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rocketgod.warble.core.PendingExport
import com.rocketgod.warble.core.Repository
import com.rocketgod.warble.core.Leaderboard
import com.rocketgod.warble.data.ExportSessionEntity
import com.rocketgod.warble.data.TypeAggRow
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.FeedEvent
import com.rocketgod.warble.model.FeedKind
import com.rocketgod.warble.model.FeedTone
import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.model.SortMode
import com.rocketgod.warble.model.TypeFilter
import com.rocketgod.warble.model.Skin
import com.rocketgod.warble.model.Stats
import com.rocketgod.warble.model.TypeStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

enum class SendPhase { IDLE, WRITING, UPLOADING, SAVING, SENT }

private const val SPOT_CHANNEL = "wardrive_spot"

class WarbleViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repository(app, viewModelScope)

    val live: StateFlow<List<Contact>> = repo.liveFlow
    val gnss: StateFlow<com.rocketgod.warble.core.GnssStat> = repo.gnssFlow
    val speedMps: StateFlow<Float?> = repo.location.speed
    val bearingDeg: StateFlow<Float?> = repo.location.bearing
    val distanceM: StateFlow<Double> = repo.location.distanceM

    val discoveryRate = MutableStateFlow(0)

    @Volatile var notificationsEnabled: Boolean = true

    @Volatile var appInForeground: Boolean = false

    @Volatile var buzzOnSpot: Boolean = true

    private fun buzzSpot(hit: NotableHit) {
        if (!notificationsEnabled) return
        if (appInForeground) return
        val ctx = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(SPOT_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(SPOT_CHANNEL, "Spot alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Buzzes when a notable device (camera, drone, tracker, Flipper) is first seen nearby. Mirrors to a paired watch."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 220, 120, 220)
                setShowBadge(true)
            })
        }
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = hit.banner.ifBlank { hit.readable }
        val n = Notification.Builder(ctx, SPOT_CHANNEL)
            .setContentTitle("Spotted: $title")
            .setContentText(hit.readable)
            .setSmallIcon(R.drawable.ic_stat_wardrive)
            .setColor(hit.category.colorArgb.toInt())
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify("spot_${hit.brand}_${hit.category}".hashCode(), n)
    }

    private var lastWatchSig: String? = null

    private suspend fun publishToWatchLoop() {
        val client = runCatching { Wearable.getDataClient(getApplication<Application>()) }.getOrNull() ?: return
        while (true) {
            delay(3000)
            val fe = feedEvent.value
            val wifi = newUnsentWifi.value; val ble = newUnsentBle.value; val cell = newUnsentCell.value
            val rate = discoveryRate.value; val spd = speedMps.value ?: -1f; val dist = distanceM.value
            val prefs = getApplication<Application>()
                .getSharedPreferences("warble", android.content.Context.MODE_PRIVATE)
            val metric = prefs.getBoolean("use_metric", java.util.Locale.getDefault().country !in setOf("US", "GB", "LR", "MM"))
            val accent = skin.value.accentHex

            val goalPref = prefs.getInt("wifi_goal", 0)
            val goal = if (goalPref > 0) goalPref else wifiBestRun.value.toInt()
            val sig = "$wifi/$ble/$cell/$rate/$spd/${dist.toInt()}/$metric/${fe?.eyebrow}/${fe?.title}/${fe?.colorArgb}/$accent/$goal"
            if (sig == lastWatchSig) continue
            lastWatchSig = sig
            val req = PutDataMapRequest.create("/wardrive/stats")
            req.dataMap.apply {
                putInt("wifi", wifi.toInt()); putInt("ble", ble.toInt()); putInt("cell", cell.toInt())
                putInt("rate", rate); putFloat("speed", spd); putDouble("dist", dist); putBoolean("metric", metric)
                putString("b_eyebrow", fe?.eyebrow ?: ""); putString("b_title", fe?.title ?: "")
                putLong("b_color", fe?.colorArgb ?: 0L)
                putLong("accent", accent)
                putInt("wifi_goal", goal)
                putLong("ts", System.currentTimeMillis())
            }
            req.setUrgent()
            runCatching { client.putDataItem(req.asPutDataRequest()) }
        }
    }

    private var lastWidgetSig = ""

    private class HeldThreat(val label: String, val lane: String, var rssi: Int, var lastSeen: Long)
    private val heldThreats = HashMap<String, HeldThreat>()
    private val HOLD_THREAT_MS = 30_000L
    private val HOLD_BANNER_MS = 60_000L

    private var heldBannerEyebrow = ""; private var heldBannerTitle = ""; private var heldBannerColor = 0L
    private var heldBannerAt = 0L

    private suspend fun publishWidgetLoop() {
        val app = getApplication<Application>()
        while (true) {
            delay(2500)
            val now = System.currentTimeMillis()
            val snap = live.value
            val ap = snap.count { it.type == com.rocketgod.warble.model.SignalType.WIFI && it.category != "WiFi Client" }
            val nw = newUnsentWifi.value; val nb = newUnsentBle.value; val nc = newUnsentCell.value
            val scan = scanningState.value
            val beast = now - com.rocketgod.warble.usb.UsbBeast.lastFrameMs < 15_000L

            var atk = 0; var surv = 0; var trk = 0
            for (c in snap) {
                val t = com.rocketgod.warble.classify.NotableDevices.threat(c.name, c.key, c.companyId, c.category) ?: continue
                when (t.lane) {
                    com.rocketgod.warble.classify.ThreatLane.ATTACK -> atk++
                    com.rocketgod.warble.classify.ThreatLane.SURVEILLANCE -> surv++
                    com.rocketgod.warble.classify.ThreatLane.TRACKING -> trk++
                }

                heldThreats[c.key]?.also { it.rssi = c.rssi; it.lastSeen = now }
                    ?: run { heldThreats[c.key] = HeldThreat(t.label, t.lane.name, c.rssi, now) }
            }
            val threat = atk + surv + trk

            heldThreats.entries.iterator().let { itr -> while (itr.hasNext()) if (now - itr.next().value.lastSeen > HOLD_THREAT_MS) itr.remove() }
            val held = heldThreats.values.sortedByDescending { it.lastSeen }
            val listJson = org.json.JSONArray().apply {
                held.take(24).forEachIndexed { i, h -> put(i, org.json.JSONObject().put("l", h.label).put("n", h.lane)) }
            }
            val listStr = listJson.toString()
            val heldCount = held.size

            feedEvent.value?.let { heldBannerEyebrow = it.eyebrow; heldBannerTitle = it.title; heldBannerColor = it.colorArgb ?: 0L; heldBannerAt = now }
            val bannerLive = heldBannerTitle.isNotBlank() && now - heldBannerAt < HOLD_BANNER_MS
            val bEyebrow = if (bannerLive) heldBannerEyebrow else ""
            val bTitle = if (bannerLive) heldBannerTitle else ""
            val bColor = if (bannerLive) heldBannerColor else 0L

            val rate = discoveryRate.value; val spd = speedMps.value ?: -1f; val dist = distanceM.value

            val st = _stats.value
            val lifeWifi = st.wifiLifetime
            val lifeBt = st.perType.firstOrNull { it.type == com.rocketgod.warble.model.SignalType.BLE }?.unique ?: 0L
            val lifeCell = st.perType.firstOrNull { it.type == com.rocketgod.warble.model.SignalType.CELL }?.unique ?: 0L
            val lifeTotal = st.lifetime
            val sig = "$ap/$nw/$nb/$nc/$scan/$beast/$threat/$atk/$surv/$trk/$heldCount/${listStr.hashCode()}/$bTitle/$bColor/$rate/${spd.toInt()}/${dist.toInt()}/$lifeWifi/$lifeBt/$lifeCell/$lifeTotal"
            if (sig == lastWidgetSig) continue
            lastWidgetSig = sig
            app.getSharedPreferences("warble", android.content.Context.MODE_PRIVATE).edit()
                .putInt("w_ap", ap).putLong("w_nw", nw).putLong("w_nb", nb).putLong("w_nc", nc)
                .putBoolean("w_scan", scan).putBoolean("w_beast", beast)
                .putInt("w_threat", threat).putInt("w_atk", atk).putInt("w_surv", surv).putInt("w_trk", trk)
                .putInt("w_threat_held", heldCount)
                .putString("w_threat_list", listStr)
                .putString("w_b_eyebrow", bEyebrow).putString("w_b_title", bTitle).putLong("w_b_color", bColor)
                .putInt("w_rate", rate).putFloat("w_speed", spd).putFloat("w_dist", dist.toFloat())
                .putLong("w_life_wifi", lifeWifi).putLong("w_life_bt", lifeBt)
                .putLong("w_life_cell", lifeCell).putLong("w_life_total", lifeTotal)
                .apply()
            StatsWidget.update(app)
            OffensiveWidget.update(app)
            OffensiveListWidget.notifyChanged(app)
            ToolConsoleWidget.update(app)
            BannerWidget.update(app)
            AllTimeWidget.update(app)
        }
    }

    init {
        viewModelScope.launch { publishToWatchLoop() }
        viewModelScope.launch { publishWidgetLoop() }

        viewModelScope.launch {
            repo.notableSightings.collect { hit ->

                post(FeedEvent(FeedKind.SPOTTED, "", hit.banner, FeedTone.HOT, colorArgb = hit.category.colorArgb))
                if (buzzOnSpot) buzzSpot(hit)
            }
        }
        viewModelScope.launch {
            val win = ArrayDeque<Pair<Long, Long>>()
            while (true) {

                kotlinx.coroutines.delay(3000)
                val now = System.currentTimeMillis()

                val cur = _stats.value.wifiThisRun

                if (win.isNotEmpty() && cur < win.last().second) win.clear()
                win.addLast(now to cur)
                while (win.size > 1 && now - win.first().first > 60_000L) win.removeFirst()
                discoveryRate.value = if (win.size >= 2) {
                    val dt = (now - win.first().first).coerceAtLeast(1L)
                    val dv = cur - win.first().second
                    ((dv.toDouble() / dt) * 60_000.0).toInt().coerceAtLeast(0)
                } else 0
            }
        }
    }
    val gnssSats: StateFlow<List<com.rocketgod.warble.data.GnssSatEntity>> =
        repo.gnssSats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val cellTowers: StateFlow<List<com.rocketgod.warble.data.CellTowerEntity>> =
        repo.cellTowers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pmkids: StateFlow<List<com.rocketgod.warble.data.PmkidEntity>> =
        repo.pmkids().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val privacyZones: StateFlow<List<com.rocketgod.warble.data.PrivacyZone>> =
        repo.privacyZones().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val blockedDevices: StateFlow<List<com.rocketgod.warble.data.BlockedDevice>> =
        repo.blockedDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun isBlockedFlow(bssid: String) =
        repo.isBlockedFlow(bssid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun addPrivacyZone(lat: Double, lng: Double, radiusM: Double, label: String) =
        viewModelScope.launch { repo.addPrivacyZone(lat, lng, radiusM, label) }
    fun setZoneEnabled(id: Long, on: Boolean) = viewModelScope.launch { repo.setZoneEnabled(id, on) }
    fun deletePrivacyZone(id: Long) = viewModelScope.launch { repo.deletePrivacyZone(id) }
    fun blockDevice(bssid: String, label: String?) = viewModelScope.launch { repo.blockDevice(bssid, label) }
    fun unblockDevice(bssid: String) = viewModelScope.launch { repo.unblockDevice(bssid) }
    fun lastLatLng(): Pair<Double, Double>? = repo.location.last?.let { it.latitude to it.longitude }

    fun lastPose(): Triple<Double, Double, Float?>? = repo.location.last?.let {
        val heading = if (it.hasBearing() && it.hasSpeed() && it.speed > 0.5f) it.bearing else null
        Triple(it.latitude, it.longitude, heading)
    }

    suspend fun findMapPoints(): List<com.rocketgod.warble.ui.MapPoint> {
        val blocked = repo.blockedKeysNow()
        return repo.mapPoints().asSequence().filter { it.key.lowercase() !in blocked }
            .map { com.rocketgod.warble.ui.MapPoint(it.lat, it.lng, it.name ?: "", it.key) }.toList()
    }

    suspend fun findMapPointsIn(south: Double, north: Double, west: Double, east: Double): List<com.rocketgod.warble.ui.MapPoint> {
        val blocked = repo.blockedKeysNow()
        return repo.mapPointsIn(south, north, west, east).asSequence().filter { it.key.lowercase() !in blocked }
            .map { com.rocketgod.warble.ui.MapPoint(it.lat, it.lng, it.name ?: "", it.key) }.toList()
    }

    suspend fun recentLocatedLatLng(): Pair<Double, Double>? = repo.recentLocatedLatLng()

    suspend fun mapMarkersAll(): List<com.rocketgod.warble.ui.MapMarker> = mapMarkersIn(-90.0, 90.0, -180.0, 180.0)

    suspend fun mapMarkersIn(south: Double, north: Double, west: Double, east: Double): List<com.rocketgod.warble.ui.MapMarker> =
        com.rocketgod.warble.ui.buildMapMarkersIn(repo, south, north, west, east)

    fun savePmkid(hit: com.rocketgod.warble.usb.PmkidCapture.Hit) {
        repo.savePmkid(hit)

        post(FeedEvent(FeedKind.CAPTURE, if (hit.kind == 2) "HANDSHAKE" else "PMKID", "CAPTURED", FeedTone.GOLD))
    }

    private val _stats = MutableStateFlow(EMPTY_STATS)
    val stats: StateFlow<Stats> = _stats

    val skin = MutableStateFlow(
        runCatching {
            Skin.valueOf(getApplication<Application>()
                .getSharedPreferences("warble", android.content.Context.MODE_PRIVATE)
                .getString("skin", "TEAL") ?: "TEAL")
        }.getOrDefault(Skin.TEAL)
    )
    val listingFilter = MutableStateFlow(SignalType.BLE)
    val wardrive = MutableStateFlow(false)
    val onboarded = MutableStateFlow(false)
    val banner = MutableStateFlow("Radar live")
    val scanningState = MutableStateFlow(false)
    val sortMode = MutableStateFlow(SortMode.SIGNAL)
    val typeFilter = MutableStateFlow(TypeFilter.ALL)
    val feedEvent = MutableStateFlow<FeedEvent?>(null)

    val categoryTally = MutableStateFlow<List<Pair<String, Int>>>(emptyList())

    val hashesThisRun = MutableStateFlow(0L)

    private var scanning = false

    init {

        warblePrefs().let { p ->
            if (!p.getBoolean("seq_reset_v140", false)) {
                p.edit().remove("export_seq_WiGLE").remove("export_seq_WDGWars")
                    .remove("export_seq_File").putBoolean("seq_reset_v140", true).apply()
            }
        }
        viewModelScope.launch {
            try {
                repo.ensureRun()
                wireStats()
            } catch (t: Throwable) {
                banner.value = "Init error: ${t.message}"
            }
        }
    }

    private fun wireStats() {

        viewModelScope.launch {
            while (true) {
                runCatching { refreshStats() }
                delay(3000)
            }
        }

        viewModelScope.launch {
            repo.runIdFlow.collect { runCatching { refreshStats() } }
        }

        viewModelScope.launch {
            live.collect { liveList ->
                val s = _stats.value
                if (s.nearby != liveList.size) _stats.value = s.copy(nearby = liveList.size)
            }
        }
    }

    private suspend fun refreshStats() {
        val dao = repo.dao()
        val rid = repo.currentRunId()
        val byType = withContext(Dispatchers.IO) { dao.dashByType(rid) }
        val cats = withContext(Dispatchers.IO) { dao.categoryCountsNow() }
        val bestRunRuns = withContext(Dispatchers.IO) { dao.bestRunNow() }
        val finishedRuns = withContext(Dispatchers.IO) { dao.finishedRunCountNow() }
        val wifiBest = withContext(Dispatchers.IO) { dao.bestRunForTypeNow("WIFI") }
        hashesThisRun.value = repo.captureCountThisRun()

        val pt = com.rocketgod.warble.model.SignalType.values().map { t ->
            val a = byType.firstOrNull { it.type == t.name }
            TypeStat(t, a?.observations ?: 0, a?.unique ?: 0, a?.newThisRun ?: 0, a?.inWigle ?: 0)
        }
        val wifi = pt.firstOrNull { it.type == com.rocketgod.warble.model.SignalType.WIFI }
        val lifetime = byType.sumOf { it.unique }
        val thisRun = byType.sumOf { it.newThisRun }
        fun notWigle(t: String) = byType.firstOrNull { it.type == t }?.notWigle ?: 0
        fun notWdgw(t: String) = byType.firstOrNull { it.type == t }?.notWdgw ?: 0
        fun cat(name: String) = cats.firstOrNull { it.category == name }?.c?.toLong() ?: 0L

        val s = Stats(
            thisRun = thisRun, lifetime = lifetime, bestRun = maxOf(bestRunRuns, thisRun),
            runs = finishedRuns, unidentified = cat("Unidentified"), smartHome = cat("Smart home"),
            nearby = repo.nearbyCount(), perType = pt,
            wifiLifetime = wifi?.unique ?: 0,
            wifiThisRun = wifi?.newThisRun ?: 0,
            wifiBestRun = maxOf(wifiBest, wifi?.newThisRun ?: 0)
        )
        _stats.value = s
        detectFeed(s, notWigle("WIFI"))
        categoryTally.value = cats.map { it.category to it.c }.sortedByDescending { it.second }
        newForWigle.value = notWigle("WIFI"); newForWdgw.value = notWdgw("WIFI")
        newUnsentWifi.value = notWigle("WIFI")
        newUnsentBle.value = notWigle("BLE")
        newUnsentCell.value = notWigle("CELL")
    }

    private data class Tier(val id: String, val threshold: Long, val title: String)
    private val ladder = listOf(
        Tier("first_contact", 1, "First Contact"),
        Tier("century", 100, "Century"),
        Tier("thousand_cuts", 1_000, "Thousand Cuts"),
        Tier("contacts_10k", 10_000, "Signal Storm"),
        Tier("contacts_50k", 50_000, "Airwave Addict"),
        Tier("contacts_100k", 100_000, "Spectrum Stalker"),
        Tier("contacts_500k", 500_000, "Ghost in the Air"),
        Tier("contacts_1m", 1_000_000, "One in a Million"),
        Tier("contacts_5m", 5_000_000, "Ether Lord"),
        Tier("contacts_10m", 10_000_000, "Omniscient")
    )
    private var feedSeeded = false
    private var lastRunMark = 0L
    private val earned = HashSet<String>()
    private var runRecord = -1L
    private var firedBest = false
    private var densityMark = 0
    private val feedQueue = ArrayDeque<FeedEvent>()
    private var feedAdvancing = false

    private fun runMilestone(after: Long): Long = when {
        after < 50 -> 50
        after < 100 -> 100
        after < 250 -> 250
        after < 500 -> 500
        else -> (after / 500 + 1) * 500
    }

    private fun detectFeed(s: Stats, newWifi: Long) {

        val run = newWifi
        val perRun = s.wifiThisRun
        val life = s.wifiLifetime
        val best = s.wifiBestRun
        if (!feedSeeded) {
            feedSeeded = true
            lastRunMark = when {
                run < 50 -> 0; run < 100 -> 50; run < 250 -> 100
                run < 500 -> 250; else -> (run / 500) * 500
            }
            ladder.forEach { if (life >= it.threshold) earned.add(it.id) }
            if (perRun >= 50) earned.add("pack_hunter")
            runRecord = best
            return
        }

        if (run < lastRunMark) {
            lastRunMark = when {
                run < 50 -> 0; run < 100 -> 50; run < 250 -> 100
                run < 500 -> 250; else -> (run / 500) * 500
            }
            firedBest = false
        }
        var next = runMilestone(lastRunMark)
        while (next <= run) {
            lastRunMark = next
            post(FeedEvent(FeedKind.MILESTONE, "THIS RUN", "${Leaderboard.fmt(next)} Wi-Fi", FeedTone.ACCENT))
            next = runMilestone(lastRunMark)
        }
        val newly = ladder.filter { life >= it.threshold && !earned.contains(it.id) }
        newly.forEach { earned.add(it.id) }
        if (newly.size in 1..2) newly.forEach { post(FeedEvent(FeedKind.UNLOCK, "UNLOCKED", it.title, FeedTone.GOLD)) }
        if (perRun >= 50 && !earned.contains("pack_hunter")) {
            earned.add("pack_hunter"); post(FeedEvent(FeedKind.UNLOCK, "UNLOCKED", "Pack Hunter", FeedTone.GOLD))
        }
        if (runRecord < 0) runRecord = best
        if (perRun > runRecord && runRecord > 0 && !firedBest) {
            firedBest = true
            post(FeedEvent(FeedKind.BEST, "NEW BEST", "${Leaderboard.fmt(perRun)} Wi-Fi", FeedTone.HOT))
        }

        val tier = when { s.nearby >= 200 -> 200; s.nearby >= 100 -> 100; else -> 0 }
        if (tier > densityMark) {
            densityMark = tier
            post(FeedEvent(FeedKind.BEST, "DENSITY!", "$tier+ nearby", FeedTone.HOT))
        } else if (s.nearby < 90) densityMark = 0
    }

    private fun post(e: FeedEvent) {
        feedQueue.addLast(e)
        if (!feedAdvancing) {
            feedAdvancing = true
            viewModelScope.launch {
                while (feedQueue.isNotEmpty()) { feedEvent.value = feedQueue.removeFirst(); delay(3600) }
                feedEvent.value = null; feedAdvancing = false
            }
        }
    }

    fun startScanning(hasLocation: Boolean) {
        if (scanning) return
        scanning = true
        scanningState.value = true
        repo.wardriveMode = wardrive.value
        repo.wifiPriority = wifiPriority.value

        try {
            repo.start(hasLocation)
        } catch (t: Throwable) {
            banner.value = "Scan error: ${t.message}"
        }

        if (backgroundScan.value) startScanService()
        viewModelScope.launch {
            while (scanning) {
                try { repo.poll() } catch (_: Throwable) {}
                delay(1500)
            }
        }
    }

    private fun startScanService() {
        val ctx = getApplication<Application>()
        val intent = android.content.Intent(ctx, ScanService::class.java)
        try {
            ctx.startService(intent)
        } catch (_: Throwable) {

            runCatching { ctx.startForegroundService(intent) }
        }
    }

    fun stopScanning() {
        scanning = false; scanningState.value = false; repo.stop()
        runCatching {
            val ctx = getApplication<Application>()
            ctx.stopService(android.content.Intent(ctx, ScanService::class.java))
        }
    }

    fun setWardrive(on: Boolean) { wardrive.value = on; repo.wardriveMode = on }

    suspend fun exportAll() = repo.exportAll(RELEASE)
    suspend fun exportRun() = repo.exportRun(RELEASE)

    suspend fun exportToStream(out: java.io.OutputStream, onlyNew: Boolean) = repo.exportToStream(out, RELEASE, onlyNew)
    suspend fun prepareExportNew() = repo.prepareExportNew(RELEASE)
    suspend fun commitExport(p: PendingExport) = repo.commitExport(p)
    suspend fun prepareWigleSend() = repo.prepareWigleSend(RELEASE)
    suspend fun commitWigleSent(p: PendingExport) = repo.commitWigleSent(p)
    suspend fun prepareWdgwSend() = repo.prepareWdgwSend(RELEASE)
    suspend fun commitWdgwSent(p: PendingExport) = repo.commitWdgwSent(p)
    suspend fun syncWigleMine(name: String, token: String, onProgress: (Int) -> Unit) =
        repo.syncWigleMine(name, token, onProgress)
    fun cancelWigleSync() = repo.cancelWigleSync()

    val newForWigle = MutableStateFlow(0L)
    val newForWdgw = MutableStateFlow(0L)

    val newUnsentWifi = MutableStateFlow(0L)
    val newUnsentBle = MutableStateFlow(0L)
    val newUnsentCell = MutableStateFlow(0L)

    val wifiBestRun: StateFlow<Long> = repo.dao().bestRunForType("WIFI")
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val wigleImporting = MutableStateFlow(false)
    val wigleImportCount = MutableStateFlow(0)

    val wiglePhase = MutableStateFlow(SendPhase.IDLE)
    val wdgwPhase = MutableStateFlow(SendPhase.IDLE)

    private var wigleJob: kotlinx.coroutines.Job? = null
    private var wdgwJob: kotlinx.coroutines.Job? = null

    val uploadTargets = MutableStateFlow("")

    val uploadLine: StateFlow<String?> =
        combine(wiglePhase, wdgwPhase, uploadTargets) { w, d, tgt ->
            if (tgt.isBlank()) return@combine null
            val ps = buildList {
                if (tgt.contains("WiGLE")) add(w)
                if (tgt.contains("WDGW")) add(d)
            }
            when {
                ps.isEmpty() -> null
                ps.all { it == SendPhase.IDLE } -> null
                ps.all { it == SendPhase.SENT } -> "Sent → $tgt ✓"
                ps.any { it == SendPhase.WRITING } -> "Preparing → $tgt"
                ps.any { it == SendPhase.UPLOADING } -> "Sending → $tgt"
                ps.any { it == SendPhase.SAVING } -> "Saving → $tgt"
                else -> "Sending → $tgt"
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val backgroundScan = MutableStateFlow(true)
    fun setBackgroundScan(on: Boolean) {
        backgroundScan.value = on
        if (!on) runCatching {
            val ctx = getApplication<Application>()
            ctx.stopService(android.content.Intent(ctx, ScanService::class.java))
        } else if (scanningState.value) startScanService()
    }

    val wifiPriority = MutableStateFlow(true)
    fun setWifiPriority(on: Boolean) {
        wifiPriority.value = on
        repo.wifiPriority = on

        if (scanningState.value) { stopScanning(); startScanning(true) }
    }

    val maxWifi = MutableStateFlow(false)
    fun setMaxWifi(on: Boolean) {
        maxWifi.value = on
        repo.applyMaxWifi(on)
    }

    val captureClients = MutableStateFlow(true)
    fun setCaptureClients(on: Boolean) {
        captureClients.value = on
        com.rocketgod.warble.usb.Mt7612uMonitor.captureClients = on
        com.rocketgod.warble.usb.MonitorCapture.captureClients = on

    }

    val gnssFullTracking = MutableStateFlow(false)
    fun setGnssFullTracking(on: Boolean) {
        gnssFullTracking.value = on
        repo.applyGnssFullTracking(on)
    }

    val keepScreenOn = MutableStateFlow(true)
    fun setKeepScreenOn(on: Boolean) { keepScreenOn.value = on }

    private var sendsInFlight = 0
    private var resumeScanAfterSends = false
    @Synchronized private fun beginSendPauseScan() {
        if (sendsInFlight == 0) {
            resumeScanAfterSends = scanningState.value
            if (resumeScanAfterSends) stopScanning()
        }
        sendsInFlight++
    }
    @Synchronized private fun endSendResumeScan() {
        sendsInFlight--
        if (sendsInFlight <= 0) {
            sendsInFlight = 0
            if (resumeScanAfterSends) { startScanning(true); resumeScanAfterSends = false }
        }
    }

    private fun warblePrefs() =
        getApplication<Application>().getSharedPreferences("warble", android.content.Context.MODE_PRIVATE)

    private fun nextExportName(service: String): String {
        val prefs = warblePrefs()
        val handle = (prefs.getString("wdgw_username", null) ?: "")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .ifBlank { "user" }
        val seqKey = "export_seq_$service"
        val n = prefs.getInt(seqKey, 0) + 1
        prefs.edit().putInt(seqKey, n).apply()
        return "WardriveGo-$handle-$n.csv"
    }

    private suspend fun ensureWdgwUsername(key: String) {
        val prefs = warblePrefs()
        if (!prefs.getString("wdgw_username", null).isNullOrBlank()) return
        val nm = withContext(Dispatchers.IO) {
            runCatching { com.rocketgod.warble.net.WdgwStats.me(key)?.name }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: return
        prefs.edit().putString("wdgw_username", nm).apply()
    }

    fun cacheWdgwUsername(name: String?) {
        val nm = name?.takeIf { it.isNotBlank() } ?: return
        warblePrefs().edit().putString("wdgw_username", nm).apply()
    }

    fun sendToWigle(name: String, token: String, onResult: (ok: Boolean, message: String) -> Unit) {

        if (wiglePhase.value != SendPhase.IDLE && wigleJob?.isActive == true) {
            onResult(false, "Already uploading to WiGLE…"); return
        }
        wiglePhase.value = SendPhase.WRITING
        wigleJob = viewModelScope.launch {
            var paused = false
            try {
                beginSendPauseScan(); paused = true
                wiglePhase.value = SendPhase.UPLOADING

                val r = repo.sendToWigleStreamed(
                    RELEASE, nextExportName("WiGLE"),
                    upload = { file, fn ->
                        val res = withContext(Dispatchers.IO) {
                            com.rocketgod.warble.net.WigleApi.uploadFile(name, token, file, fn, donate = false)
                        }
                        res.ok to res.message
                    }
                )
                when {
                    r.nothingNew -> onResult(true, r.failMsg ?: "Nothing new — all networks are already in WiGLE")
                    r.ok -> {
                        val other = r.sentTotal - r.sentWifi
                        val extra = if (other > 0) " (+$other other)" else ""
                        onResult(true, "✓ Sent ${r.sentWifi} Wi-Fi$extra to WiGLE — count reset")
                        wiglePhase.value = SendPhase.SENT
                        launch { delay(4000); if (wiglePhase.value == SendPhase.SENT) wiglePhase.value = SendPhase.IDLE }
                    }
                    else -> onResult(false, "WiGLE upload stopped after ${r.sentTotal} sent — ${r.remaining} still queued (already-sent are saved; retry to finish): ${r.failMsg}")
                }
            } catch (e: Exception) {
                com.rocketgod.warble.usb.BeastDiag.log("WiGLE send: ERROR — ${e.message}")
                onResult(false, "WiGLE upload error: ${e.message}")
            } finally {

                if (wiglePhase.value != SendPhase.SENT) wiglePhase.value = SendPhase.IDLE
                if (paused) endSendResumeScan()
            }
        }
    }

    fun sendToWdgw(key: String, onResult: (ok: Boolean, message: String) -> Unit) {
        if (wdgwPhase.value != SendPhase.IDLE && wdgwJob?.isActive == true) {
            onResult(false, "Already uploading to WDGWars…"); return
        }
        wdgwPhase.value = SendPhase.WRITING
        wdgwJob = viewModelScope.launch {
            var paused = false
            try {
                beginSendPauseScan(); paused = true
                ensureWdgwUsername(key)
                wdgwPhase.value = SendPhase.UPLOADING
                val r = repo.sendToWdgwStreamed(
                    RELEASE, nextExportName("WDGWars"),
                    upload = { file, fn ->
                        val res = withContext(Dispatchers.IO) { com.rocketgod.warble.net.WdgwApi.uploadFile(key, file, fn) }
                        res.ok to res.message
                    }
                )
                when {
                    r.nothingNew -> onResult(true, r.failMsg ?: "Nothing new — all networks are already in WDGWars")
                    r.ok -> {
                        val other = r.sentTotal - r.sentWifi
                        val extra = if (other > 0) " (+$other other)" else ""
                        onResult(true, "✓ Sent ${r.sentWifi} Wi-Fi$extra to WDGWars — count reset")
                        wdgwPhase.value = SendPhase.SENT
                        launch { delay(4000); if (wdgwPhase.value == SendPhase.SENT) wdgwPhase.value = SendPhase.IDLE }
                    }
                    else -> onResult(false, "WDGWars upload stopped after ${r.sentTotal} sent — ${r.remaining} still queued (already-sent are saved; retry to finish): ${r.failMsg}")
                }
            } catch (e: Exception) {
                com.rocketgod.warble.usb.BeastDiag.log("WDGWars send: ERROR — ${e.message}")
                onResult(false, "WDGWars upload error: ${e.message}")
            } finally {
                if (wdgwPhase.value != SendPhase.SENT) wdgwPhase.value = SendPhase.IDLE
                if (paused) endSendResumeScan()
            }
        }
    }

    val exportSessions: StateFlow<List<ExportSessionEntity>> =
        repo.exportSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    suspend fun importWigle(input: InputStream) = repo.importWigle(input)
    suspend fun finalizeRun() = repo.finalizeRun()

    val heavyDataActive = MutableStateFlow(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val makerBreakdown = heavyDataActive
        .flatMapLatest { on -> if (on) repo.dao().makerBreakdown() else kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val typeCatCounts = heavyDataActive
        .flatMapLatest { on -> if (on) repo.dao().typeCatCounts() else kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val monitorCatCounts = heavyDataActive
        .flatMapLatest { on -> if (on) repo.dao().monitorCatCounts() else kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun obsByCat(type: String, cat: String, limit: Int = 500): List<com.rocketgod.warble.data.ObservationEntity> =
        withContext(Dispatchers.IO) { repo.dao().obsByCat(type, cat, limit) }
    suspend fun obsMonitorByCat(cat: String, limit: Int = 500): List<com.rocketgod.warble.data.ObservationEntity> =
        withContext(Dispatchers.IO) { repo.dao().obsMonitorByCat(cat, limit) }
    suspend fun obsByMakers(brands: List<String>, limit: Int = 500): List<com.rocketgod.warble.data.ObservationEntity> =
        withContext(Dispatchers.IO) { repo.dao().obsByMakers(brands, limit) }
    suspend fun obsByMaker(maker: String, limit: Int = 2000): List<com.rocketgod.warble.data.ObservationEntity> =
        withContext(Dispatchers.IO) { if (maker == "Unknown") repo.dao().obsUnknownMaker(limit) else repo.dao().obsByMaker(maker, limit) }
    suspend fun obsByKey(key: String): com.rocketgod.warble.data.ObservationEntity? =
        withContext(Dispatchers.IO) { repo.dao().find(key) }
    suspend fun mapPointsByType(type: String): List<com.rocketgod.warble.ui.MapPoint> =
        withContext(Dispatchers.IO) { repo.dao().mapPointsByType(type, 60000).map { labeledPoint(it) } }
    suspend fun mapPointsByMakers(brands: List<String>): List<com.rocketgod.warble.ui.MapPoint> =
        withContext(Dispatchers.IO) { repo.dao().mapPointsByMakers(brands, 60000).map { labeledPoint(it) } }

    private fun labeledPoint(r: com.rocketgod.warble.data.LabeledPtRow): com.rocketgod.warble.ui.MapPoint {
        val label = com.rocketgod.warble.classify.NotableDevices.displayName(r.name, r.key, r.companyId, r.category, r.maker)
            ?: r.name ?: r.category
        return com.rocketgod.warble.ui.MapPoint(r.lat, r.lng, label, r.key)
    }

    companion object {

        val RELEASE = "WardriveGo-android-${BuildConfig.VERSION_NAME}"
        val EMPTY_STATS = Stats(0, 0, 0, 0, 0, 0, 0, emptyList())
    }
}
