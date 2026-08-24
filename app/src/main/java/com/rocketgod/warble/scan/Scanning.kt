package com.rocketgod.warble.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.rocketgod.warble.model.SignalType

data class RawObservation(
    val key: String,
    val type: SignalType,
    val name: String?,
    val rssi: Int,
    val companyId: Int? = null,
    val serviceUuids: List<String> = emptyList(),

    val appleType: Int? = null,

    val fmdn: Boolean = false,
    val maker: String? = null,
    val channel: Int? = null,
    val frequency: Int? = null,
    val capabilities: String? = null,
    val connectable: Boolean = false,
    val viaMonitor: Boolean = false,

    val wifiClient: Boolean = false,

    val wifiStandard: Int = 0,

    val channelWidthMhz: Int = 0,

    val centerFreqMhz: Int = 0,

    val rttResponder: Boolean = false,

    val flockProbe: Boolean = false
)

interface ScanSource {
    val type: SignalType
    fun start(onBatch: (List<RawObservation>) -> Unit)
    fun stop()
}

class BleScanSource(
    private val context: Context,
    private val wifiPriority: () -> Boolean = { false }
) : ScanSource {
    override val type = SignalType.BLE
    private var scanner: BluetoothLeScanner? = null
    private var cb: ScanCallback? = null
    private var sink: ((List<RawObservation>) -> Unit)? = null
    @Volatile private var lastStart = 0L
    @Volatile private var retryAt = 0L
    @Volatile private var lastPriority: Boolean? = null

    @SuppressLint("MissingPermission")
    override fun start(onBatch: (List<RawObservation>) -> Unit) {
        sink = onBatch
        beginScan()
    }

    @SuppressLint("MissingPermission")
    private fun beginScan() {
        val onBatch = sink ?: return
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = mgr?.adapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return

        val priority = wifiPriority()
        lastPriority = priority
        val settings = ScanSettings.Builder()
            .setScanMode(if (priority) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(if (priority) BLE_REPORT_DELAY_MS else 0L)
            .apply {

                setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !priority) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                map(result)?.let { onBatch(listOf(it)) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                onBatch(results.mapNotNull { map(it) })
            }
            override fun onScanFailed(errorCode: Int) {
                com.rocketgod.warble.usb.BeastDiag.log("BLE scan failed ($errorCode) — re-arming")

                retryAt = System.currentTimeMillis() + 35_000L
            }
        }

        try { cb?.let { scanner?.stopScan(it) } } catch (_: Throwable) {}
        cb = callback
        try {
            scanner?.startScan(null, settings, callback)
            lastStart = System.currentTimeMillis()
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun poll() {
        val now = System.currentTimeMillis()

        val profileChanged = lastPriority != null && wifiPriority() != lastPriority
        if ((retryAt in 1..now) || now - lastStart > 15 * 60_000L || profileChanged) { retryAt = 0; beginScan() }
        else cb?.let { c -> try { scanner?.flushPendingScanResults(c) } catch (_: Throwable) {} }
    }

    private fun map(r: ScanResult): RawObservation? {
        val rec = r.scanRecord
        val address = r.device?.address ?: return null
        var companyId: Int? = null
        var appleType: Int? = null
        rec?.manufacturerSpecificData?.let { msd ->
            if (msd.size() > 0) {
                companyId = msd.keyAt(0)

                if (companyId == 0x004C) {
                    val v = msd.valueAt(0)
                    if (v != null && v.size >= 2 && (v[0].toInt() and 0xFF) == 0x12 && (v[1].toInt() and 0xFF) == 0x19) {
                        appleType = 0x12
                    }
                }
            }
        }
        val uuids = rec?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()

        var fmdn = false
        rec?.serviceData?.let { sd ->
            for ((pu, bytes) in sd) {
                if (pu.uuid.toString().lowercase().startsWith("0000feaa") && bytes.isNotEmpty()) {
                    val f = bytes[0].toInt() and 0xFF
                    if (f == 0x40 || f == 0x41) { fmdn = true; break }
                }
            }
        }
        val name = rec?.deviceName
        val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) r.isConnectable else true
        return RawObservation(
            key = address,
            type = SignalType.BLE,
            name = name,
            rssi = r.rssi,
            companyId = companyId,
            serviceUuids = uuids,
            appleType = appleType,
            fmdn = fmdn,
            connectable = connectable
        )
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        sink = null
        try { cb?.let { scanner?.stopScan(it) } } catch (_: SecurityException) {}
        cb = null
    }

    companion object {

        private const val BLE_REPORT_DELAY_MS = 15_000L
    }
}

class WifiScanSource(
    private val context: Context,

    private val onDistances: (Map<String, Int>) -> Unit = {}
) : ScanSource {
    override val type = SignalType.WIFI
    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var receiver: BroadcastReceiver? = null
    private var sink: ((List<RawObservation>) -> Unit)? = null
    private var thread: HandlerThread? = null

    private val rttManager: android.net.wifi.rtt.WifiRttManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_RTT))
            context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? android.net.wifi.rtt.WifiRttManager
        else null
    }
    @Volatile private var rttCandidates: List<android.net.wifi.ScanResult> = emptyList()
    @Volatile private var rttInFlight = false
    @Volatile private var lastRtt = 0L

    @SuppressLint("MissingPermission")
    override fun start(onBatch: (List<RawObservation>) -> Unit) {
        sink = onBatch

        val ht = HandlerThread("wardrive-wifi").also { it.start() }
        thread = ht
        val handler = Handler(ht.looper)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = publish()
        }
        receiver = r
        context.registerReceiver(r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), null, handler)
        try { wifi.startScan() } catch (_: Exception) {}
        handler.post { publish() }
    }

    fun poll() {
        try { wifi.startScan() } catch (_: Exception) {}
        maybeRange()
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun maybeRange() {
        val mgr = rttManager ?: return
        val cands = rttCandidates
        if (cands.isEmpty() || rttInFlight) return
        val now = System.currentTimeMillis()
        if (now - lastRtt < RTT_INTERVAL_MS) return
        if (!runCatching { mgr.isAvailable }.getOrDefault(false)) return
        rttInFlight = true
        try {
            val max = runCatching { android.net.wifi.rtt.RangingRequest.getMaxPeers() }.getOrDefault(10)
            val req = android.net.wifi.rtt.RangingRequest.Builder()
                .addAccessPoints(cands.take(max))
                .build()
            mgr.startRanging(req, context.mainExecutor, object : android.net.wifi.rtt.RangingResultCallback() {
                override fun onRangingResults(results: MutableList<android.net.wifi.rtt.RangingResult>) {
                    val map = HashMap<String, Int>()
                    for (r in results) {
                        if (r.status == android.net.wifi.rtt.RangingResult.STATUS_SUCCESS && r.distanceMm > 0)
                            runCatching { r.macAddress?.toString() }.getOrNull()?.let { map[it] = r.distanceMm }
                    }
                    if (map.isNotEmpty()) onDistances(map)
                    lastRtt = System.currentTimeMillis(); rttInFlight = false
                }
                override fun onRangingFailure(code: Int) {
                    com.rocketgod.warble.usb.BeastDiag.log("RTT ranging failed ($code)")
                    lastRtt = System.currentTimeMillis(); rttInFlight = false
                }
            })
        } catch (t: Throwable) {
            com.rocketgod.warble.usb.BeastDiag.log("RTT unavailable: ${t.message}")
            lastRtt = System.currentTimeMillis(); rttInFlight = false
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun publish() {
        val results = try { wifi.scanResults } catch (_: Exception) { return } ?: return
        val list = results.map { s ->
            val freq = s.frequency
            val std = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) s.wifiStandard else 0

            val widthMhz = when (runCatching { s.channelWidth }.getOrDefault(-1)) {
                android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ -> 20
                android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ, android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
                android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ -> 160
                else -> 0
            }

            val centerFreq = runCatching { s.centerFreq0 }.getOrDefault(0)
            val rtt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (runCatching { s.is80211mcResponder() }.getOrDefault(false) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        runCatching { s.is80211azNtbResponder() }.getOrDefault(false)))
            RawObservation(
                key = s.BSSID ?: return@map null,
                type = SignalType.WIFI,
                name = s.SSID?.ifBlank { null },
                rssi = s.level,
                maker = null,
                channel = channelFor(freq),
                frequency = freq,
                capabilities = s.capabilities,
                connectable = true,
                wifiStandard = std,
                channelWidthMhz = widthMhz,
                centerFreqMhz = centerFreq,
                rttResponder = rtt
            )
        }.filterNotNull()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) rttCandidates = results.filter {
            runCatching { it.is80211mcResponder() }.getOrDefault(false) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    runCatching { it.is80211azNtbResponder() }.getOrDefault(false))
        }
        sink?.invoke(list)

        if (sink != null && throttleOff()) try { wifi.startScan() } catch (_: Exception) {}
    }

    private fun throttleOff(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { !wifi.isScanThrottleEnabled }.getOrDefault(false)

    private fun channelFor(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2412) / 5 + 1
        freq in 5170..5825 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }

    override fun stop() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        thread?.let { try { it.quitSafely() } catch (_: Exception) {} }
        thread = null
        rttCandidates = emptyList()
    }

    companion object {
        private const val RTT_INTERVAL_MS = 5_000L
    }
}

