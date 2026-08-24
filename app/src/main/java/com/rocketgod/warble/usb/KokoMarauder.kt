package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.scan.RawObservation

object KokoMarauder {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    fun abort() { stop = true }

    private val WIGLE_RE = Regex(
        """([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}),([^,]*),([^,]*),[^,]*,(\d+),(-?\d+),[^,]*,[^,]*,[^,]*,[^,]*,(WIFI|BLE)\s*$"""
    )

    private val AP_RE = Regex("""^(-?\d+)\s+Ch:\s*(\d+)\s+([0-9a-fA-F:]{17})\s+ESSID:\s+(.*?)(?:\s+\d+\s+\d+)?\s*$""")

    private val CLIENT_RE = Regex("""^(-?\d+)\s+Ch:\s*(\d+)\s+Client:\s+([0-9a-fA-F:]{17})\s+Requesting:\s*(.*)$""")

    @Volatile var sniffClients = false

    @Volatile var preferWardrive = false

    private val HOP_24 = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
    private val HOP_5 = intArrayOf(
        36, 40, 44, 48, 52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165
    )
    private const val DWELL_MS = 350L

    private class Dev(
        var type: SignalType, var name: String?, var auth: String?,
        var rssi: Int, var channel: Int, var lastSeen: Long, var client: Boolean = false
    )

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit, onNotice: (String, String) -> Unit,
            onFrame: (List<RawObservation>) -> Unit) {
        if (running) return
        running = true; stop = false
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("Marauder: couldn't open serial"); running = false; return }
        val cp = Cp210x(conn, device)
        val buf = ByteArray(4096)
        try {
            if (!cp.open(115200)) { onStatus("Marauder: CP210x open failed"); return }
            BeastDiag.log("=== Marauder serial bring-up (CP210x 10c4:ea60) ===")
            onStatus("Marauder · resetting…")
            cp.resetEsp32()

            val boot = StringBuilder()
            val bootDeadline = System.currentTimeMillis() + 8000
            while (!stop && System.currentTimeMillis() < bootDeadline) {
                val n = cp.read(buf, 400); if (n > 0) boot.append(String(buf, 0, n))
                if (boot.contains("CLI Ready") || boot.contains("Ghost ESP", true)) break
            }

            val looksMarauder = boot.contains("Marauder", true) || boot.contains("CLI Ready")
            if (!boot.contains("Ghost ESP", true) && !looksMarauder) {
                cp.write("help\r\n")
                val dl = System.currentTimeMillis() + 1500
                while (!stop && System.currentTimeMillis() < dl) { val n = cp.read(buf, 300); if (n > 0) boot.append(String(buf, 0, n)) }
            }
            if (boot.contains("Ghost ESP", true)) {
                BeastDiag.log("Marauder bring-up: board is running Ghost ESP firmware (not Marauder) — handing off to Ghost ESP driver over CP210x")
                GhostEsp.driveOverSerial(cp, onStatus, onFrame)
                return
            }
            var identified = boot.contains("Marauder", ignoreCase = true) || boot.contains("CLI Ready")
            if (!identified) {
                cp.write("stopscan -f\n")
                val probe = System.currentTimeMillis() + 1500
                while (!stop && System.currentTimeMillis() < probe) { val n = cp.read(buf, 300); if (n > 0) boot.append(String(buf, 0, n)) }
                identified = boot.contains("Marauder", true) || boot.contains(">") || boot.contains("#")
            }
            if (!identified) {

                BeastDiag.log("Marauder: unrecognized firmware — handing to generic best-effort passive scan (unknown firmware)")
                GhostEsp.driveOverSerial(cp, onStatus, onFrame)
                return
            }

            val (chip, dualBand) = detectChip(boot.toString())
            val label = "$chip Marauder"
            val sdPresent = !(boot.contains("SD Card NOT Supported") || boot.contains("Failed to mount SD"))
            BeastDiag.log("Marauder: chip=$chip (${if (dualBand) "dual-band 2.4+5 GHz" else "2.4 GHz only"}), " +
                "SD ${if (sdPresent) "mounted — wardrive (Wi-Fi+BLE)" else "absent — sniffbeacon (Wi-Fi-only)"}")

            Thread.sleep(2500)
            cp.write("led -p rainbow\n")
            Thread.sleep(150)
            cp.write("stopscan -f\n"); Thread.sleep(250)
            cp.drain()

            val hop = if (dualBand) HOP_24 + HOP_5 else HOP_24

            if (sdPresent && preferWardrive) {
                val noRows = runWardrive(cp, buf, label, onFrame, onStatus)
                if (noRows && !stop) {
                    onNotice(
                        "No GPS on the device",
                        "$label couldn't reach a GPS — Marauder's wardrive logger only streams once it has a GPS " +
                        "fix, so it sent no networks. Switched to Wi-Fi scan mode: the app geotags with the phone's " +
                        "GPS, so you still map everything it sees."
                    )
                    runSniffHop(cp, buf, label, hop, onFrame, onStatus)
                }
            } else {
                BeastDiag.log("Marauder: sniff-hop mode (Wi-Fi APs" + (if (sniffClients) " + clients" else "") +
                    ", ${hop.size} ch)" + if (sdPresent) " — SD present but sniff-hop out-scans wardrive" else "")
                runSniffHop(cp, buf, label, hop, onFrame, onStatus)
            }
        } catch (e: Exception) {
            BeastDiag.log("Marauder: serial error — ${e.message}")
            onStatus("Marauder: error · ${e.message}")
        } finally {
            runCatching { cp.write("stopscan -f\n") }
            cp.close(); running = false
            BeastDiag.log("Marauder: serial source stopped")
        }
    }

    private fun detectChip(boot: String): Pair<String, Boolean> {
        val m = Regex("ESP-ROM:esp32([a-z0-9]*)", RegexOption.IGNORE_CASE).find(boot) ?: return "ESP32" to false
        val s = m.groupValues[1].lowercase()
        return when {
            s.startsWith("c5") -> "ESP32-C5" to true
            s.startsWith("c6") -> "ESP32-C6" to false
            s.startsWith("c3") -> "ESP32-C3" to false
            s.startsWith("c2") -> "ESP32-C2" to false
            s.startsWith("s3") -> "ESP32-S3" to false
            s.startsWith("s2") -> "ESP32-S2" to false
            s.startsWith("h2") -> "ESP32-H2" to false
            else -> "ESP32" to false
        }
    }

    private fun runWardrive(cp: Cp210x, buf: ByteArray, label: String,
                            onFrame: (List<RawObservation>) -> Unit, onStatus: (String) -> Unit): Boolean {
        cp.write("wardrive\n")
        BeastDiag.log("Marauder: wardrive mode — WiGLE serial stream, Wi-Fi + BLE")
        onStatus("$label · wardrive · starting…")
        val devs = LinkedHashMap<String, Dev>()
        val line = StringBuilder()
        val start = System.currentTimeMillis()
        var lastFeed = 0L; var lastDiag = start
        var matched = 0L; var lastLine = ""; var gpsAbsent = false
        val freshMs = 30_000L
        while (!stop) {
            val n = cp.read(buf, 200)
            if (n > 0) {
                val chunk = String(buf, 0, n)
                for (c in chunk) {
                    if (c == '\n' || c == '\r') {
                        val ln = line.toString(); line.setLength(0)
                        if (ln.isNotBlank()) lastLine = ln.trim()
                        if (ln.contains("GPS Module not detected", true) || ln.contains("No GPS", true)) gpsAbsent = true
                        if (parseWigle(ln, devs)) matched++
                    } else line.append(c)
                }
            }
            val now = System.currentTimeMillis()

            if (matched == 0L && ((gpsAbsent && now - start > 8000) || now - start > 25000)) {
                BeastDiag.log("Marauder: wardrive emitted 0 rows (${if (gpsAbsent) "GPS module absent" else "no GPS fix"}) — falling back to sniffbeacon")
                runCatching { cp.write("stopscan -f\n") }; Thread.sleep(250); cp.drain()
                return true
            }
            if (now - lastFeed >= 2000) { lastFeed = now; feedWardrive(devs, now, freshMs, label, onFrame, onStatus) }
            if (now - lastDiag >= 12000) {
                lastDiag = now
                val f = devs.values.count { now - it.lastSeen < freshMs }
                BeastDiag.log("Marauder[$label·wardrive]: rows=$matched live=$f | last: ${lastLine.take(90)}")
            }
        }
        return false
    }

    private fun parseWigle(s: String, devs: LinkedHashMap<String, Dev>): Boolean {
        val m = WIGLE_RE.find(s) ?: return false
        val mac = m.groupValues[1].lowercase()
        val ssid = m.groupValues[2]
        val auth = m.groupValues[3]
        val ch = m.groupValues[4].toIntOrNull() ?: 0
        val rssi = m.groupValues[5].toIntOrNull() ?: return false
        val type = if (m.groupValues[6] == "BLE") SignalType.BLE else SignalType.WIFI
        val now = System.currentTimeMillis()
        val e = devs[mac]
        if (e == null) devs[mac] = Dev(type, ssid.ifBlank { null }, auth.ifBlank { null }, rssi, ch, now)
        else { e.type = type; if (ssid.isNotBlank()) e.name = ssid; if (auth.isNotBlank()) e.auth = auth; e.rssi = rssi; e.channel = ch; e.lastSeen = now }
        return true
    }

    private fun feedWardrive(devs: LinkedHashMap<String, Dev>, now: Long, freshMs: Long, label: String,
                             onFrame: (List<RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val fresh = devs.entries.filter { now - it.value.lastSeen < freshMs }
        if (fresh.isEmpty()) { onStatus("$label · wardrive · scanning…"); return }
        onFrame(fresh.map { (mac, d) -> toObs(mac, d) })
        val wifi = fresh.count { it.value.type == SignalType.WIFI }
        val ble = fresh.size - wifi
        onStatus("$label · wardrive · $wifi Wi-Fi · $ble BLE")
    }

    private const val AP_PHASE_MS = 10_000L
    private const val STA_PHASE_MS = 5_000L

    private fun runSniffHop(cp: Cp210x, buf: ByteArray, label: String, hop: IntArray,
                            onFrame: (List<RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val freshMs = hop.size * DWELL_MS * 2
        val devs = LinkedHashMap<String, Dev>()
        val line = StringBuilder()
        var lastFeed = 0L; var lastDiag = System.currentTimeMillis()
        var hopIdx = 0; var dwellStart = System.currentTimeMillis()
        var apRows = 0L; var staRows = 0L; var lastLine = ""
        var clientPhase = false; var phaseStart = System.currentTimeMillis()

        fun startSniff(cmd: String) {
            cp.write("stopscan -f\n"); Thread.sleep(180); cp.drain()
            cp.write("channel -s ${hop[hopIdx]}\n"); Thread.sleep(60)
            cp.write("$cmd\n")
        }
        startSniff("sniffbeacon")
        BeastDiag.log("Marauder: sniff-hop ${hop.size} ch @ ${DWELL_MS}ms" +
            if (sniffClients) " (beacons + probe clients)" else " (beacons)")
        onStatus("$label · scan · Wi-Fi" + if (sniffClients) " + clients" else "")
        while (!stop) {
            val n = cp.read(buf, 150)
            if (n > 0) {
                val chunk = String(buf, 0, n)
                for (c in chunk) {
                    if (c == '\n' || c == '\r') {
                        val ln = line.toString().trim(); line.setLength(0)
                        if (ln.isNotEmpty()) lastLine = ln
                        if (clientPhase) { if (parseProbe(ln, devs)) staRows++ }
                        else { if (parseSniff(ln, devs)) apRows++ }
                    } else line.append(c)
                }
            }
            val now = System.currentTimeMillis()
            if (now - dwellStart >= DWELL_MS) { hopIdx = (hopIdx + 1) % hop.size; cp.write("channel -s ${hop[hopIdx]}\n"); dwellStart = now }

            if (sniffClients && now - phaseStart >= (if (clientPhase) STA_PHASE_MS else AP_PHASE_MS)) {
                clientPhase = !clientPhase; phaseStart = now
                startSniff(if (clientPhase) "sniffprobe" else "sniffbeacon")
            }
            if (now - lastFeed >= 2000) { lastFeed = now; feedSniff(devs, now, hop[hopIdx], freshMs, label, onFrame, onStatus) }
            if (now - lastDiag >= 12000) {
                lastDiag = now
                val f = devs.values.count { now - it.lastSeen < freshMs }
                val fc = devs.values.count { it.client && now - it.lastSeen < freshMs }
                BeastDiag.log("Marauder[$label·sniff ${hop.size}ch]: aps=$apRows clients=$staRows live=$f (${fc} cli) " +
                    "ch=${hop[hopIdx]} ${if (clientPhase) "probe" else "beacon"} | last: ${lastLine.take(80)}")
            }
        }
    }

    private fun parseSniff(s: String, aps: LinkedHashMap<String, Dev>): Boolean {
        val m = AP_RE.matchEntire(s) ?: return false
        val rssi = m.groupValues[1].toIntOrNull() ?: return false
        val ch = m.groupValues[2].toIntOrNull() ?: return false
        val bssid = m.groupValues[3].lowercase()
        val ssid = m.groupValues[4]
        val now = System.currentTimeMillis()
        val e = aps[bssid]
        if (e == null) aps[bssid] = Dev(SignalType.WIFI, ssid.ifBlank { null }, null, rssi, ch, now)
        else { e.rssi = rssi; e.channel = ch; if (ssid.isNotBlank()) e.name = ssid; e.client = false; e.lastSeen = now }
        return true
    }

    private fun parseProbe(s: String, devs: LinkedHashMap<String, Dev>): Boolean {
        val m = CLIENT_RE.matchEntire(s) ?: return false
        val rssi = m.groupValues[1].toIntOrNull() ?: return false
        val ch = m.groupValues[2].toIntOrNull() ?: 0
        val mac = m.groupValues[3].lowercase()
        val now = System.currentTimeMillis()
        val e = devs[mac]
        if (e == null) devs[mac] = Dev(SignalType.WIFI, null, null, rssi, ch, now, client = true)
        else { e.rssi = rssi; if (ch > 0) e.channel = ch; e.client = true; e.lastSeen = now }
        return true
    }

    private fun feedSniff(devs: LinkedHashMap<String, Dev>, now: Long, curCh: Int, freshMs: Long, label: String,
                          onFrame: (List<RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val fresh = devs.entries.filter { now - it.value.lastSeen < freshMs }
        if (fresh.isEmpty()) { onStatus("$label · scan · ch $curCh"); return }
        onFrame(fresh.map { (mac, d) -> toObs(mac, d) })
        val cli = fresh.count { it.value.client }
        val ap = fresh.size - cli
        onStatus("$label · $ap Wi-Fi" + (if (sniffClients) " · $cli clients" else "") + " · ch $curCh")
    }

    private fun toObs(mac: String, d: Dev): RawObservation = RawObservation(
        key = mac,
        type = d.type,
        name = d.name,
        rssi = d.rssi.coerceIn(-120, -20),
        capabilities = d.auth,
        channel = if (d.type == SignalType.WIFI) d.channel else null,
        frequency = if (d.type == SignalType.WIFI) chToFreq(d.channel) else null,
        viaMonitor = true,
        wifiClient = d.client
    )

    private fun chToFreq(ch: Int): Int = when {
        ch in 1..14 -> if (ch == 14) 2484 else 2407 + ch * 5
        ch >= 32 -> 5000 + ch * 5
        else -> 0
    }
}
