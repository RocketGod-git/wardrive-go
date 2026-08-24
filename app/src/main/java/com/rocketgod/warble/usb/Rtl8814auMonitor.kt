package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

object Rtl8814auMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false
    @Volatile var captureClients = true
    private var curBand5g = false

    private class Sighting(val mac: String, var isAp: Boolean) {
        var ssid: String? = null; var bssid: String? = null
        var rssi = -128; var channel = 0
        var good = false; var hits = 0; var lastSeen = 0L
        var flock = false
        fun trusted() = good || hits >= 2 || flock
    }
    private val sightings = LinkedHashMap<String, Sighting>()

    private val HOP = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
        36, 40, 44, 48,
        149, 153, 157, 161, 165
    )
    private const val DWELL_MS = 125L
    private val FRESH_MS get() = (UsbBeast.effectiveSweepMs(HOP, DWELL_MS) * 9 / 5).coerceAtLeast(3000L)
    private const val PRUNE_MS = 60_000L
    private const val REPORT_MS = 10_000L
    private const val NOMINAL_RSSI = -60
    private fun freqOf(ch: Int) = if (ch <= 14) 2407 + ch * 5 else 5000 + ch * 5

    private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0
    private const val VREQ = 0x05
    private const val REG_RX_DRVINFO_SZ = 0x060F
    private val RF_BASE = intArrayOf(0x2800, 0x2c00, 0x3800, 0x3c00)

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit,
            onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit) {
        if (running) return
        running = true; stop = false; sightings.clear()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("RTL8814AU: couldn't reopen adapter"); running = false; return }
        var epRx: UsbEndpoint? = null
        try {
            val intf = device.getInterface(0)
            conn.claimInterface(intf, true)
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) { epRx = ep; break }
            }
            val rx = epRx ?: run { BeastDiag.log("RTL8814AU: no bulk-IN endpoint"); onStatus("RTL8814AU: no RX endpoint"); return }
            BeastDiag.log("=== RTL8814AU bring-up (AWUS1900) ===")
            BeastDiag.log("RTL8814AU: RX EP=0x%02x".format(rx.address))

            onStatus("RTL8814AU · powering on…")
            resetMac(conn)
            powerOn(conn)
            w16(conn, Rtl8814auTables.REG_CR, 0x0000)
            w16(conn, Rtl8814auTables.REG_CR, Rtl8814auTables.CR_INIT16)
            val cr0 = r16(conn, Rtl8814auTables.REG_CR)
            BeastDiag.log("RTL8814AU: MAC on, REG_CR=0x%04x".format(cr0))
            if (cr0 == 0xEAEA) { onStatus("RTL8814AU: MAC didn't power on (replug)"); return }
            initLLT(conn); initRQPN(conn)

            onStatus("RTL8814AU · loading MAC/BB/RF…")
            applyMac(conn, Rtl8814auTables.MAC_REG)

            run { val v = r8(conn, 0x02); w8(conn, 0x02, v or 0x04) }
            w8(conn, 0x1002, r8(conn, 0x1002) or 0x03)
            w8(conn, 0x1F, 0x07); w16(conn, 0x20, 0x0707); w8(conn, 0x76, 0x07)
            sleep(1)
            applyBb(conn, Rtl8814auTables.PHY_REG)
            applyBb(conn, Rtl8814auTables.AGC_TAB)
            applyRf(conn, Rtl8814auTables.RADIOA, 0)
            applyRf(conn, Rtl8814auTables.RADIOB, 1)
            applyRf(conn, Rtl8814auTables.RADIOC, 2)
            applyRf(conn, Rtl8814auTables.RADIOD, 3)
            w32(conn, 0x1000, r32(conn, 0x1000) or 0x10000)

            w16(conn, Rtl8814auTables.REG_RXFF_BNDY, Rtl8814auTables.RX_DMA_BOUNDARY)
            w8(conn, 0x10C, r8(conn, 0x10C) or 0x01)
            w8(conn, REG_RX_DRVINFO_SZ, 0x04)
            w32(conn, Rtl8814auTables.REG_RCR, Rtl8814auTables.RCR_MONITOR)
            w16(conn, Rtl8814auTables.REG_RXFLTMAP0, Rtl8814auTables.RXFLTMAP_ALL)
            w16(conn, Rtl8814auTables.REG_RXFLTMAP1, Rtl8814auTables.RXFLTMAP_ALL)
            w16(conn, Rtl8814auTables.REG_RXFLTMAP2, Rtl8814auTables.RXFLTMAP_ALL)
            w16(conn, Rtl8814auTables.REG_CR, r16(conn, Rtl8814auTables.REG_CR) or Rtl8814auTables.CR_MACEN)

            switchBand(conn, HOP[0] > 14); curBand5g = HOP[0] > 14
            channelTune(conn, HOP[0])
            BeastDiag.log("RTL8814AU: RCR=0x%08x BB(0x800)=0x%08x — monitor live dual-band, ch ${HOP[0]}".format(
                r32(conn, Rtl8814auTables.REG_RCR), r32(conn, 0x800)))
            onStatus("RTL8814AU · live · dual-band monitor")

            rxLoop(conn, rx, onFrame, onStatus)
        } catch (e: Exception) {
            BeastDiag.log("RTL8814AU: bring-up error — ${e.message}")
            onStatus("RTL8814AU: error · ${e.message}")
        } finally {
            runCatching { conn.close() }
            running = false
        }
    }

    private fun resetMac(conn: UsbDeviceConnection) {
        runCatching {
            val v = r8(conn, 0x05); w8(conn, 0x05, v or 0x02)
            val end = System.currentTimeMillis() + 100
            while ((r8(conn, 0x05) and 0x02) != 0 && System.currentTimeMillis() < end) sleep(1)
        }
    }

    private fun powerOn(conn: UsbDeviceConnection) {
        w8(conn, 0x10C2, r8(conn, 0x10C2) or 0x02)
        wmask8(conn, 0x05, 0x04, 0x00); poll8(conn, 0x06, 0x02, 0x02)
        wmask8(conn, 0x05, 0x08, 0x00); wmask8(conn, 0xF0, 0x80, 0x00)
        wmask8(conn, 0x81, 0x30, 0x20); wmask8(conn, 0x05, 0x01, 0x01); poll8(conn, 0x05, 0x01, 0x00)
    }

    private fun initLLT(conn: UsbDeviceConnection) {
        w8(conn, Rtl8814auTables.REG_AUTO_LLT, r8(conn, Rtl8814auTables.REG_AUTO_LLT) or 0x01)
        val end = System.currentTimeMillis() + 2000
        while ((r8(conn, Rtl8814auTables.REG_AUTO_LLT) and 0x01) != 0 && System.currentTimeMillis() < end) sleep(2)
    }

    private fun initRQPN(conn: UsbDeviceConnection) {
        val txpkt = 2048 - 8; val pub = txpkt - 0x20 * 4
        for (a in intArrayOf(0x230, 0x234, 0x238, 0x23C)) w32(conn, a, 0x20)
        w32(conn, 0x240, pub); w32(conn, 0x22C, 0x80000000.toInt())
        for (a in intArrayOf(0x424, 0x456, 0x47A, 0x204, 0x206)) w16(conn, a, txpkt and 0xffff)
    }

    private fun applyMac(conn: UsbDeviceConnection, tab: Array<IntArray>) {
        for (e in tab) { if (e[0] == Rtl8814auTables.DELAY) sleep(e[1]) else w8(conn, e[0], e[1] and 0xff) }
    }
    private fun applyBb(conn: UsbDeviceConnection, tab: Array<IntArray>) {
        for (e in tab) { if (e[0] == Rtl8814auTables.DELAY) sleep(e[1]) else w32(conn, e[0], e[1]) }
    }
    private fun applyRf(conn: UsbDeviceConnection, tab: Array<IntArray>, path: Int) {
        for (e in tab) { if (e[0] == Rtl8814auTables.DELAY) sleep(e[1]) else rfWrite(conn, path, e[0], e[1]) }
    }

    private fun setRfe(conn: UsbDeviceConnection, band5g: Boolean) {
        if (band5g) {
            w32(conn, 0xCB0, 0x37173717); w32(conn, 0xEB0, 0x37173717); w32(conn, 0x18B4, 0x37173717)
            w32(conn, 0x1AB4, 0x77177717); bbMask(conn, 0x1ABC, 0x0ff00000, 0x37)
        } else {
            w32(conn, 0xCB0, 0x54775477); w32(conn, 0xEB0, 0x54775477); w32(conn, 0x18B4, 0x54775477)
            w32(conn, 0x1AB4, 0x54775477); bbMask(conn, 0x1ABC, 0x0ff00000, 0x54)
        }
    }

    private fun switchBand(conn: UsbDeviceConnection, band5g: Boolean) {
        w8(conn, 0x1002, r8(conn, 0x1002) and 0x01.inv())
        if (band5g) {
            w8(conn, 0x454, 0x80)
            bbMask(conn, 0xa80, 1 shl 18, 0x1)
            setRfe(conn, true)
            bbMask(conn, 0x80c, 0xf0, 0x0)
            bbMask(conn, 0xa04, 0x0f000000, 0xF)
            bbMask(conn, 0x808, 0x30000000, 0x2)
        } else {
            bbMask(conn, 0x958, 0x1F, 0x0)
            setRfe(conn, false)
            bbMask(conn, 0x80c, 0xf0, 0x2)
            bbMask(conn, 0xa04, 0x0f000000, 0x5)
            bbMask(conn, 0x808, 0x30000000, 0x3)
            w8(conn, 0x454, 0x00)
            bbMask(conn, 0xa80, 1 shl 18, 0x0)
        }
        w8(conn, 0x1002, r8(conn, 0x1002) or 0x01)
    }

    private fun channelTune(conn: UsbDeviceConnection, ch: Int) {
        val band5g = ch > 14
        if (band5g != curBand5g) { switchBand(conn, band5g); curBand5g = band5g }

        val fc = when {
            ch in 36..48 -> 0x494; ch in 50..64 -> 0x453
            ch in 100..116 -> 0x452; ch >= 118 && band5g -> 0x412
            else -> 0x96a
        }
        bbMask(conn, 0x860, 0x1ffe0000, fc)

        val rfMod = when {
            ch in 36..64 -> 0x101; ch in 100..140 -> 0x301; ch > 140 -> 0x501; else -> 0x000
        }
        val rfVal = (ch and 0xff) or (rfMod shl 8)
        val mask = (1 shl 18) or (1 shl 17) or (1 shl 16) or (1 shl 9) or (1 shl 8) or 0xff
        for (p in 0 until 4) rfMask(conn, p, 0x18, mask, rfVal)

        if (band5g) bbMask(conn, 0x958, 0x1F, when { ch in 36..64 -> 1; ch in 100..144 -> 2; else -> 3 })

        when (ch) {
            in 1..11  -> { w32(conn, 0xa20, 0x1a1b0030); w32(conn, 0xa24, 0x090e1317); w32(conn, 0xa28, 0x00000204) }
            in 12..13 -> { w32(conn, 0xa20, 0x1a1b0030); w32(conn, 0xa24, 0x090e1217); w32(conn, 0xa28, 0x00000305) }
        }
    }

    private class RxState {
        var total = 0L; var bufs = 0L; var lastLog = 0L; var hopAt = 0L; var lastEmit = 0L; var hopIdx = 0
        fun start() { val t = System.currentTimeMillis(); lastLog = t; hopAt = t; lastEmit = t }
    }

    private fun rxTick(conn: UsbDeviceConnection, now: Long, st: RxState,
                       onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        if (now - st.lastLog > 3000) {
            val shown = sightings.values.filter { it.trusted() && now - it.lastSeen < REPORT_MS }
            val aps = shown.count { it.isAp }
            BeastDiag.log("RTL8814AU: RX ${st.bufs} bufs / ${st.total} B ch ${HOP[st.hopIdx]} | ${shown.size} live ($aps AP · ${shown.size - aps} client, ${sightings.size} tracked) | ${PmkidCapture.diag()}")
            st.lastLog = now
        }
        if (now - st.lastEmit > 2000) { emitSightings(onFrame); st.lastEmit = now }
        if (now - st.hopAt > UsbBeast.dwell(DWELL_MS)) {
            val ni = UsbBeast.nextHop(st.hopIdx, HOP)
            if (ni != st.hopIdx) { st.hopIdx = ni; runCatching { channelTune(conn, HOP[st.hopIdx]) } }
            val shown = sightings.values.filter { it.trusted() && now - it.lastSeen < REPORT_MS }
            val aps = shown.count { it.isAp }
            MonitorLive.push(onStatus, "RTL8814AU", aps, shown.size - aps, HOP[st.hopIdx])
            st.hopAt = now
        }
    }

    private fun rxLoop(conn: UsbDeviceConnection, epRx: UsbEndpoint,
                       onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val nbuf = 24; val bufSz = 16384
        val buffers = Array(nbuf) { ByteBuffer.allocateDirect(bufSz) }
        val reqs = ArrayList<UsbRequest>(nbuf)
        var initOk = true
        for (i in 0 until nbuf) {
            val r = UsbRequest()
            if (!r.initialize(conn, epRx)) { initOk = false; break }
            r.clientData = i; buffers[i].clear()
            if (!r.queue(buffers[i])) { initOk = false; break }
            reqs.add(r)
        }
        if (!initOk) {
            BeastDiag.log("RTL8814AU: async RX unavailable (${reqs.size}/$nbuf) — synchronous RX")
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
            rxLoopSync(conn, epRx, onFrame, onStatus); return
        }
        BeastDiag.log("RTL8814AU: async RX live — $nbuf buffers in flight")
        val scratch = ByteArray(bufSz)
        val st = RxState().apply { start() }
        try {
            while (!stop) {
                val req = try { conn.requestWait(100L) } catch (e: TimeoutException) { null }
                val now = System.currentTimeMillis()
                if (req != null) {
                    val idx = req.clientData as? Int ?: -1
                    if (idx in 0 until nbuf) {
                        val b = buffers[idx]
                        val n = b.position()
                        val parseLen = if (n in 1..bufSz) { st.total += n; st.bufs++; b.rewind(); b.get(scratch, 0, n); n } else 0
                        b.clear()
                        if (!runCatching { req.queue(b) }.getOrDefault(false)) {
                            runCatching { req.close() }
                            val nr = UsbRequest()
                            if (runCatching { nr.initialize(conn, epRx) }.getOrDefault(false)) {
                                nr.clientData = idx; b.clear()
                                if (runCatching { nr.queue(b) }.getOrDefault(false)) reqs[idx] = nr
                            }
                        }
                        if (parseLen > 0) runCatching { parseRxBuf(scratch, parseLen, HOP[st.hopIdx]) }
                    }
                }
                rxTick(conn, now, st, onFrame, onStatus)
            }
        } finally {
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
        }
        emitSightings(onFrame)
        BeastDiag.log("RTL8814AU: RX loop stopped (${st.total} B)")
    }

    private fun rxLoopSync(conn: UsbDeviceConnection, epRx: UsbEndpoint,
                           onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val buf = ByteArray(16384)
        val st = RxState().apply { start() }
        while (!stop) {
            val n = conn.bulkTransfer(epRx, buf, buf.size, 500)
            val now = System.currentTimeMillis()
            if (n > 0) { st.total += n; st.bufs++; runCatching { parseRxBuf(buf, n, HOP[st.hopIdx]) } }
            rxTick(conn, now, st, onFrame, onStatus)
        }
        emitSightings(onFrame)
        BeastDiag.log("RTL8814AU: RX loop stopped (${st.total} B)")
    }

    private fun parseRxBuf(b: ByteArray, n: Int, ch: Int) {
        val size = 24
        var off = 0
        while (off + size <= n) {
            val d0 = le32(b, off)
            val pkt = d0 and 0x3fff
            if (pkt == 0 || pkt > 4096) break
            val crc = (d0 ushr 14) and 1
            val drv = ((d0 ushr 16) and 0xf) * 8
            val shift = (d0 ushr 24) and 3
            val fs = off + size + drv + shift
            val fe = minOf(n, fs + pkt)
            if (crc == 0 && fe - fs >= 24) {
                val rate = b[off + 12].toInt() and 0x7f
                val rssi = if (drv >= 8 && off + size + drv <= n) rtlRssi(b, off + size, rate) else NOMINAL_RSSI
                parse80211(b, fs, fe, ch, rssi)
            }
            off += (size + drv + shift + pkt + 7) and 7.inv()
        }
    }

    private fun parse80211(b: ByteArray, d: Int, end: Int, ch: Int, rssi: Int) {
        fun u8(i: Int) = b[i].toInt() and 0xff
        val fc = u8(d); val ftype = (fc shr 2) and 3; val sub = (fc shr 4) and 0xf
        val fc1 = u8(d + 1); val toDs = fc1 and 0x01; val fromDs = (fc1 shr 1) and 0x01
        val a1 = d + 4; val a2 = d + 10; val a3 = d + 16
        fun mac(at: Int) = (at until at + 6).joinToString(":") { "%02x".format(u8(it)) }
        fun isReal(m: String) = m != "ff:ff:ff:ff:ff:ff" && m != "00:00:00:00:00:00" &&
            !m.startsWith("01:00:5e") && !m.startsWith("33:33") && !m.startsWith("01:80:c2")
        fun readSsid(from: Int): String? {
            var i = from
            while (i + 2 <= end) {
                val tag = u8(i); val tl = u8(i + 1)
                if (i + 2 + tl > end) break
                if (tag == 0) return String(b, i + 2, tl, Charsets.UTF_8).trim { it <= ' ' }
                i += 2 + tl
            }
            return null
        }
        fun touch(m: String, ap: Boolean): Sighting? {
            if (!isReal(m)) return null
            val s = sightings.getOrPut(m) { Sighting(m, ap) }
            s.hits++; s.good = true
            if (rssi > s.rssi) s.rssi = rssi
            s.channel = ch; s.lastSeen = System.currentTimeMillis()
            return s
        }
        when (ftype) {
            0 -> when (sub) {
                8, 5 -> if (d + 36 <= end) {
                    val ssid = readSsid(d + 36)
                    touch(mac(a3), ap = true)?.let { if (ssid != null && it.ssid == null) it.ssid = ssid }
                }
                4 -> {
                    if (com.rocketgod.warble.classify.NotableDevices.isFlockWildcardProbe(mac(a2), readSsid(d + 24)))
                        touch(mac(a2), ap = true)?.let { it.flock = true }
                    if (captureClients) touch(mac(a2), ap = false)
                }
                0, 2, 10, 11, 12 -> if (captureClients) touch(mac(a2), ap = false)?.let { it.bssid = mac(a3) }
            }
            2 -> {
                PmkidCapture.scan(b, d, end, 0, ch, rssi) { m -> sightings[m]?.ssid }
                if (captureClients) when {
                    toDs == 1 && fromDs == 0 -> touch(mac(a2), ap = false)?.let { it.bssid = mac(a1) }
                    toDs == 0 && fromDs == 1 -> touch(mac(a1), ap = false)?.let { it.bssid = mac(a2) }
                    else -> touch(mac(a2), ap = false)
                }
            }
        }
    }

    private fun rtlRssi(b: ByteArray, di: Int, rate: Int): Int {
        fun u8(i: Int) = b[i].toInt() and 0xff
        return if (rate <= 3) {
            val agc = u8(di + 5); val lna = (agc and 0xE0) shr 5; val vga = agc and 0x1F
            when (lna) {
                7 -> if (vga <= 27) -94 + 2 * (27 - vga) else -94
                6 -> -42 + 2 * (2 - vga); 5 -> -36 + 2 * (7 - vga); 4 -> -30 + 2 * (7 - vga)
                3 -> -18 + 2 * (7 - vga); 2 -> 2 * (5 - vga); 1 -> 14 - 2 * vga; else -> 20 - 2 * vga
            }
        } else ((u8(di + 4) shr 1) and 0x7f) - 110
    }

    private fun emitSightings(onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit) {
        val now = System.currentTimeMillis()
        UsbBeast.seenWindowMs = FRESH_MS
        UsbBeast.defaultDwellMs = DWELL_MS.toInt()
        val batch = sightings.values.filter { it.trusted() && now - it.lastSeen < FRESH_MS }.map { s ->
            com.rocketgod.warble.scan.RawObservation(
                key = s.mac, type = com.rocketgod.warble.model.SignalType.WIFI, name = s.ssid,
                rssi = s.rssi.coerceIn(-120, -20),
                channel = if (s.channel > 0) s.channel else null,
                frequency = if (s.channel > 0) freqOf(s.channel) else null,
                viaMonitor = true, wifiClient = !s.isAp, flockProbe = s.flock
            )
        }
        sightings.entries.removeAll { now - it.value.lastSeen > PRUNE_MS }
        if (batch.isNotEmpty()) onFrame(batch)
    }

    private fun r(conn: UsbDeviceConnection, addr: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        return if (conn.controlTransfer(IN_VENDOR, VREQ, addr, 0, b, len, 500) == len) b else ByteArray(len)
    }
    private fun w(conn: UsbDeviceConnection, addr: Int, data: ByteArray) {
        conn.controlTransfer(OUT_VENDOR, VREQ, addr, 0, data, data.size, 500)
    }
    private fun r8(conn: UsbDeviceConnection, a: Int) = r(conn, a, 1)[0].toInt() and 0xff
    private fun r16(conn: UsbDeviceConnection, a: Int): Int { val b = r(conn, a, 2); return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) }
    private fun r32(conn: UsbDeviceConnection, a: Int) = le32(r(conn, a, 4), 0)
    private fun w8(conn: UsbDeviceConnection, a: Int, v: Int) = w(conn, a, byteArrayOf(v.toByte()))
    private fun w16(conn: UsbDeviceConnection, a: Int, v: Int) = w(conn, a, byteArrayOf(v.toByte(), (v ushr 8).toByte()))
    private fun w32(conn: UsbDeviceConnection, a: Int, v: Int) = w(conn, a, byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
    private fun wmask8(conn: UsbDeviceConnection, a: Int, msk: Int, v: Int) { val cur = r8(conn, a); w8(conn, a, (cur and msk.inv()) or (v and msk)) }
    private fun poll8(conn: UsbDeviceConnection, a: Int, msk: Int, v: Int): Boolean {
        val end = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < end) { if ((r8(conn, a) and msk) == (v and msk)) return true; sleep(1) }
        return false
    }
    private fun bbMask(conn: UsbDeviceConnection, a: Int, mask: Int, v: Int) {
        val cur = r32(conn, a); w32(conn, a, (cur and mask.inv()) or ((v shl sh(mask)) and mask))
    }

    private fun rfWrite(conn: UsbDeviceConnection, path: Int, reg: Int, v: Int) {
        w32(conn, Rtl8814auTables.LSSI[path], (((reg and 0xff) shl 20) or (v and 0xfffff)) and 0x0fffffff)
    }
    private fun rfRead(conn: UsbDeviceConnection, path: Int, reg: Int) = r32(conn, RF_BASE[path] + (reg and 0xff) * 4) and 0xfffff
    private fun rfMask(conn: UsbDeviceConnection, path: Int, reg: Int, mask: Int, v: Int) {
        val cur = rfRead(conn, path, reg); rfWrite(conn, path, reg, (cur and mask.inv()) or ((v shl sh(mask)) and mask))
    }

    private fun sh(mask: Int) = Integer.numberOfTrailingZeros(mask)
    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) or ((b[i+2].toInt() and 0xff) shl 16) or ((b[i+3].toInt() and 0xff) shl 24)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
