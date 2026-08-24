package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.scan.RawObservation

object GhostEsp {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    fun abort() { stop = true }

    @Volatile var ledFx = true
    @Volatile var accentArgb: Int = 0x00E5FF

    private val NAMED = listOf(
        "red" to 0xFF0000, "green" to 0x00FF00, "blue" to 0x0000FF, "yellow" to 0xFFFF00,
        "purple" to 0x800080, "cyan" to 0x00FFFF, "orange" to 0xFFA500, "white" to 0xFFFFFF, "pink" to 0xFFC0CB
    )
    private fun nearestNamed(rgb: Int): String {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        return NAMED.minByOrNull { (_, c) ->
            val dr = r - ((c shr 16) and 0xFF); val dg = g - ((c shr 8) and 0xFF); val db = b - (c and 0xFF)
            dr * dr + dg * dg + db * db
        }!!.first
    }

    private val ANSI = Regex("""\x1b\[[0-9;]*m""")

    private val HEAD_RE = Regex("""\[(\d+)]\s*SSID:\s*(.*)$""")

    private val LBL_BSSID = Regex("""BSSID:\s*([0-9A-Fa-f:]{17})""")
    private val LBL_RSSI = Regex("""RSSI:\s*(-?\d+)""")
    private val LBL_CHAN = Regex("""Channel:\s*(\d+)""")
    private val LBL_BAND = Regex("""Band:\s*([^,]+)""")
    private val LBL_COMPANY = Regex("""(?:Company|Vendor):\s*([^,\r\n]+)""")

    private fun cleanSsid(s: String): String? =
        s.trim().removeSuffix(",").trim().let { if (it.isBlank() || it.equals("(Hidden)", true)) null else it }

    private const val SETTLE_MS = 2_500L
    private const val POLL_MS = 4_000L
    private const val SCAN_MAX_MS = 34_000L
    private const val CLASSIC_SCAN_MS = 6_000L
    private const val LIST_MS = 4_000L
    private const val FRESH_MS = 60_000L

    private class Ap(
        var ssid: String?, var maker: String?, var rssi: Int,
        var channel: Int?, var freq: Int?, var lastSeen: Long
    )

    private class ApRec(
        var ssid: String? = null, var bssid: String = "", var rssi: Int = -100,
        var channel: Int? = null, var band: String? = null, var maker: String? = null
    )

    private fun freqFor(channel: Int?, band: String?): Int? {
        val ch = channel ?: return null
        val b = band?.lowercase() ?: ""
        return when {
            b.contains("2.4") || (b.isBlank() && ch in 1..14) -> if (ch == 14) 2484 else 2412 + (ch - 1) * 5
            b.contains("6")   -> 5950 + ch * 5
            b.contains("5")   || ch in 32..177 -> 5000 + ch * 5
            else -> null
        }
    }

    private fun applyField(c: ApRec, src: String) {
        LBL_BSSID.find(src)?.let { c.bssid = it.groupValues[1].lowercase() }
        LBL_RSSI.find(src)?.let { c.rssi = it.groupValues[1].toInt() }
        LBL_CHAN.find(src)?.let { c.channel = it.groupValues[1].toInt() }
        LBL_BAND.find(src)?.let { c.band = it.groupValues[1].trim().removeSuffix(",").trim() }
        LBL_COMPANY.find(src)?.let {
            val v = it.groupValues[1].trim().removeSuffix(",").trim()
            if (v.isNotBlank() && !v.equals("Unknown", true)) c.maker = v
        }
    }