class CellScanSource(private val context: Context) : ScanSource {
    override val type = SignalType.CELL
    private val tm = context.applicationContext
        .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var sink: ((List<RawObservation>) -> Unit)? = null

    override fun start(onBatch: (List<RawObservation>) -> Unit) {
        sink = onBatch
        poll()
    }

    @SuppressLint("MissingPermission", "NewApi")
    fun poll() {
        val manager = tm ?: return
        try {
            manager.requestCellInfoUpdate(context.mainExecutor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) = emit(cellInfo)
                })
        } catch (_: Throwable) {
            try { @Suppress("DEPRECATION") emit(manager.allCellInfo ?: emptyList()) } catch (_: Throwable) {}
        }
    }

    @Volatile private var netOp = ""
    @Volatile private var netName = ""

    @SuppressLint("MissingPermission")
    private fun emit(cells: List<CellInfo>) {
        netOp = try { tm?.networkOperator ?: "" } catch (_: Throwable) { "" }
        netName = try { tm?.networkOperatorName ?: "" } catch (_: Throwable) { "" }
        val out = ArrayList<RawObservation>()
        for (c in cells) {
            val o = try { mapCell(c) } catch (_: Throwable) { null }
            if (o != null) out.add(o)
        }
        if (out.isNotEmpty()) sink?.invoke(out)
    }

    @SuppressLint("NewApi")
    private fun mapCell(c: CellInfo): RawObservation? {
        return when (c) {
                is CellInfoLte -> {
                    val id = c.cellIdentity
                    build("LTE", mcc(id.mccString, 0), mnc(id.mncString, 0), id.ci.toLong(),
                        c.cellSignalStrength.dbm)
                }
                is CellInfoGsm -> {
                    val id = c.cellIdentity
                    build("GSM", mcc(id.mccString, 0), mnc(id.mncString, 0), id.cid.toLong(),
                        c.cellSignalStrength.dbm)
                }
                is CellInfoWcdma -> {
                    val id = c.cellIdentity
                    build("WCDMA", mcc(id.mccString, 0), mnc(id.mncString, 0), id.cid.toLong(),
                        c.cellSignalStrength.dbm)
                }
                else -> nr(c)
            }
    }

    private fun nr(c: CellInfo): RawObservation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (c !is CellInfoNr) return null
        val id = c.cellIdentity as? CellIdentityNr ?: return null
        val ss = c.cellSignalStrength as? CellSignalStrengthNr
        val nci = id.nci
        return build("NR", id.mccString ?: "", id.mncString ?: "", nci, ss?.dbm ?: -140)
    }

    private fun mcc(s: String?, fallback: Int): String = s ?: if (fallback > 0) fallback.toString() else ""
    private fun mnc(s: String?, fallback: Int): String = s ?: if (fallback > 0) fallback.toString() else ""

    private fun build(kind: String, mcc: String, mnc: String, cid: Long, dbm: Int): RawObservation? {
        if (cid == Long.MAX_VALUE || cid < 0 || (mcc.isBlank() && cid == 0L)) return null
        val key = "${mcc}_${mnc}_$cid"

        val carrier = if (netName.isNotBlank() && netOp == "$mcc$mnc") netName
            else listOf(mcc, mnc).filter { it.isNotBlank() }.joinToString("/")
        val name = listOf(carrier, kind).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
        return RawObservation(
            key = key,
            type = SignalType.CELL,
            name = name,
            rssi = if (dbm == Int.MAX_VALUE) -120 else dbm,
            capabilities = kind,
            connectable = false
        )
    }

    override fun stop() { sink = null }
}

