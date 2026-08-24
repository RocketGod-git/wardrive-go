package com.rocketgod.warble.core

import android.content.Context
import com.rocketgod.warble.classify.BleClassifier
import com.rocketgod.warble.data.CellTowerEntity
import com.rocketgod.warble.data.ExportSessionEntity
import com.rocketgod.warble.data.GnssSatEntity
import com.rocketgod.warble.data.BlockedDevice
import com.rocketgod.warble.data.ObservationEntity
import com.rocketgod.warble.data.PrivacyZone
import com.rocketgod.warble.data.RunEntity
import com.rocketgod.warble.data.WarbleDb
import com.rocketgod.warble.model.Contact
import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.scan.BleScanSource
import com.rocketgod.warble.scan.CellScanSource
import com.rocketgod.warble.scan.LocationProvider
import com.rocketgod.warble.scan.RawObservation
import com.rocketgod.warble.scan.ScanSource
import com.rocketgod.warble.scan.WifiScanSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class PendingExport(val csv: String, val keys: List<String>, val total: Int, val ble: Int, val wifi: Int, val cell: Int)

data class SatInfo(
    val svid: Int,
    val constellation: String,
    val cn0: Float,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val usedInFix: Boolean,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
    val carrierFreqHz: Float,

    val health: Int? = null,
    val uraIndex: Int? = null,
    val svConfig: Int? = null,
    val antiSpoof: Boolean? = null
)

data class GnssStat(
    val usedInFix: Int,
    val total: Int,
    val constellations: List<Pair<String, Int>>,
    val sats: List<SatInfo> = emptyList(),
    val leapSeconds: Int? = null
)

data class CellTowerInfo(
    val id: String,
    val tech: String,
    val operator: String,
    val mcc: String,
    val mnc: String,
    val cid: Long,
    val pci: Int?,
    val tac: Int?,
    val arfcn: Int?,
    val band: String?,
    val dbm: Int,
    val asuLevel: Int,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val timingAdvance: Int?,
    val registered: Boolean
)

class Repository(private val context: Context, private val scope: CoroutineScope) {

    private val db = WarbleDb.get(context)
    private val dao = db.dao()
    private val classifier by lazy { BleClassifier.get(context) }
    private val oui by lazy { com.rocketgod.warble.classify.OuiLookup.get(context) }
    val location = LocationProvider(context)

    private val live = ConcurrentHashMap<String, Contact>()
    private val _liveFlow = MutableStateFlow<List<Contact>>(emptyList())
    val liveFlow: StateFlow<List<Contact>> = _liveFlow.asStateFlow()

    private val _gnss = MutableStateFlow(GnssStat(0, 0, emptyList()))
    val gnssFlow: StateFlow<GnssStat> = _gnss.asStateFlow()
    private var gnssCallback: android.location.GnssStatus.Callback? = null

    private val _cells = MutableStateFlow<List<CellTowerInfo>>(emptyList())
    val cellFlow: StateFlow<List<CellTowerInfo>> = _cells.asStateFlow()
    private val telephony by lazy { context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager }
    @Volatile private var lastCellLog = 0L

    private fun constName(type: Int): String = when (type) {
        android.location.GnssStatus.CONSTELLATION_GPS -> "GPS"
        android.location.GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        android.location.GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        android.location.GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        android.location.GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        android.location.GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        android.location.GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        else -> "Other"
    }