    private fun parseApList(text: String): List<ApRec> {
        val out = ArrayList<ApRec>()
        var cur: ApRec? = null
        fun flush() { cur?.let { if (it.bssid.isNotBlank()) out.add(it) }; cur = null }
        for (rawLine in text.lines()) {
            val line = ANSI.replace(rawLine, "").trim()
            val head = HEAD_RE.find(line)
            if (head != null) {
                flush()
                val rest = head.groupValues[2]
                if (LBL_BSSID.containsMatchIn(rest)) {

                    val rec = ApRec(ssid = cleanSsid(rest.substringBefore(", BSSID:", rest.substringBefore("BSSID:"))))
                    applyField(rec, rest)
                    if (rec.bssid.isNotBlank()) out.add(rec)
                    cur = null
                } else {

                    cur = ApRec(ssid = cleanSsid(rest))
                }
                continue
            }
            val c = cur ?: continue
            when {
                line.startsWith("BSSID:", true) || line.startsWith("RSSI:", true) ||
                line.startsWith("Channel:", true) || line.startsWith("Band:", true) ||
                line.startsWith("Company:", true) || line.startsWith("Vendor:", true) -> applyField(c, line)
            }
        }
        flush()
        return out
    }

    private fun readFor(port: SerialLink, buf: ByteArray, ms: Long): String {
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + ms
        while (!stop && System.currentTimeMillis() < end) {
            val n = port.read(buf, 300); if (n > 0) sb.append(String(buf, 0, n))
        }
        return sb.toString()
    }

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit,
            onFrame: (List<RawObservation>) -> Unit) {
        if (running) return
        running = true; stop = false
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("Ghost ESP: couldn't open serial"); running = false; return }
        val cp = CdcAcm(conn, device)
        try {
            if (!cp.open(115200)) { onStatus("Ghost ESP: CDC-ACM open failed"); return }
            BeastDiag.log("=== Ghost ESP serial bring-up (native USB 303a:1001) ===")
            driveOverSerial(cp, onStatus, onFrame)
        } catch (e: Exception) {
            BeastDiag.log("Ghost ESP: serial error — ${e.message}")
            onStatus("Ghost ESP: error · ${e.message}")
        } finally {
            cp.close(); running = false
            BeastDiag.log("Ghost ESP: serial source stopped")
        }
    }

    fun driveOverSerial(port: SerialLink, onStatus: (String) -> Unit, onFrame: (List<RawObservation>) -> Unit) {
        stop = false
        val buf = ByteArray(4096)
        try {
            onStatus("Ghost ESP · identifying…")

            val banner = StringBuilder()
            var identified = false
            for (attempt in 0 until 3) {
                if (stop) break
                port.write("stopscan\r\n"); Thread.sleep(150); port.drain()
                port.write("help\r\n")
                banner.append(readFor(port, buf, 2500))
                if (banner.contains("Ghost ESP", true)) { identified = true; break }
            }
            if (!identified) {

                BeastDiag.log("Ghost ESP: banner didn't identify — trying a passive scan anyway (unknown firmware)")
                onStatus("Unknown ESP firmware — trying passive scan…")
            }

            port.drain(); port.write("chipinfo\r\n")
            val infoTxt = ANSI.replace(readFor(port, buf, 1800), "")
            val chip = Regex("""Model:\s*(ESP32\S*)""").find(infoTxt)?.groupValues?.get(1)
            val build = Regex("""Build Config:\s*(\S+)""").find(infoTxt)?.groupValues?.get(1)
            val brand = when {
                !identified -> "Unknown firmware"
                build?.contains("poltergeist", true) == true || banner.contains("poltergeist", true) -> "Poltergeist"
                else -> "Ghost ESP"
            }
            val label = listOfNotNull(brand, chip).joinToString(" · ")

            val poll = chip != null || build?.contains("poltergeist", true) == true || !identified
            BeastDiag.log("Ghost ESP: ${if (identified) "identified as" else "unknown fw — trying as"} $label (build=${build ?: "?"}, ${if (poll) "poll" else "classic stopscan"}) — passive AP-scan mode")
            onStatus("$label · live · scanning…")

            if (ledFx && identified) {
                val hex = String.format("%06X", accentArgb and 0xFFFFFF)
                port.write("settings set accent_color $hex\r\n"); Thread.sleep(40)
                port.write("settings set terminal_color $hex\r\n"); Thread.sleep(40)
                port.write("statusidle set hud\r\n"); Thread.sleep(40)
                port.write("rgbmode ${nearestNamed(accentArgb)}\r\n"); Thread.sleep(40)
                port.write("rgbmode police\r\n"); Thread.sleep(600)
                port.write("rgbmode ${nearestNamed(accentArgb)}\r\n"); Thread.sleep(40)
                port.drain()
            }

            val aps = LinkedHashMap<String, Ap>()
            var cycles = 0L
            var lastEmit = 0L

            fun emitFresh() {
                val t = System.currentTimeMillis()
                val fresh = aps.entries.filter { t - it.value.lastSeen < FRESH_MS }
                if (fresh.isNotEmpty()) onFrame(fresh.map { (bssid, a) ->
                    RawObservation(
                        key = bssid, type = SignalType.WIFI, name = a.ssid,
                        rssi = a.rssi.coerceIn(-120, -20), maker = a.maker,
                        channel = a.channel, frequency = a.freq,
                        viaMonitor = true, wifiClient = false
                    )
                })
                lastEmit = t
            }
            while (!stop) {
                port.drain(); port.write("scanap\r\n")
                var recs: List<ApRec> = emptyList()
                if (poll) {

                    run { val s0 = System.currentTimeMillis() + SETTLE_MS
                          while (!stop && System.currentTimeMillis() < s0) port.read(buf, 300) }
                    val hardEnd = System.currentTimeMillis() + SCAN_MAX_MS
                    while (!stop && System.currentTimeMillis() < hardEnd) {
                        port.drain(); port.write("list -a\r\n")
                        recs = parseApList(readFor(port, buf, POLL_MS))
                        if (recs.isNotEmpty()) break
                        emitFresh()
                    }
                } else {

                    run { val s0 = System.currentTimeMillis() + CLASSIC_SCAN_MS
                          while (!stop && System.currentTimeMillis() < s0) {
                              port.read(buf, 300)
                              if (System.currentTimeMillis() - lastEmit > 3000) emitFresh()
                          } }
                    if (stop) break
                    port.write("stopscan\r\n"); Thread.sleep(250)
                    port.drain(); port.write("list -a\r\n")
                    recs = parseApList(readFor(port, buf, LIST_MS))
                }
                if (stop) break

                val now = System.currentTimeMillis()
                var newFinds = 0
                for (r in recs) {
                    if (r.bssid.isBlank()) continue
                    val freq = freqFor(r.channel, r.band)
                    val e = aps[r.bssid]
                    if (e == null) { aps[r.bssid] = Ap(r.ssid, r.maker, r.rssi, r.channel, freq, now); newFinds++ }
                    else {
                        if (r.ssid != null) e.ssid = r.ssid
                        if (r.maker != null) e.maker = r.maker
                        if (r.channel != null) { e.channel = r.channel; e.freq = freq }
                        e.rssi = r.rssi; e.lastSeen = now
                    }
                }
                cycles++
                emitFresh()
                val liveCount = aps.values.count { now - it.lastSeen < FRESH_MS }

                if (!identified && aps.isEmpty() && cycles >= 2)
                    onStatus("$label — no AP data (tried scanap/list; its commands may differ)")
                else
                    onStatus("$label · live · $liveCount Wi-Fi (sweep $cycles)")
                BeastDiag.log("Ghost ESP: sweep $cycles parsed ${recs.size} APs, $liveCount live, +$newFinds new")

                if (ledFx && newFinds > 0) {
                    port.write("rgbmode police\r\n")
                    val until = System.currentTimeMillis() + 2000
                    while (!stop && System.currentTimeMillis() < until) Thread.sleep(100)
                    port.write("rgbmode ${nearestNamed(accentArgb)}\r\n")
                    port.drain()
                }
            }
        } finally {
            runCatching { port.write("stopscan\r\n") }
            runCatching { port.write("rgbmode off\r\n") }
        }
    }
}
