package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.scan.RawObservation

object FreeWili {
    @Volatile var running = false; private set
    @Volatile private var stop = false

    fun abort() { stop = true }

    private val ANSI = Regex("""\x1b\[[0-9;]*m""")
    private val MAC = Regex("""^[0-9a-fA-F:]{17}$""")
    private const val FRESH_MS = 60_000L
    private const val SCAN_READ_MS = 9_000L

    private class Ap(var ssid: String?, var rssi: Int, var channel: Int?, var freq: Int?, var lastSeen: Long)
    private class ApRec(val bssid: String, val ssid: String?, val rssi: Int, val channel: Int?)

    private fun freqFor(ch: Int?): Int? {
        val c = ch ?: return null
        return when {
            c in 1..13 -> 2412 + (c - 1) * 5
            c == 14 -> 2484
            c in 32..177 -> 5000 + c * 5
            else -> null
        }
    }

    private fun parseScanLine(raw: String): ApRec? {
        val line = ANSI.replace(raw, "").trim()
        val i = line.indexOf("*wifiscan"); if (i < 0) return null
        var body = line.substring(i)
        body = body.trimStart('*').removePrefix("wifiscan").trim()
        body = body.trimEnd(']', ')').trim()
        val t = body.split(Regex("""\s+""")).filter { it.isNotEmpty() }

        if (t.size < 8) return null
        val bssid = t[2].lowercase()
        if (!MAC.matches(bssid)) return null
        val rssi = t[3].toIntOrNull() ?: return null
        val channel = t[4].toIntOrNull()
        val ssid = if (t.size >= 9) t.subList(7, t.size - 1).joinToString(" ").trim().ifBlank { null } else null
        return ApRec(bssid, ssid, rssi, channel)
    }

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit, onFrame: (List<RawObservation>) -> Unit) {
        if (running) return
        running = true; stop = false
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("FreeWili: couldn't open serial"); running = false; return }
        val cdc = CdcAcm(conn, device)
        try {
            onStatus("FreeWili · connecting…")

            if (!cdc.open(115200, assertDtr = false)) { onStatus("FreeWili: CDC-ACM open failed"); return }
            BeastDiag.log("=== FreeWili console bring-up (093c:205a, no-DTR) ===")
            driveConsole(cdc, onStatus, onFrame)
        } catch (e: Exception) {
            BeastDiag.log("FreeWili: serial error — ${e.message}")
            onStatus("FreeWili: error · ${e.message}")
        } finally {
            cdc.close(); running = false
            BeastDiag.log("FreeWili: serial source stopped")
        }
    }

    private fun readFor(port: SerialLink, buf: ByteArray, ms: Long): String {
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + ms
        while (!stop && System.currentTimeMillis() < end) {
            val n = port.read(buf, 300); if (n > 0) sb.append(String(buf, 0, n))
        }
        return sb.toString()
    }

    private fun key(port: SerialLink, k: String) { port.write("$k\r") }

    private fun driveConsole(port: SerialLink, onStatus: (String) -> Unit, onFrame: (List<RawObservation>) -> Unit) {
        stop = false
        val buf = ByteArray(4096)

        readFor(port, buf, 3000)

        var inWifi = false
        for (attempt in 0 until 2) {
            if (stop) break
            port.drain(); key(port, "w"); readFor(port, buf, 1500)
            key(port, "w"); val menu = ANSI.replace(readFor(port, buf, 1800), "")
            if (menu.contains("Wifi Functions", true) || menu.contains("Scan for Access Points", true)) { inWifi = true; break }
        }
        if (!inWifi) BeastDiag.log("FreeWili: didn't confirm WiFi menu — scanning anyway")
        onStatus("FreeWili · live · scanning…")

        val aps = LinkedHashMap<String, Ap>()
        var cycles = 0L
        while (!stop) {
            port.drain(); key(port, "s")
            val text = readFor(port, buf, SCAN_READ_MS)
            val now = System.currentTimeMillis()
            var newFinds = 0; var parsed = 0
            for (ln in text.lines()) {
                val r = parseScanLine(ln) ?: continue
                parsed++
                val freq = freqFor(r.channel)
                val e = aps[r.bssid]
                if (e == null) { aps[r.bssid] = Ap(r.ssid, r.rssi, r.channel, freq, now); newFinds++ }
                else {
                    if (r.ssid != null) e.ssid = r.ssid
                    if (r.channel != null) { e.channel = r.channel; e.freq = freq }
                    e.rssi = r.rssi; e.lastSeen = now
                }
            }

            val fresh = aps.entries.filter { now - it.value.lastSeen < FRESH_MS }
            if (fresh.isNotEmpty()) onFrame(fresh.map { (bssid, a) ->
                RawObservation(
                    key = bssid, type = SignalType.WIFI, name = a.ssid,
                    rssi = a.rssi.coerceIn(-120, -20), channel = a.channel, frequency = a.freq,
                    viaMonitor = true, wifiClient = false
                )
            })
            cycles++
            val live = aps.values.count { now - it.lastSeen < FRESH_MS }
            onStatus("FreeWili · live · $live Wi-Fi (sweep $cycles)")
            BeastDiag.log("FreeWili: sweep $cycles parsed $parsed *wifiscan, $live live, +$newFinds new")
        }
        runCatching { key(port, "q") }
    }
}