class LocationProvider(private val context: Context) {
    @Volatile var last: Location? = null
        private set

    val speed = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)

    val bearing = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)

    private val prefs = context.getSharedPreferences("warble", Context.MODE_PRIVATE)

    val distanceM = kotlinx.coroutines.flow.MutableStateFlow(prefs.getFloat("run_distance_m", 0f).toDouble())
    @Volatile private var prevForDist: Location? = null
    private fun accept(l: Location) {
        prevForDist?.let { p ->
            val d = p.distanceTo(l)
            if (d in 3f..500f) {
                distanceM.value = distanceM.value + d
                prefs.edit().putFloat("run_distance_m", distanceM.value.toFloat()).apply()
            }
        }
        prevForDist = l
        last = l
        speed.value = if (l.hasSpeed() && l.speed in 0f..140f) l.speed else null
        bearing.value = if (l.hasBearing()) l.bearing else null
    }

    fun resetDistance() {
        distanceM.value = 0.0
        prevForDist = null
        prefs.edit().putFloat("run_distance_m", 0f).apply()
    }

    private val client by lazy {
        com.google.android.gms.location.LocationServices
            .getFusedLocationProviderClient(context)
    }
    private var callback: com.google.android.gms.location.LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 2000L) {
        try {

            callback?.let { client.removeLocationUpdates(it) }
            callback = null
            client.lastLocation.addOnSuccessListener { it?.let { l -> accept(l) } }

            val req = com.google.android.gms.location.LocationRequest.Builder(intervalMs)
                .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
                .build()
            val cb = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    result.lastLocation?.let { accept(it) }
                }
            }
            callback = cb
            client.requestLocationUpdates(req, cb, context.mainLooper)
        } catch (_: Throwable) {}
    }

    fun stop() { callback?.let { client.removeLocationUpdates(it) }; callback = null }
}