    private var navCallback: android.location.GnssNavigationMessage.Callback? = null
    @Volatile private var lastSatLog = 0L

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGnss() {
        if (gnssCallback != null) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return
        val cb = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val counts = HashMap<Int, Int>(); var used = 0
                val sats = ArrayList<SatInfo>(status.satelliteCount)
                val nav = GpsLnav.snapshot()
                for (i in 0 until status.satelliteCount) {
                    val c = status.getConstellationType(i)
                    counts[c] = (counts[c] ?: 0) + 1
                    if (status.usedInFix(i)) used++
                    val svid = status.getSvid(i)
                    val isGps = c == android.location.GnssStatus.CONSTELLATION_GPS
                    val nf = if (isGps) nav[svid] else null
                    sats.add(
                        SatInfo(
                            svid = svid,
                            constellation = constName(c),
                            cn0 = status.getCn0DbHz(i),
                            elevationDeg = status.getElevationDegrees(i),
                            azimuthDeg = status.getAzimuthDegrees(i),
                            usedInFix = status.usedInFix(i),
                            hasAlmanac = status.hasAlmanacData(i),
                            hasEphemeris = status.hasEphemerisData(i),
                            carrierFreqHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else 0f,
                            health = nf?.health, uraIndex = nf?.uraIndex,
                            svConfig = nf?.svConfig, antiSpoof = nf?.antiSpoof
                        )
                    )
                }
                sats.sortWith(compareBy({ it.constellation }, { it.svid }))
                _gnss.value = GnssStat(
                    used, status.satelliteCount,
                    counts.entries.map { constName(it.key) to it.value }.sortedByDescending { it.second },
                    sats, GpsLnav.leapSeconds
                )

                val now = System.currentTimeMillis()
                if (now - lastSatLog > 8_000) {
                    lastSatLog = now
                    scope.launch(Dispatchers.IO) { runCatching { logSats(sats, now) } }
                }
            }
        }
        runCatching { lm.registerGnssStatusCallback(cb, android.os.Handler(android.os.Looper.getMainLooper())) }
            .onSuccess { gnssCallback = cb }

        if (gnssFullTracking) {
            val ncb = object : android.location.GnssNavigationMessage.Callback() {
                override fun onGnssNavigationMessageReceived(msg: android.location.GnssNavigationMessage) {
                    if (msg.type == android.location.GnssNavigationMessage.TYPE_GPS_L1CA) {
                        runCatching { GpsLnav.ingestL1ca(msg.svid, msg.data) }
                    }
                }
            }
            runCatching { lm.registerGnssNavigationMessageCallback(ncb, android.os.Handler(android.os.Looper.getMainLooper())) }
                .onSuccess { navCallback = ncb }
        }
    }

    @Volatile var gnssFullTracking: Boolean = false
    fun applyGnssFullTracking(on: Boolean) {
        if (gnssFullTracking == on) return
        gnssFullTracking = on
        if (gnssCallback != null) { stopGnss(); startGnss() }
    }

    private suspend fun logSats(sats: List<SatInfo>, now: Long) {
        for (s in sats) {
            val id = "${s.constellation}-${s.svid}"
            val prev = dao.gnssSat(id)
            dao.upsertGnssSat(
                GnssSatEntity(
                    id = id, constellation = s.constellation, svid = s.svid,
                    bestCn0 = maxOf(prev?.bestCn0 ?: 0f, s.cn0), lastCn0 = s.cn0,
                    elevation = s.elevationDeg, azimuth = s.azimuthDeg,
                    usedInFix = s.usedInFix || (prev?.usedInFix ?: false),
                    hasAlmanac = s.hasAlmanac || (prev?.hasAlmanac ?: false),
                    hasEphemeris = s.hasEphemeris || (prev?.hasEphemeris ?: false),
                    carrierHz = if (s.carrierFreqHz > 0f) s.carrierFreqHz else (prev?.carrierHz ?: 0f),
                    health = s.health ?: prev?.health, uraIndex = s.uraIndex ?: prev?.uraIndex,
                    svConfig = s.svConfig ?: prev?.svConfig, antiSpoof = s.antiSpoof ?: prev?.antiSpoof,
                    firstSeen = prev?.firstSeen ?: now, lastSeen = now,
                    timesSeen = (prev?.timesSeen ?: 0) + 1
                )
            )
        }
    }

    fun gnssSats() = dao.gnssSats()
    suspend fun gnssSat(id: String) = dao.gnssSat(id)

    @android.annotation.SuppressLint("MissingPermission")
    fun readCells() {
        val tm = telephony ?: return
        val cells = try { @Suppress("DEPRECATION") tm.allCellInfo ?: emptyList() } catch (e: Exception) { return }
        val carrier = try { tm.networkOperatorName ?: "" } catch (e: Exception) { "" }
        val servingOp = try { tm.networkOperator ?: "" } catch (e: Exception) { "" }
        val out = ArrayList<CellTowerInfo>()
        for (c in cells) runCatching { cellToInfo(c, carrier, servingOp) }.getOrNull()?.let { out.add(it) }

        val byId = LinkedHashMap<String, CellTowerInfo>()
        for (t in out) {
            val prev = byId[t.id]
            if (prev == null || (t.registered && !prev.registered) || t.dbm > prev.dbm) byId[t.id] = t
        }
        var list = byId.values.sortedWith(compareByDescending<CellTowerInfo> { it.registered }.thenByDescending { it.dbm })

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val strengths = try { tm.signalStrength?.cellSignalStrengths } catch (e: Exception) { null }
            val i = list.indexOfFirst { it.registered }
            if (i >= 0 && !strengths.isNullOrEmpty()) {
                list = list.toMutableList().also { it[i] = enrichServing(it[i], strengths) }
            }
        }
        _cells.value = list
        val now = System.currentTimeMillis()
        if (now - lastCellLog > 8_000) { lastCellLog = now; scope.launch(Dispatchers.IO) { runCatching { logCells(list, now) } } }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun enrichServing(t: CellTowerInfo, strengths: List<android.telephony.CellSignalStrength>): CellTowerInfo {
        val match = strengths.firstOrNull { s ->
            when (t.tech) {
                "LTE" -> s is android.telephony.CellSignalStrengthLte
                "5G NR" -> s is android.telephony.CellSignalStrengthNr
                "GSM" -> s is android.telephony.CellSignalStrengthGsm
                "WCDMA" -> s is android.telephony.CellSignalStrengthWcdma
                else -> false
            }
        } ?: return t
        return when (match) {
            is android.telephony.CellSignalStrengthLte -> t.copy(
                dbm = match.dbm, asuLevel = match.level,
                rsrp = match.rsrp.cn() ?: t.rsrp, rsrq = match.rsrq.cn() ?: t.rsrq,
                sinr = match.rssnr.cn() ?: t.sinr, timingAdvance = match.timingAdvance.cn() ?: t.timingAdvance
            )
            is android.telephony.CellSignalStrengthNr -> t.copy(
                dbm = match.dbm, asuLevel = match.level,
                rsrp = match.ssRsrp.cn() ?: t.rsrp, rsrq = match.ssRsrq.cn() ?: t.rsrq, sinr = match.ssSinr.cn() ?: t.sinr
            )
            else -> t.copy(dbm = match.dbm, asuLevel = match.level)
        }
    }

    private fun Int.cn(): Int? = if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this
    private fun opName(reg: Boolean, carrier: String, servingOp: String, mcc: String, mnc: String): String {

        if (carrier.isNotBlank() && (reg || servingOp == "$mcc$mnc")) return carrier
        val id = listOf(mcc, mnc).filter { it.isNotBlank() }.joinToString("/")
        return id.ifBlank { if (carrier.isNotBlank()) carrier else "Unknown carrier" }
    }
    private fun bandsStr(bands: IntArray?, prefix: String): String? =
        if (bands == null || bands.isEmpty()) null else bands.joinToString(", ") { "$prefix$it" }

    @android.annotation.SuppressLint("NewApi")
    private fun cellToInfo(c: android.telephony.CellInfo, carrier: String, servingOp: String): CellTowerInfo? {
        val reg = c.isRegistered
        return when (c) {
            is android.telephony.CellInfoLte -> {
                val id = c.cellIdentity; val ss = c.cellSignalStrength
                val mcc = id.mccString ?: ""; val mnc = id.mncString ?: ""
                val ci = id.ci.cn(); val pci = id.pci.cn()
                if (ci == null && pci == null) return null
                CellTowerInfo(
                    id = "LTE-$mcc-$mnc-${ci ?: "p$pci"}", tech = "LTE",
                    operator = opName(reg, carrier, servingOp, mcc, mnc), mcc = mcc, mnc = mnc,
                    cid = (ci ?: -1).toLong(), pci = pci, tac = id.tac.cn(), arfcn = id.earfcn.cn(),
                    band = if (android.os.Build.VERSION.SDK_INT >= 30) bandsStr(id.bands, "B") else null,
                    dbm = ss.dbm, asuLevel = ss.level, rsrp = ss.rsrp.cn(), rsrq = ss.rsrq.cn(),
                    sinr = ss.rssnr.cn(), timingAdvance = ss.timingAdvance.cn(), registered = reg
                )
            }
            is android.telephony.CellInfoGsm -> {
                val id = c.cellIdentity; val ss = c.cellSignalStrength
                val mcc = id.mccString ?: ""; val mnc = id.mncString ?: ""
                val cid = id.cid.cn(); if (cid == null) return null
                CellTowerInfo(
                    id = "GSM-$mcc-$mnc-$cid", tech = "GSM",
                    operator = opName(reg, carrier, servingOp, mcc, mnc), mcc = mcc, mnc = mnc,
                    cid = cid.toLong(), pci = id.bsic.cn(), tac = id.lac.cn(), arfcn = id.arfcn.cn(),
                    band = null, dbm = ss.dbm, asuLevel = ss.level, rsrp = null, rsrq = null,
                    sinr = null, timingAdvance = null, registered = reg
                )
            }
            is android.telephony.CellInfoWcdma -> {
                val id = c.cellIdentity; val ss = c.cellSignalStrength
                val mcc = id.mccString ?: ""; val mnc = id.mncString ?: ""
                val cid = id.cid.cn(); if (cid == null) return null
                CellTowerInfo(
                    id = "WCDMA-$mcc-$mnc-$cid", tech = "WCDMA",
                    operator = opName(reg, carrier, servingOp, mcc, mnc), mcc = mcc, mnc = mnc,
                    cid = cid.toLong(), pci = id.psc.cn(), tac = id.lac.cn(), arfcn = id.uarfcn.cn(),
                    band = null, dbm = ss.dbm, asuLevel = ss.level, rsrp = null, rsrq = null,
                    sinr = null, timingAdvance = null, registered = reg
                )
            }
            else -> nrInfo(c, reg, carrier, servingOp)
        }
    }

    private fun nrInfo(c: android.telephony.CellInfo, reg: Boolean, carrier: String, servingOp: String): CellTowerInfo? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        if (c !is android.telephony.CellInfoNr) return null
        val id = c.cellIdentity as? android.telephony.CellIdentityNr ?: return null
        val ss = c.cellSignalStrength as? android.telephony.CellSignalStrengthNr
        val mcc = id.mccString ?: ""; val mnc = id.mncString ?: ""
        return CellTowerInfo(
            id = "NR-$mcc-$mnc-${id.nci}", tech = "5G NR",
            operator = opName(reg, carrier, servingOp, mcc, mnc), mcc = mcc, mnc = mnc,
            cid = id.nci, pci = id.pci.cn(), tac = id.tac.cn(), arfcn = id.nrarfcn.cn(),
            band = if (android.os.Build.VERSION.SDK_INT >= 30) bandsStr(id.bands, "n") else null,
            dbm = ss?.dbm ?: -140, asuLevel = ss?.level ?: 0,
            rsrp = ss?.ssRsrp?.cn(), rsrq = ss?.ssRsrq?.cn(), sinr = ss?.ssSinr?.cn(),
            timingAdvance = null, registered = reg
        )
    }

    private suspend fun logCells(list: List<CellTowerInfo>, now: Long) {
        for (t in list) {
            val prev = dao.cellTower(t.id)
            dao.upsertCellTower(
                CellTowerEntity(
                    id = t.id, tech = t.tech, operator = t.operator, mcc = t.mcc, mnc = t.mnc, cid = t.cid,
                    pci = t.pci, tac = t.tac, arfcn = t.arfcn, band = t.band,
                    bestDbm = maxOf(prev?.bestDbm ?: -140, t.dbm), lastDbm = t.dbm,
                    rsrp = t.rsrp ?: prev?.rsrp, rsrq = t.rsrq ?: prev?.rsrq, sinr = t.sinr ?: prev?.sinr,
                    timingAdvance = t.timingAdvance ?: prev?.timingAdvance,
                    registeredEver = t.registered || (prev?.registeredEver ?: false),
                    firstSeen = prev?.firstSeen ?: now, lastSeen = now, timesSeen = (prev?.timesSeen ?: 0) + 1
                )
            )
        }
    }

    fun cellTowers() = dao.cellTowers()
    suspend fun cellTower(id: String) = dao.cellTower(id)

    fun pmkids() = dao.pmkids()
    suspend fun pmkid(id: String) = dao.pmkid(id)

    fun savePmkid(hit: com.rocketgod.warble.usb.PmkidCapture.Hit) {
        val loc = location.last
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.insertPmkid(
                    com.rocketgod.warble.data.PmkidEntity(
                        pmkid = hit.pmkid, bssid = hit.bssid, sta = hit.sta, ssid = hit.ssid,
                        channel = hit.channel, rssi = hit.rssi,
                        lat = loc?.latitude, lng = loc?.longitude, firstSeen = hit.time,
                        kind = hit.kind, hashline = hit.hashline
                    )
                )
            }
        }
    }

    private fun stopGnss() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        gnssCallback?.let { runCatching { lm?.unregisterGnssStatusCallback(it) } }
        navCallback?.let { runCatching { lm?.unregisterGnssNavigationMessageCallback(it) } }
        gnssCallback = null; navCallback = null
    }

    @Volatile var wardriveMode: Boolean = false

    @Volatile var wifiPriority: Boolean = true

    @Volatile var maxWifi: Boolean = false

    private val _runId = MutableStateFlow(0L)
    val runIdFlow: StateFlow<Long> = _runId.asStateFlow()
    private var runId: Long
        get() = _runId.value
        set(v) { _runId.value = v }

    private var sources: List<ScanSource> = emptyList()

    @Volatile private var runStartedAt: Long = 0L
    suspend fun captureCountThisRun(): Long = withContext(Dispatchers.IO) { dao.captureCountSince(runStartedAt) }

    suspend fun ensureRun() {
        val open = dao.openRun()
        if (open != null) { runId = open.id; runStartedAt = open.startedAt }
        else { val now = System.currentTimeMillis(); runId = dao.insertRun(RunEntity(startedAt = now, endedAt = null, contactCount = 0)); runStartedAt = now }

    }

    fun start(location: Boolean) {
        if (location) { this.location.start(); startGnss() }

        val list = mutableListOf<ScanSource>(WifiScanSource(context) { distances -> applyDistances(distances) })
        if (!maxWifi) {
            list.add(0, BleScanSource(context) { wifiPriority })
            if (context.packageManager.hasSystemFeature("android.hardware.telephony"))
                list.add(CellScanSource(context))
        }
        sources = list
        sources.forEach { s -> s.start { batch -> ingest(batch) } }

        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.IO) {
            while (true) { delay(WRITE_FLUSH_MS); runCatching { flushPending() } }
        }
    }

    fun applyMaxWifi(on: Boolean) {
        maxWifi = on
        if (sources.isEmpty()) return
        if (on) {
            sources.filter { it is BleScanSource || it is CellScanSource }.forEach { it.stop() }
            sources = sources.filter { it is WifiScanSource }
        } else {
            val add = mutableListOf<ScanSource>()
            if (sources.none { it is BleScanSource }) add.add(BleScanSource(context) { wifiPriority })
            if (sources.none { it is CellScanSource } &&
                context.packageManager.hasSystemFeature("android.hardware.telephony"))
                add.add(CellScanSource(context))
            add.forEach { s -> s.start { batch -> ingest(batch) } }
            sources = sources + add
        }
    }

    @Volatile private var lastCellRead = 0L
    @Volatile private var cellReadInFlight = false

    fun poll() {
        sources.forEach {
            when (it) { is WifiScanSource -> it.poll(); is CellScanSource -> it.poll(); is BleScanSource -> it.poll(); else -> {} }
        }

        val now = System.currentTimeMillis()
        if (!cellReadInFlight && now - lastCellRead > 3000L) {
            cellReadInFlight = true; lastCellRead = now
            scope.launch(Dispatchers.IO) { try { readCells() } catch (_: Throwable) {} finally { cellReadInFlight = false } }
        }
        pruneAndEmit(force = true)
        if (now - lastRateLog > 3000L) {
            val w = rawWifi.getAndSet(0); val b = rawBle.getAndSet(0); val c = rawCell.getAndSet(0)
            lastRateLog = now

            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) shr 20
            val maxMb = rt.maxMemory() shr 20
            com.rocketgod.warble.usb.BeastDiag.log("SCAN RATE /3s: wifi=$w ble=$b cell=$c  (live=${live.size}, heap=${usedMb}/${maxMb}MB)")
        }
    }

    fun stop() {
        sources.forEach { it.stop() }
        location.stop()
        stopGnss()

        flushJob?.cancel(); flushJob = null
        scope.launch(Dispatchers.IO) { runCatching { flushPending() } }
    }

    fun feedMonitor(batch: List<RawObservation>) {

        if (batch.isNotEmpty()) com.rocketgod.warble.usb.UsbBeast.lastFrameMs = android.os.SystemClock.elapsedRealtime()
        ingest(batch)
    }

    private fun lane(name: String) =
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "wardrive-$name") }.asCoroutineDispatcher()
    private val ingestBle = lane("ingest-ble")
    private val ingestWifi = lane("ingest-wifi")
    private val ingestCell = lane("ingest-cell")

    private val rawWifi = java.util.concurrent.atomic.AtomicInteger()
    private val rawBle = java.util.concurrent.atomic.AtomicInteger()
    private val rawCell = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastRateLog = 0L

    private val pendingWrites = java.util.concurrent.ConcurrentHashMap<String, Contact>()

    private val writeLock = Mutex()
    @Volatile private var flushJob: Job? = null

    private fun ingest(batch: List<RawObservation>) {
        if (batch.isEmpty()) return
        val d = when (batch[0].type) {
            SignalType.BLE -> { rawBle.addAndGet(batch.size); ingestBle }
            SignalType.WIFI -> { rawWifi.addAndGet(batch.size); ingestWifi }
            SignalType.CELL -> { rawCell.addAndGet(batch.size); ingestCell }
        }
        scope.launch(d) { ingestNow(batch) }
    }

    private fun ingestNow(batch: List<RawObservation>) {
        val now = System.currentTimeMillis()
        val loc = location.last

        if (batch[0].type == SignalType.WIFI && !batch[0].viaMonitor) {
            currentPhoneWifi = batch.mapTo(HashSet(batch.size)) { it.key }
            phoneScanAt = now
        }
        val due = ArrayList<Contact>()
        for (raw in batch) {
            val inf0 = classify(raw)
            val prev = live[raw.key]

            val keepNotable = com.rocketgod.warble.classify.NotableDevices.isNotableMaker(prev?.maker) &&
                !com.rocketgod.warble.classify.NotableDevices.isNotableMaker(inf0.maker)
            val inf = if (keepNotable && prev != null) inf0.copy(name = inf0.name ?: prev.name, maker = prev.maker, category = prev.category, icon = prev.icon)
                      else inf0

            val smoothRssi = if (prev == null) {
                raw.rssi.toDouble()
            } else {
                val dt = ((now - prev.lastSeen).coerceAtLeast(0L)) / 1000.0
                val alpha = 1.0 - kotlin.math.exp(-dt / 2.0)
                prev.smoothRssi * (1 - alpha) + raw.rssi * alpha
            }
            val contact = Contact(
                key = raw.key, type = raw.type, name = inf.name, maker = inf.maker,
                category = inf.category, icon = inf.icon, rssi = raw.rssi, smoothRssi = smoothRssi,
                bestRssi = if (prev == null) raw.rssi else maxOf(prev.bestRssi, raw.rssi),
                timesSeen = (prev?.timesSeen ?: 0) + 1,
                firstSeen = prev?.firstSeen ?: now, lastSeen = now,
                companyId = raw.companyId,

                serviceUuids = raw.serviceUuids.ifEmpty { prev?.serviceUuids ?: emptyList() },
                channel = raw.channel, frequency = raw.frequency,
                capabilities = raw.capabilities, connectable = raw.connectable,
                lat = loc?.latitude ?: prev?.lat, lng = loc?.longitude ?: prev?.lng,
                altitude = loc?.altitude ?: prev?.altitude,
                accuracy = loc?.accuracy?.toDouble() ?: prev?.accuracy,

                newThisRun = prev?.newThisRun ?: true,
                inWigle = prev?.inWigle ?: false,
                viaMonitor = raw.viaMonitor,

                seenByPhone = (prev?.seenByPhone ?: false) || !raw.viaMonitor,
                seenByMonitor = (prev?.seenByMonitor ?: false) || raw.viaMonitor,

                lastSeenByMonitor = if (raw.viaMonitor) now else (prev?.lastSeenByMonitor ?: 0L),

                wifiStandard = if (raw.wifiStandard != 0) raw.wifiStandard else (prev?.wifiStandard ?: 0),
                channelWidthMhz = if (raw.channelWidthMhz != 0) raw.channelWidthMhz else (prev?.channelWidthMhz ?: 0),
                centerFreqMhz = if (raw.centerFreqMhz != 0) raw.centerFreqMhz else (prev?.centerFreqMhz ?: 0),
                distanceMm = prev?.distanceMm
            )
            live[raw.key] = contact

            if (prev == null) com.rocketgod.warble.classify.NotableDevices.match(raw.name, raw.key, raw.companyId, raw.serviceUuids, raw.appleType, raw.fmdn, raw.flockProbe)?.let { hit ->
                if (announcedNotable.add(hit.banner)) _notable.tryEmit(hit)
            }

            val backfillLoc = prev != null && prev.lat == null && loc != null
            if (prev == null || raw.rssi > prev.bestRssi || backfillLoc) due.add(contact)
        }

        for (c in due) pendingWrites[c.key] = c
        pruneAndEmit()
    }

    private companion object {
        const val WRITE_FLUSH_MS = 2500L
        const val MAP_POINT_CAP = 200_000

        const val STALE_MS = 30_000L
        const val EXT_SWEEP_MS = 3_000L

        const val PHONE_SCAN_VALID_MS = 35_000L

    }

    @Volatile private var currentPhoneWifi: Set<String> = emptySet()
    @Volatile private var phoneScanAt: Long = 0L

    suspend fun flushPending() {
        if (pendingWrites.isEmpty()) return
        val batch = ArrayList<Contact>(pendingWrites.size)
        val it = pendingWrites.entries.iterator()
        while (it.hasNext()) { batch.add(it.next().value); it.remove() }
        if (batch.isEmpty()) return
        val now = System.currentTimeMillis()

        writeLock.withLock {
            val rows = ArrayList<ObservationEntity>(batch.size)
            for (c in batch) rows.add(buildRow(c, now))
            dao.upsertBatch(rows)
        }
    }

    private suspend fun buildRow(c: Contact, now: Long): ObservationEntity {
        val existing = dao.find(c.key)

        val known = existing?.inWigle == true ||
            (existing == null && c.type == SignalType.WIFI && dao.isWigleKnown(c.key))
        val runForRow = existing?.runId ?: if (known) 0L else runId

        val stickNotable = com.rocketgod.warble.classify.NotableDevices.isNotableMaker(existing?.maker) &&
            !com.rocketgod.warble.classify.NotableDevices.isNotableMaker(c.maker)
        val finalName = c.name ?: existing?.name
        val finalMaker = if (stickNotable) existing?.maker else (c.maker ?: existing?.maker)
        val finalCategory = if (stickNotable) (existing?.category ?: c.category) else c.category
        val finalCompanyId = c.companyId ?: existing?.companyId

        val notableFlag = com.rocketgod.warble.classify.NotableDevices.isNotableMaker(finalMaker) ||
            com.rocketgod.warble.classify.NotableDevices.threat(finalName, c.key, finalCompanyId, finalCategory) != null
        return ObservationEntity(
                key = c.key, type = c.type.name, name = finalName,
                maker = finalMaker,
                category = finalCategory,
                icon = if (stickNotable) (existing?.icon ?: c.icon) else c.icon,
                bestRssi = existing?.let { maxOf(it.bestRssi, c.rssi) } ?: c.rssi,
                lastRssi = c.rssi,
                timesSeen = (existing?.timesSeen ?: 0) + 1,
                firstSeen = existing?.firstSeen ?: now, lastSeen = now,
                companyId = finalCompanyId,
                channel = c.channel ?: existing?.channel,
                frequency = c.frequency ?: existing?.frequency,
                capabilities = c.capabilities ?: existing?.capabilities,
                connectable = c.connectable,
                lat = c.lat ?: existing?.lat, lng = c.lng ?: existing?.lng,
                altitude = c.altitude ?: existing?.altitude, accuracy = c.accuracy ?: existing?.accuracy,
                runId = runForRow, inWigle = known, exportedAt = existing?.exportedAt,
                viaMonitor = c.viaMonitor || (existing?.viaMonitor ?: false),
                inWdgw = existing?.inWdgw == true,
                wifiStandard = if (c.wifiStandard != 0) c.wifiStandard else (existing?.wifiStandard ?: 0),
                notable = notableFlag
            )
    }

    private fun applyDistances(distances: Map<String, Int>) {
        if (distances.isEmpty()) return
        for ((mac, mm) in distances) live.computeIfPresent(mac) { _, c -> c.copy(distanceMm = mm) }
        pruneAndEmit(force = true)
    }

    private fun classify(raw: RawObservation): com.rocketgod.warble.classify.Inference {
        val base = when (raw.type) {
            SignalType.BLE -> classifier.infer(raw.name, raw.companyId, raw.serviceUuids)
            SignalType.WIFI -> if (raw.wifiClient)
                com.rocketgod.warble.classify.Inference(raw.name, null, "WiFi Client", "wifi")
            else
                com.rocketgod.warble.classify.Inference(raw.name, null, "WiFi AP", "wifi")
            SignalType.CELL -> com.rocketgod.warble.classify.Inference(
                raw.name, null, "Cell tower", "antenna.radiowaves.left.and.right"
            )
        }

        val resolved = when {
            raw.type == SignalType.WIFI ->
                base.copy(maker = base.maker ?: oui.vendor(raw.key))
            raw.type == SignalType.BLE && base.maker == null && base.category == "Unidentified" ->
                oui.vendor(raw.key)?.let { base.copy(maker = it, category = "Accessory", icon = "dot.radiowaves.left.and.right") } ?: base
            else -> base
        }

        val hit = com.rocketgod.warble.classify.NotableDevices.match(raw.name, raw.key, raw.companyId, raw.serviceUuids, raw.appleType, raw.fmdn, raw.flockProbe)
        if (hit == null) return resolved

        val isFlipper = hit.category == com.rocketgod.warble.classify.NotableCategory.FLIPPER
        val category = if (isFlipper)
            com.rocketgod.warble.classify.NotableDevices.flipperColor(raw.serviceUuids)?.let { "Flipper Zero ($it)" } ?: hit.readable
        else hit.readable
        val makerLabel = if (isFlipper) category else hit.brand

        return if (hit.isNetworkTracker) resolved.copy(maker = null, category = category)
        else resolved.copy(name = resolved.name ?: hit.brand, maker = makerLabel, category = category)
    }

    private val _notable = MutableSharedFlow<com.rocketgod.warble.classify.NotableHit>(extraBufferCapacity = 16)
    val notableSightings = _notable.asSharedFlow()
    private val announcedNotable = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var lastEmit = 0L

    private fun pruneAndEmit(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val cutoff = now - STALE_MS
        val phoneValid = now - phoneScanAt < PHONE_SCAN_VALID_MS

        fun phoneCurrent(c: Contact) =
            if (phoneValid) c.key in currentPhoneWifi else c.seenByPhone && c.lastSeen >= cutoff

        fun monitorCurrent(c: Contact) = c.seenByMonitor && now - c.lastSeenByMonitor < EXT_SWEEP_MS
        val it = live.entries.iterator()
        while (it.hasNext()) {
            val c = it.next().value
            val keep = if (c.type == SignalType.WIFI) phoneCurrent(c) || monitorCurrent(c)
                       else c.lastSeen >= cutoff
            if (!keep) it.remove()
        }
        if (force || now - lastEmit > 400L) {
            lastEmit = now
            val emitted = live.values.map { c ->
                if (c.type != SignalType.WIFI) c
                else c.copy(liveByPhone = phoneCurrent(c), liveByMonitor = monitorCurrent(c))
            }
            _liveFlow.value = emitted.sortedByDescending { it.strength }

            if (com.rocketgod.warble.usb.UsbBeast.campBusy) {
                com.rocketgod.warble.usb.UsbBeast.activeChannels = emitted.asSequence()
                    .filter { it.type == SignalType.WIFI && it.category != "WiFi Client" }
                    .mapNotNull { wifiChannel(it) }
                    .toSet()
            }
        }
    }

    private fun wifiChannel(c: Contact): Int? {
        c.channel?.let { if (it > 0) return it }
        val f = c.frequency ?: return null
        return when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2412) / 5 + 1
            f in 5000..5900 -> (f - 5000) / 5
            f in 5925..7125 -> (f - 5950) / 5
            else -> null
        }
    }

    fun nearbyCount(): Int = _liveFlow.value.size

    suspend fun mapPoints(): List<com.rocketgod.warble.data.MapPtRow> = withContext(Dispatchers.IO) {
        flushPending(); dao.mapPoints(MAP_POINT_CAP)
    }

    suspend fun mapPointsIn(south: Double, north: Double, west: Double, east: Double, max: Int = 60000): List<com.rocketgod.warble.data.MapPtRow> =
        withContext(Dispatchers.IO) { dao.mapPointsIn(south, north, west, east, max) }

    suspend fun classifyRowsIn(south: Double, north: Double, west: Double, east: Double, max: Int = 4000): List<com.rocketgod.warble.data.ClassifyRow> =
        withContext(Dispatchers.IO) { dao.classifyRowsIn(south, north, west, east, max) }

    suspend fun notableRowsIn(south: Double, north: Double, west: Double, east: Double, max: Int = 4000): List<com.rocketgod.warble.data.ClassifyRow> =
        withContext(Dispatchers.IO) { dao.notableRowsIn(south, north, west, east, max) }

    suspend fun pmkidsAllNow(): List<com.rocketgod.warble.data.PmkidEntity> =
        withContext(Dispatchers.IO) { dao.pmkidList() }

    suspend fun blockedKeysNow(): Set<String> =
        withContext(Dispatchers.IO) { dao.blockedDevicesNow().map { it.bssid.lowercase() }.toSet() }

    suspend fun recentLocatedLatLng(): Pair<Double, Double>? =
        withContext(Dispatchers.IO) { dao.recentLocated()?.let { it.lat to it.lng } }

    fun privacyZones() = dao.zones()
    fun blockedDevices() = dao.blockedDevices()
    fun isBlockedFlow(bssid: String) = dao.isBlockedFlow(bssid.lowercase())
    suspend fun addPrivacyZone(lat: Double, lng: Double, radiusM: Double, label: String) =
        dao.upsertZone(PrivacyZone(lat = lat, lng = lng, radiusM = radiusM, label = label, createdAt = System.currentTimeMillis()))
    suspend fun setZoneEnabled(id: Long, on: Boolean) = dao.setZoneEnabled(id, on)
    suspend fun deletePrivacyZone(id: Long) = dao.deleteZone(id)
    suspend fun blockDevice(bssid: String, label: String?) =
        dao.upsertBlocked(BlockedDevice(bssid.lowercase(), label, System.currentTimeMillis()))
    suspend fun unblockDevice(bssid: String) = dao.deleteBlocked(bssid.lowercase())

    suspend fun excludedCount(): Int = withContext(Dispatchers.IO) {
        runCatching {
            val blocked = dao.blockedDevicesNow().mapTo(HashSet()) { it.bssid.lowercase() }
            val zones = dao.zonesNow().filter { it.enabled }

            var after = ""; var total = 0
            while (true) {
                val page = dao.exclusionCandidatesPaged(after, 5000)
                if (page.isEmpty()) break
                for (c in page) {
                    if (isOptOutSsid(c.name) ||
                        c.key.lowercase() in blocked ||
                        (c.lat != null && c.lng != null && zones.any { z -> distM(c.lat, c.lng, z.lat, z.lng) <= z.radiusM })) total++
                }
                if (page.size < 5000) break
                after = page.last().key
            }
            total
        }.getOrDefault(0)
    }

    private fun isOptOutSsid(ssid: String?): Boolean {
        val s = ssid?.lowercase() ?: return false
        return s.endsWith("_nomap") || s.endsWith("_optout")
    }

    private fun isRandomizedWifi(type: String, key: String): Boolean {
        if (type != "WIFI") return false
        val firstOctet = key.substringBefore(':', "").toIntOrNull(16) ?: return false
        return (firstOctet and 0x02) != 0
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun nothingNewReason(anyLoc: Int): String? =
        if (anyLoc > 0) "$anyLoc new network${plural(anyLoc)} have no GPS fix yet — get a location fix (go outside / enable GPS), then upload"
        else null

    private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    private suspend fun filterUploadable(rows: List<ObservationEntity>): List<ObservationEntity> {
        val blocked = dao.blockedDevicesNow().mapTo(HashSet()) { it.bssid.lowercase() }
        val zones = dao.zonesNow().filter { it.enabled }
        return rows.filterNot { o ->
            isRandomizedWifi(o.type, o.key) ||
                (o.type == "WIFI" && o.category == "WiFi Client") ||
                isOptOutSsid(o.name) ||
                o.key.lowercase() in blocked ||
                (o.lat != null && o.lng != null && zones.any { z -> distM(o.lat, o.lng, z.lat, z.lng) <= z.radiusM })
        }
    }

    suspend fun exportAll(appRelease: String): String =
        withContext(Dispatchers.IO) { flushPending(); WigleCsv.export(filterUploadable(dao.allObservations()), appRelease) }

    suspend fun exportRun(appRelease: String): String =
        withContext(Dispatchers.IO) { flushPending(); WigleCsv.export(filterUploadable(dao.observationsForRun(runId)), appRelease) }

    suspend fun prepareExportNew(appRelease: String): PendingExport? = withContext(Dispatchers.IO) {
        flushPending()
        val rows = filterUploadable(dao.notExported())
        if (rows.isEmpty()) return@withContext null
        PendingExport(
            csv = WigleCsv.export(rows, appRelease),
            keys = rows.map { it.key },
            total = rows.size,
            ble = rows.count { it.type == "BLE" },
            wifi = rows.count { it.type == "WIFI" },
            cell = rows.count { it.type == "CELL" }
        )
    }

    suspend fun commitExport(p: PendingExport) = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        p.keys.chunked(500).forEach { dao.markExported(it, ts) }
        dao.insertExportSession(ExportSessionEntity(exportedAt = ts, total = p.total, ble = p.ble, wifi = p.wifi, cell = p.cell, dest = "File"))
    }

    fun exportSessions() = dao.exportSessions()

    suspend fun prepareWigleSend(appRelease: String): PendingExport? = withContext(Dispatchers.IO) {
        flushPending()
        val rows = filterUploadable(dao.notInWigle())
        if (rows.isEmpty()) return@withContext null
        PendingExport(
            csv = WigleCsv.export(rows, appRelease),
            keys = rows.map { it.key },
            total = rows.size,
            ble = rows.count { it.type == "BLE" },
            wifi = rows.count { it.type == "WIFI" },
            cell = rows.count { it.type == "CELL" }
        )
    }

    suspend fun commitWigleSent(p: PendingExport) = withContext(Dispatchers.IO) {
        com.rocketgod.warble.usb.BeastDiag.log("commitWigleSent: marking ${p.keys.size} rows in_wigle…")
        val ts = System.currentTimeMillis()

        location.resetDistance()

        var marked = 0
        writeLock.withLock { p.keys.chunked(500).forEach { marked += dao.markInWigle(it) } }
        val remaining = dao.countNotInWigleNow()
        com.rocketgod.warble.usb.BeastDiag.log("commitWigleSent: done — $marked marked; $remaining still not-in-wigle (should be ~0)")

        runCatching {
            p.keys.chunked(500).forEach { dao.markExported(it, ts) }
            dao.insertExportSession(ExportSessionEntity(exportedAt = ts, total = p.total, ble = p.ble, wifi = p.wifi, cell = p.cell, dest = "WiGLE"))
        }
    }

    suspend fun prepareWdgwSend(appRelease: String): PendingExport? = withContext(Dispatchers.IO) {
        flushPending()
        val rows = filterUploadable(dao.notInWdgw())
        if (rows.isEmpty()) return@withContext null
        PendingExport(
            csv = WigleCsv.export(rows, appRelease),
            keys = rows.map { it.key },
            total = rows.size,
            ble = rows.count { it.type == "BLE" },
            wifi = rows.count { it.type == "WIFI" },
            cell = rows.count { it.type == "CELL" }
        )
    }

    suspend fun commitWdgwSent(p: PendingExport) = withContext(Dispatchers.IO) {
        com.rocketgod.warble.usb.BeastDiag.log("commitWdgwSent: marking ${p.keys.size} rows in_wdgw…")
        var marked = 0
        writeLock.withLock { p.keys.chunked(500).forEach { marked += dao.markInWdgw(it) } }
        val remaining = dao.countNotInWdgwNow()
        com.rocketgod.warble.usb.BeastDiag.log("commitWdgwSent: done — $marked marked; $remaining still not-in-wdgw (should be ~0)")
        runCatching {
            dao.insertExportSession(ExportSessionEntity(exportedAt = System.currentTimeMillis(), total = p.total, ble = p.ble, wifi = p.wifi, cell = p.cell, dest = "WDGWars"))
        }
    }

    data class ChunkedSend(
        val ok: Boolean, val nothingNew: Boolean, val sentWifi: Int, val sentTotal: Int,
        val remaining: Int, val failMsg: String?
    )

    data class CsvCounts(val total: Int, val wifi: Int, val ble: Int, val cell: Int)

    private suspend fun streamCsv(
        out: java.io.Writer, appRelease: String, pageSize: Int = 5000, requireLocation: Boolean = false,
        pager: suspend (afterKey: String, n: Int) -> List<ObservationEntity>
    ): CsvCounts {
        out.write(WigleCsv.header(appRelease))
        var afterKey = ""; var total = 0; var wifi = 0; var ble = 0; var cell = 0
        while (true) {
            val raw = pager(afterKey, pageSize)
            if (raw.isEmpty()) break
            afterKey = raw.last().key
            for (o in filterUploadable(raw)) {

                if (requireLocation && (o.lat == null || o.lng == null)) continue
                WigleCsv.appendRow(out, o); total++
                when (o.type) { "WIFI" -> wifi++; "BLE" -> ble++; "CELL" -> cell++ }
            }
        }
        out.flush()
        return CsvCounts(total, wifi, ble, cell)
    }

    private suspend fun markSentPaged(
        pageSize: Int = 5000, requireLocation: Boolean = false,
        pager: suspend (afterKey: String, n: Int) -> List<ObservationEntity>,
        mark: suspend (List<String>) -> Unit
    ) {
        var afterKey = ""
        while (true) {
            val raw = pager(afterKey, pageSize)
            if (raw.isEmpty()) break
            afterKey = raw.last().key

            val keys = filterUploadable(raw)
                .filter { !requireLocation || (it.lat != null && it.lng != null) }
                .map { it.key }
            if (keys.isNotEmpty()) writeLock.withLock { keys.chunked(500).forEach { mark(it) } }
        }
    }

    suspend fun sendToWigleStreamed(
        appRelease: String, filename: String,
        upload: suspend (file: java.io.File, filename: String) -> Pair<Boolean, String>
    ): ChunkedSend = withContext(Dispatchers.IO) {
        flushPending()
        val total = dao.countNotInWigleNow().toInt()
        val anyLoc = dao.countNotInWigleAnyLocNow().toInt()
        com.rocketgod.warble.usb.BeastDiag.log("WiGLE send: located-new=$total, any-loc-new=$anyLoc")
        if (total == 0) {
            val why = nothingNewReason(anyLoc)
            com.rocketgod.warble.usb.BeastDiag.log("WiGLE send: nothing to upload — ${why ?: "all already in WiGLE"}")
            return@withContext ChunkedSend(true, nothingNew = true, 0, 0, 0, why)
        }

        val tmp = java.io.File(context.cacheDir, "wardrive_wigle_upload.csv.gz")
        val counts = try {
            java.util.zip.GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { w ->
                streamCsv(w, appRelease, requireLocation = true) { a, n -> dao.notInWigleAfter(a, n) }
            }
        } catch (e: Throwable) { runCatching { tmp.delete() }; throw e }
        if (counts.total == 0) {
            tmp.delete()
            com.rocketgod.warble.usb.BeastDiag.log("WiGLE send: $total located-new all withheld by privacy rules")
            return@withContext ChunkedSend(true, nothingNew = true, 0, 0, 0, "$total new network${plural(total)} withheld by your privacy rules (exclusion zone / blocked / _nomap)")
        }
        val gzName = if (filename.endsWith(".gz")) filename else "$filename.gz"
        com.rocketgod.warble.usb.BeastDiag.log("WiGLE send: uploading ${counts.total} rows (${counts.wifi} Wi-Fi) as $gzName")
        val (ok, msg) = upload(tmp, gzName)
        if (!ok) { runCatching { tmp.delete() }; com.rocketgod.warble.usb.BeastDiag.log("WiGLE send: upload REJECTED — $msg"); return@withContext ChunkedSend(false, false, 0, 0, total, msg) }

        val ts = System.currentTimeMillis()
        markSentPaged(requireLocation = true, pager = { a, n -> dao.notInWigleAfter(a, n) }) { keys ->
            dao.markInWigle(keys); runCatching { dao.markExported(keys, ts) }
        }

        runCatching {
            val remain = dao.countNotInWigleNow()
            if (remain > 0) {
                val s = dao.sampleNotInWigle(6).joinToString(" | ") {
                    "${it.type}:${it.key.takeLast(8)} loc=${if (it.lat != null && it.lng != null) "Y" else "N"} run=${it.runId} inWigle=${it.inWigle} name=${it.name?.take(12)}"
                }
                com.rocketgod.warble.usb.BeastDiag.log("WiGLE post-mark: still $remain not-in-wigle → $s")
            } else com.rocketgod.warble.usb.BeastDiag.log("WiGLE post-mark: 0 not-in-wigle ✓")
        }
        location.resetDistance()
        runCatching { dao.insertExportSession(ExportSessionEntity(exportedAt = ts, total = counts.total, ble = counts.ble, wifi = counts.wifi, cell = counts.cell, dest = "WiGLE")) }
        finalizeRun()
        runCatching { tmp.delete() }
        ChunkedSend(true, false, counts.wifi, counts.total, 0, null)
    }

    suspend fun sendToWdgwStreamed(
        appRelease: String, filename: String,
        upload: suspend (file: java.io.File, filename: String) -> Pair<Boolean, String>
    ): ChunkedSend = withContext(Dispatchers.IO) {
        flushPending()
        val total = dao.countNotInWdgwNow().toInt()
        val anyLoc = dao.countNotInWdgwAnyLocNow().toInt()
        com.rocketgod.warble.usb.BeastDiag.log("WDGWars send: located-new=$total, any-loc-new=$anyLoc")
        if (total == 0) {
            val why = nothingNewReason(anyLoc)
            com.rocketgod.warble.usb.BeastDiag.log("WDGWars send: nothing to upload — ${why ?: "all already in WDGWars"}")
            return@withContext ChunkedSend(true, nothingNew = true, 0, 0, 0, why)
        }
        val tmp = java.io.File(context.cacheDir, "wardrive_wdgw_upload.csv")
        val counts = try {
            tmp.bufferedWriter().use { w -> streamCsv(w, appRelease, requireLocation = true) { a, n -> dao.notInWdgwAfter(a, n) } }
        } catch (e: Throwable) { runCatching { tmp.delete() }; throw e }
        if (counts.total == 0) {
            tmp.delete()
            com.rocketgod.warble.usb.BeastDiag.log("WDGWars send: $total located-new all withheld by privacy rules")
            return@withContext ChunkedSend(true, nothingNew = true, 0, 0, 0, "$total new network${plural(total)} withheld by your privacy rules (exclusion zone / blocked / _nomap)")
        }
        com.rocketgod.warble.usb.BeastDiag.log("WDGWars send: uploading ${counts.total} rows (${counts.wifi} Wi-Fi) as $filename")
        val (ok, msg) = upload(tmp, filename)
        if (!ok) { runCatching { tmp.delete() }; com.rocketgod.warble.usb.BeastDiag.log("WDGWars send: upload REJECTED — $msg"); return@withContext ChunkedSend(false, false, 0, 0, total, msg) }
        val ts = System.currentTimeMillis()
        markSentPaged(requireLocation = true, pager = { a, n -> dao.notInWdgwAfter(a, n) }) { keys -> dao.markInWdgw(keys) }
        runCatching { dao.insertExportSession(ExportSessionEntity(exportedAt = ts, total = counts.total, ble = counts.ble, wifi = counts.wifi, cell = counts.cell, dest = "WDGWars")) }
        runCatching { tmp.delete() }
        ChunkedSend(true, false, counts.wifi, counts.total, 0, null)
    }

    suspend fun exportToStream(out: java.io.OutputStream, appRelease: String, onlyNew: Boolean): CsvCounts = withContext(Dispatchers.IO) {
        flushPending()
        val ts = System.currentTimeMillis()
        val counts = out.bufferedWriter().let { w ->
            val c = if (onlyNew) streamCsv(w, appRelease) { a, n -> dao.notExportedAfter(a, n) }
                    else streamCsv(w, appRelease) { a, n -> dao.allObservationsAfter(a, n) }
            w.flush(); c
        }
        if (onlyNew && counts.total > 0) {

            markSentPaged(pager = { a, n -> dao.notExportedAfter(a, n) }) { keys ->
                dao.markExported(keys, ts)
            }
        }
        runCatching { dao.insertExportSession(ExportSessionEntity(exportedAt = ts, total = counts.total, ble = counts.ble, wifi = counts.wifi, cell = counts.cell, dest = "File")) }
        counts
    }

    @Volatile private var syncConn: java.net.HttpURLConnection? = null

    fun cancelWigleSync() { runCatching { syncConn?.disconnect() } }

    suspend fun syncWigleMine(apiName: String, apiToken: String, onProgress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        if (apiName.isBlank() || apiToken.isBlank()) throw IllegalStateException("Add your WiGLE API key first")
        var conn: java.net.HttpURLConnection? = null
        var count = 0
        try {
            conn = (java.net.URL("https://api.wigle.net/api/v2/network/mine").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 90_000
                val basic = android.util.Base64.encodeToString("$apiName:$apiToken".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $basic")
                setRequestProperty("Accept", "application/json")
            }
            syncConn = conn
            val code = conn.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("WiGLE rejected the credentials (HTTP $code).")
            if (code !in 200..299) throw IllegalStateException("WiGLE /network/mine failed (HTTP $code).")
            val reader = android.util.JsonReader(conn.inputStream.reader(Charsets.UTF_8).buffered(1 shl 16))
            val batch = ArrayList<com.rocketgod.warble.data.WigleKnownEntity>(10_000)
            reader.use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "results" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                batch.add(com.rocketgod.warble.data.WigleKnownEntity(r.nextString().lowercase()))
                                count++
                                if (batch.size >= 10_000) {
                                    ensureActive()
                                    dao.insertKnown(batch); batch.clear()
                                    onProgress(count)
                                }
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            if (batch.isNotEmpty()) dao.insertKnown(batch)
            onProgress(count)

            val flipped = writeLock.withLock { dao.markKnownWifiInWigle() }
            com.rocketgod.warble.usb.BeastDiag.log("WiGLE mine sync: indexed $count BSSIDs, flipped $flipped local Wi-Fi to in-WiGLE")
        } catch (e: Exception) {
            com.rocketgod.warble.usb.BeastDiag.log("WiGLE mine sync error after $count: ${e.message}")

            if (count > 0) {
                runCatching { writeLock.withLock { dao.markKnownWifiInWigle() } }
            } else throw e
        } finally {
            syncConn = null
            conn?.disconnect()
        }
        count
    }

    suspend fun wigleKnownCount(): Long = withContext(Dispatchers.IO) { dao.knownCount() }

    suspend fun importWigle(input: InputStream): Int = withContext(Dispatchers.IO) {
        val rows = WigleCsv.import(input)
        var added = 0
        val now = System.currentTimeMillis()
        val existingKeys = ArrayList<String>()
        for (r in rows) {
            if (dao.find(r.key) != null) { existingKeys.add(r.key); continue }
            val type = when {
                r.type.equals("WIFI", true) -> SignalType.WIFI
                r.type.equals("BT", true) || r.type.equals("BLE", true) -> SignalType.BLE
                else -> SignalType.CELL
            }
            val (cat, icon) = when (type) {
                SignalType.WIFI -> "WiFi AP" to "wifi"
                SignalType.BLE -> "Unidentified" to "questionmark"
                SignalType.CELL -> "Cell tower" to "antenna.radiowaves.left.and.right"
            }

            dao.upsert(
                ObservationEntity(
                    key = r.key, type = type.name, name = r.name, maker = null, category = cat, icon = icon,
                    bestRssi = -120, lastRssi = -120, timesSeen = 0, firstSeen = now, lastSeen = now,
                    companyId = null, channel = null, frequency = null, capabilities = null, connectable = false,
                    lat = null, lng = null, altitude = null, accuracy = null, runId = 0L, inWigle = true, exportedAt = now, viaMonitor = false
                )
            )
            added++
        }

        writeLock.withLock { existingKeys.chunked(500).forEach { runCatching { dao.markInWigle(it) } } }
        added
    }

    suspend fun finalizeRun() = withContext(Dispatchers.IO) {
        flushPending()
        val count = dao.runCount(runId)
        if (count <= 0L) return@withContext
        dao.closeRun(runId, System.currentTimeMillis(), count)
        val now = System.currentTimeMillis()
        runId = dao.insertRun(RunEntity(startedAt = now, endedAt = null, contactCount = 0))
        runStartedAt = now
    }

    fun currentRunId() = runId
    fun dao() = dao
}
