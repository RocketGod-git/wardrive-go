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

object Rt3070Monitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false
    @Volatile var captureClients = true

    private class Sighting(val mac: String, var isAp: Boolean) {
        var ssid: String? = null; var bssid: String? = null
        var rssi = -128; var channel = 0
        var good = false; var hits = 0; var lastSeen = 0L
        var flock = false
        fun trusted() = good || hits >= 2 || flock
    }
    private val sightings = LinkedHashMap<String, Sighting>()

    private val FRESH_MS get() = (UsbBeast.effectiveSweepMs(HOP, DWELL_MS) * 9 / 5).coerceAtLeast(3000L)
    private const val PRUNE_MS = 60_000L
    private const val REPORT_MS = 10_000L

    private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0
    private const val MULTI_WRITE = 0x06; private const val MULTI_READ = 0x07
    private const val EEPROM_READ = 0x09; private const val DEVICE_MODE = 0x01
    private const val USB_MODE_RESET = 1

    private const val WPDMA_GLO_CFG = 0x0208; private const val USB_DMA_CFG = 0x02a0
    private const val PBF_SYS_CTRL = 0x0400; private const val PBF_CFG = 0x0408; private const val PBF_MAX_PCNT = 0x040c
    private const val RF_CSR_CFG = 0x0500; private const val LDO_CFG0 = 0x05d4; private const val EFUSE_CTRL = 0x0580
    private const val MAC_CSR0 = 0x1000; private const val MAC_SYS_CTRL = 0x1004; private const val MAX_LEN_CFG = 0x1018
    private const val BBP_CSR_CFG = 0x101c; private const val LED_CFG = 0x102c
    private const val XIFS_TIME_CFG = 0x1100; private const val BKOFF_SLOT_CFG = 0x1104; private const val CH_TIME_CFG = 0x110c
    private const val BCN_TIME_CFG = 0x1114; private const val MAC_STATUS_CFG = 0x1200; private const val PWR_PIN_CFG = 0x1204
    private const val AUTOWAKEUP_CFG = 0x1208; private const val TX_PIN_CFG = 0x1328; private const val TX_BAND_CFG = 0x132c
    private const val TX_SW_CFG0 = 0x1330; private const val TX_SW_CFG1 = 0x1334; private const val TX_SW_CFG2 = 0x1338
    private const val TXOP_CTRL_CFG = 0x1340; private const val TX_RTS_CFG = 0x1344; private const val TX_TIMEOUT_CFG = 0x1348
    private const val TX_RTY_CFG = 0x134c; private const val TX_LINK_CFG = 0x1350
    private const val HT_FBK_CFG0 = 0x1354; private const val HT_FBK_CFG1 = 0x1358; private const val LG_FBK_CFG0 = 0x135c
    private const val LG_FBK_CFG1 = 0x1360; private const val CCK_PROT_CFG = 0x1364; private const val OFDM_PROT_CFG = 0x1368
    private const val MM20_PROT_CFG = 0x136c; private const val MM40_PROT_CFG = 0x1370; private const val GF20_PROT_CFG = 0x1374
    private const val GF40_PROT_CFG = 0x1378; private const val EXP_ACK_TIME = 0x1380
    private const val RX_FILTR_CFG = 0x1400; private const val AUTO_RSP_CFG = 0x1404
    private const val LEGACY_BASIC_RATE = 0x1408; private const val HT_BASIC_RATE = 0x140c; private const val TXOP_HLDR_ET = 0x1608
    private const val US_CYC_CNT = 0x02a4
    private const val CH_IDLE_STA = 0x1130; private const val CH_BUSY_STA = 0x1134; private const val CH_BUSY_STA_SEC = 0x1138

    private const val REV_RT3070F = 0x0201

    private val RF_N = intArrayOf(241, 241, 242, 242, 243, 243, 244, 244, 245, 245, 246, 246, 247)
    private val RF_K = intArrayOf(2, 7, 2, 7, 2, 7, 2, 7, 2, 7, 2, 7, 2)

    private val HOP = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
    private const val DWELL_MS = 350L
    private fun freqOf(ch: Int) = 2407 + ch * 5

    private var calBw20 = 0x08
    private var freqOffset = 0
    private var lnaGain = 0
    private var rssiOff0 = 0
    private var revLtF = false

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit,
            onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit) {
        if (running) return
        running = true; stop = false; sightings.clear()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("RT3070: couldn't reopen adapter"); running = false; return }
        var epRx: UsbEndpoint? = null
        try {
            val intf = device.getInterface(0)
            conn.claimInterface(intf, true)
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) { epRx = ep; break }
            }
            val rx = epRx ?: run { BeastDiag.log("RT3070: no bulk-IN endpoint"); onStatus("RT3070: no RX endpoint"); return }
            BeastDiag.log("=== RT3070 bring-up (rt2800usb) ===")
            BeastDiag.log("RT3070: RX EP=0x%02x".format(rx.address))

            val rev = regRd(conn, MAC_CSR0) and 0xffff
            revLtF = rev < REV_RT3070F
            BeastDiag.log("RT3070: MAC_CSR0 rev=0x%04x (%s)".format(rev, if (revLtF) "<F" else ">=F"))

            onStatus("RT3070 · reading calibration…")
            readEeprom(conn)
            BeastDiag.log("RT3070: freq_off=$freqOffset lna_gain=$lnaGain rssi_off0=$rssiOff0")

            onStatus("RT3070 · initializing MAC…")
            initRegisters(conn)
            waitBbpRfReady(conn)
            onStatus("RT3070 · loading BBP/RF registers…")
            initBbp(conn)
            initRfcsr(conn)
            BeastDiag.log("RT3070: calibration_bw20=0x%02x".format(calBw20))

            configChannel(conn, HOP[0])
            onStatus("RT3070 · enabling RX…")
            enableRx(conn)
            BeastDiag.log("RT3070: SYS_CTRL=0x%08x WPDMA=0x%08x RXFILTR=0x%08x — monitor live ch ${HOP[0]}".format(
                regRd(conn, MAC_SYS_CTRL), regRd(conn, WPDMA_GLO_CFG), regRd(conn, RX_FILTR_CFG)))
            onStatus("RT3070 · live · 2.4 GHz monitor")

            rxLoop(conn, rx, onFrame, onStatus)
        } catch (e: Exception) {
            BeastDiag.log("RT3070: bring-up error — ${e.message}")
            onStatus("RT3070: error · ${e.message}")
        } finally {
            runCatching { conn.close() }
            running = false
        }
    }

    private fun usbReset(conn: UsbDeviceConnection) {
        waitCsrReady(conn)
        regClear(conn, PBF_SYS_CTRL, 0x2000)
        regWr(conn, MAC_SYS_CTRL, 0x3)
        conn.controlTransfer(OUT_VENDOR, DEVICE_MODE, USB_MODE_RESET, 0, null, 0, 1000)
        regWr(conn, MAC_SYS_CTRL, 0x0)
    }

    private fun initRegisters(conn: UsbDeviceConnection) {
        regClear(conn, WPDMA_GLO_CFG, 0x5)
        usbReset(conn)
        regWr(conn, LEGACY_BASIC_RATE, 0x0000013f); regWr(conn, HT_BASIC_RATE, 0x00008003)
        regWr(conn, MAC_SYS_CTRL, 0)
        regRmw(conn, BCN_TIME_CFG, 0x0000ffff, 1600)
        regWr(conn, RX_FILTR_CFG, 0x00017f97)
        run { var r = regRd(conn, BKOFF_SLOT_CFG); r = setf(r, 0xff, 9); r = setf(r, 0xf00, 2); regWr(conn, BKOFF_SLOT_CFG, r) }
        regWr(conn, TX_SW_CFG0, 0x00000400)
        if (revLtF) { regWr(conn, TX_SW_CFG1, 0x00000000); regWr(conn, TX_SW_CFG2, 0x0000002c) }
        else { regWr(conn, TX_SW_CFG1, 0x00080606); regWr(conn, TX_SW_CFG2, 0x00000000) }
        regWr(conn, TX_LINK_CFG, 0x00001020)
        regWr(conn, TX_TIMEOUT_CFG, 0x000a2090)
        run { var r = regRd(conn, MAX_LEN_CFG); r = setf(r, 0x0fff, 3839); r = setf(r, 0x3000, 3); r = setf(r, 0xfc000, 10); r = setf(r, 0xf00000, 10); regWr(conn, MAX_LEN_CFG, r) }
        regWr(conn, LED_CFG, 0x7f031e46)
        regWr(conn, PBF_MAX_PCNT, 0x1f3fbf9f)
        regWr(conn, TX_RTY_CFG, 0x47d01f0f)
        regWr(conn, AUTO_RSP_CFG, 0x00000013)
        regWr(conn, CCK_PROT_CFG, 0x05740003); regWr(conn, OFDM_PROT_CFG, 0x05740003)
        regWr(conn, MM20_PROT_CFG, 0x03f44084); regWr(conn, MM40_PROT_CFG, 0x03f54084)
        regWr(conn, GF20_PROT_CFG, 0x03f44084); regWr(conn, GF40_PROT_CFG, 0x03f54084)
        regWr(conn, PBF_CFG, 0x00f40006)
        run { var r = regRd(conn, WPDMA_GLO_CFG); r = r and 0x00ff000f.inv(); r = setf(r, 0x03000000, 3); regWr(conn, WPDMA_GLO_CFG, r) }
        regWr(conn, TXOP_CTRL_CFG, 0x0000583f)
        regWr(conn, TXOP_HLDR_ET, 0x00000002)
        regWr(conn, TX_RTS_CFG, 0x00092b20)
        regWr(conn, EXP_ACK_TIME, 0x002400ca)
        regWr(conn, XIFS_TIME_CFG, 0x33a41010)
        regWr(conn, PWR_PIN_CFG, 0x00000003)
        regRmw(conn, US_CYC_CNT, 0xff, 30)
        regWr(conn, HT_FBK_CFG0, 0x65432100); regWr(conn, HT_FBK_CFG1, 0xedcba980.toInt())
        regWr(conn, LG_FBK_CFG0, 0xedcba988.toInt()); regWr(conn, LG_FBK_CFG1, 0x00002100)
    }

    private fun initBbp(conn: UsbDeviceConnection) {
        val early = arrayOf(65 to 0x2C, 66 to 0x38, 68 to 0x0B, 69 to 0x12, 70 to 0x0a, 73 to 0x10, 81 to 0x37,
            82 to 0x62, 83 to 0x6A, 84 to 0x99, 86 to 0x00, 91 to 0x04, 92 to 0x00, 103 to 0x00, 105 to 0x05, 106 to 0x35)
        for ((r, v) in early) bbpWr(conn, r, v)
        val tab = arrayOf(65 to 0x2c, 66 to 0x38, 69 to 0x12, 73 to 0x10, 70 to 0x0a, 79 to 0x13, 80 to 0x05, 81 to 0x33,
            82 to 0x62, 83 to 0x6a, 84 to 0x99, 86 to 0x00, 91 to 0x04, 92 to 0x00,
            103 to (if (revLtF) 0x00 else 0xc0), 105 to 0x05, 106 to 0x35)
        for ((r, v) in tab) bbpWr(conn, r, v)
    }

    private fun initRfcsr(conn: UsbDeviceConnection) {
        rfInitCal(conn, 30)
        val tab = arrayOf(4 to 0x40, 5 to 0x03, 6 to 0x02, 7 to 0x60, 9 to 0x0f, 10 to 0x41, 11 to 0x21, 12 to 0x7b,
            14 to 0x90, 15 to 0x58, 16 to 0xb3, 17 to 0x92, 18 to 0x2c, 19 to 0x02, 20 to 0xba, 21 to 0xdb,
            24 to 0x16, 25 to 0x03, 29 to 0x1f)
        for ((r, v) in tab) rfWr(conn, r, v)
        if (revLtF) { var r = regRd(conn, LDO_CFG0); r = setf(r, 0x03000000, 1); r = setf(r, 0x1c000000, 3); regWr(conn, LDO_CFG0, r) }
        calBw20 = rxFilterCal(conn)
        if (revLtF) rfWr(conn, 27, 0x03)
        normalModeSetup(conn)
    }

    private fun rxFilterCal(conn: UsbDeviceConnection): Int {
        var rf24 = 0x07
        rfWr(conn, 24, rf24)
        bbpWr(conn, 4, setf(bbpRd(conn, 4), 0x18, 0))
        rfWr(conn, 31, setf(rfRd(conn, 31), 0x20, 0))
        rfWr(conn, 22, setf(rfRd(conn, 22), 0x01, 1))
        bbpWr(conn, 24, 0)
        var passband = 0
        for (i in 0 until 100) { bbpWr(conn, 25, 0x90); sleep(1); passband = bbpRd(conn, 55); if (passband != 0) break }
        bbpWr(conn, 24, 0x06)
        var overtuned = 0
        for (i in 0 until 100) {
            bbpWr(conn, 25, 0x90); sleep(1)
            val stopband = bbpRd(conn, 55)
            if (passband - stopband <= 0x16) { rf24++; if (passband - stopband == 0x16) overtuned++ } else break
            rfWr(conn, 24, rf24)
        }
        if (overtuned != 0) rf24--
        rfWr(conn, 24, rf24)

        bbpWr(conn, 24, 0)
        rfWr(conn, 22, setf(rfRd(conn, 22), 0x01, 0))
        bbpWr(conn, 4, setf(bbpRd(conn, 4), 0x18, 0))
        return rf24
    }

    private fun normalModeSetup(conn: UsbDeviceConnection) {
        var rf = rfRd(conn, 17); rf = setf(rf, 0x08, 0); rf = setf(rf, 0x20, 1); rfWr(conn, 17, rf)
        rf = rfRd(conn, 27); rf = setf(rf, 0x03, if (revLtF) 3 else 0); rf = setf(rf, 0x04, 0); rf = setf(rf, 0x30, 0); rf = setf(rf, 0x40, 0); rfWr(conn, 27, rf)
    }

    private fun rfInitCal(conn: UsbDeviceConnection, reg: Int) {
        val v = rfRd(conn, reg); rfWr(conn, reg, v or 0x80); sleep(1); rfWr(conn, reg, v and 0x80.inv())
    }

    private fun configChannel(conn: UsbDeviceConnection, ch: Int) {
        val idx = ch - 1
        rfWr(conn, 2, RF_N[idx])
        rfWr(conn, 3, setf(rfRd(conn, 3), 0x0f, RF_K[idx]))
        rfWr(conn, 6, setf(rfRd(conn, 6), 0x03, 2))
        rfWr(conn, 12, setf(rfRd(conn, 12), 0x1f, 0))
        rfWr(conn, 13, setf(rfRd(conn, 13), 0x1f, 0))
        var rf = rfRd(conn, 1)
        rf = setf(rf, 0x04, 0); rf = setf(rf, 0x10, 1); rf = setf(rf, 0x40, 1)
        rf = setf(rf, 0x08, 0); rf = setf(rf, 0x20, 1); rf = setf(rf, 0x80, 1)
        rfWr(conn, 1, rf)
        rfWr(conn, 23, setf(rfRd(conn, 23), 0x7f, freqOffset and 0x7f))
        rfWr(conn, 24, setf(rfRd(conn, 24), 0x7f, calBw20))
        rfWr(conn, 31, setf(rfRd(conn, 31), 0x7f, calBw20))
        rfWr(conn, 7, setf(rfRd(conn, 7), 0x01, 1))
        rf = rfRd(conn, 30); rfWr(conn, 30, rf or 0x80); sleep(1); rfWr(conn, 30, rf and 0x80.inv())

        bbpWr(conn, 82, 0x84); bbpWr(conn, 75, 0x50)
        run { var r = regRd(conn, TX_BAND_CFG); r = setf(r, 0x01, 0); r = setf(r, 0x02, 0); r = setf(r, 0x04, 1); regWr(conn, TX_BAND_CFG, r) }

        regWr(conn, TX_PIN_CFG, 0x02 or 0x100 or 0x200 or 0x10000 or 0x40000)
        bbpWr(conn, 4, setf(bbpRd(conn, 4), 0x18, 0))
        bbpWr(conn, 3, setf(bbpRd(conn, 3), 0x20, 0))
        sleep(1)
        regRd(conn, CH_IDLE_STA); regRd(conn, CH_BUSY_STA); regRd(conn, CH_BUSY_STA_SEC)
    }

    private fun enableRx(conn: UsbDeviceConnection) {
        waitWpdma(conn)
        regWr(conn, USB_DMA_CFG, 0x00800000 or 0x00400000 or 0x80)
        regWr(conn, MAC_SYS_CTRL, 0x04)
        sleep(50)
        regSet(conn, WPDMA_GLO_CFG, 0x01 or 0x04 or 0x40)
        regWr(conn, MAC_SYS_CTRL, 0x04 or 0x08)
        regWr(conn, RX_FILTR_CFG, 0x00000003)
        regWr(conn, CH_TIME_CFG, 0x0000001f)
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
            BeastDiag.log("RT3070: RX ${st.bufs} bufs / ${st.total} B ch ${HOP[st.hopIdx]} | ${shown.size} live ($aps AP · ${shown.size - aps} client, ${sightings.size} tracked) | ${PmkidCapture.diag()}")
            st.lastLog = now
        }
        if (now - st.lastEmit > 2000) { emitSightings(onFrame); st.lastEmit = now }
        if (now - st.hopAt > UsbBeast.dwell(DWELL_MS)) {
            val ni = UsbBeast.nextHop(st.hopIdx, HOP)
            if (ni != st.hopIdx) { st.hopIdx = ni; runCatching { configChannel(conn, HOP[st.hopIdx]) } }
            val shown = sightings.values.filter { it.trusted() && now - it.lastSeen < REPORT_MS }
            val aps = shown.count { it.isAp }
            MonitorLive.push(onStatus, "RT3070", aps, shown.size - aps, HOP[st.hopIdx])
            st.hopAt = now
        }
    }

    private fun rxLoop(conn: UsbDeviceConnection, epRx: UsbEndpoint,
                       onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val nbuf = 24; val bufSz = 4096
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
            BeastDiag.log("RT3070: async RX unavailable (${reqs.size}/$nbuf) — synchronous RX")
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
            rxLoopSync(conn, epRx, onFrame, onStatus); return
        }
        BeastDiag.log("RT3070: async RX live — $nbuf buffers in flight")
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
                        val parseLen = if (n in 1..bufSz) {
                            st.total += n; st.bufs++
                            b.rewind(); b.get(scratch, 0, n); n
                        } else 0

                        b.clear()
                        if (!runCatching { req.queue(b) }.getOrDefault(false)) {
                            runCatching { req.close() }
                            val nr = UsbRequest()
                            if (runCatching { nr.initialize(conn, epRx) }.getOrDefault(false)) {
                                nr.clientData = idx; b.clear()
                                if (runCatching { nr.queue(b) }.getOrDefault(false)) reqs[idx] = nr
                            }
                        }
                        if (parseLen > 0) runCatching { parseRxFrame(scratch, parseLen, HOP[st.hopIdx]) }
                    }
                }
                rxTick(conn, now, st, onFrame, onStatus)
            }
        } finally {
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
        }
        emitSightings(onFrame)
        BeastDiag.log("RT3070: RX loop stopped (${st.total} B)")
    }

    private fun rxLoopSync(conn: UsbDeviceConnection, epRx: UsbEndpoint,
                           onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val buf = ByteArray(4096)
        val st = RxState().apply { start() }
        while (!stop) {
            val n = conn.bulkTransfer(epRx, buf, buf.size, 500)
            val now = System.currentTimeMillis()
            if (n > 0) { st.total += n; st.bufs++; runCatching { parseRxFrame(buf, n, HOP[st.hopIdx]) } }
            rxTick(conn, now, st, onFrame, onStatus)
        }
        emitSightings(onFrame)
        BeastDiag.log("RT3070: RX loop stopped (${st.total} B)")
    }

    private fun parseRxFrame(b: ByteArray, n: Int, ch: Int) {
        fun u8(i: Int) = b[i].toInt() and 0xff
        if (n < 4 + 16 + 24) return
        val rxPktLen = le32(b, 0) and 0xffff
        val rxwi = 4
        val mpduLen = (le32(b, rxwi) ushr 16) and 0xfff
        val rssiRaw = b[rxwi + 8].toInt()
        val d = rxwi + 16
        val end = minOf(n, d + mpduLen)
        if (mpduLen < 24 || d + 24 > end) return

        val rxd = if (4 + rxPktLen + 4 <= n) le32(b, 4 + rxPktLen) else 0
        val crc = (rxd and 0x100) != 0

        val pad = if (rxd and 0x4000 != 0) 2 else 0
        val good = !crc
        val rssi = if (rssiRaw != 0) -12 - rssiOff0 - lnaGain - rssiRaw else -128

        fun mac(at: Int) = (at until at + 6).joinToString(":") { "%02x".format(u8(it)) }
        fun isReal(m: String) = m != "ff:ff:ff:ff:ff:ff" && m != "00:00:00:00:00:00" &&
            !m.startsWith("01:00:5e") && !m.startsWith("33:33") && !m.startsWith("01:80:c2")
        fun readSsid(from: Int): String? {
            var i = from
            while (i + 2 <= end) {
                val tag = u8(i); val tl = u8(i + 1)
                if (i + 2 + tl > end) break
                if (tag == 0) return String(b, i + 2, tl, Charsets.UTF_8).trim()
                i += 2 + tl
            }
            return null
        }
        fun touch(m: String, ap: Boolean, goodFrame: Boolean): Sighting? {
            if (!isReal(m)) return null
            val s = sightings.getOrPut(m) { Sighting(m, ap) }
            s.hits++; if (goodFrame) s.good = true
            if (rssi > s.rssi) s.rssi = rssi
            s.channel = ch; s.lastSeen = System.currentTimeMillis()
            return s
        }

        val fc = u8(d); val ftype = (fc shr 2) and 3; val sub = (fc shr 4) and 0xF
        val fc1 = u8(d + 1); val toDs = fc1 and 0x01; val fromDs = (fc1 shr 1) and 0x01
        val a1 = d + 4; val a2 = d + 10; val a3 = d + 16
        when (ftype) {
            0 -> when (sub) {
                8, 5 -> {
                    val ssid = readSsid(d + 36)
                    if (good || cleanSsid(ssid)) touch(mac(a3), ap = true, goodFrame = good)?.let {
                        if (ssid != null && it.ssid == null) it.ssid = ssid
                    }
                }
                4 -> {

                    if (good && com.rocketgod.warble.classify.NotableDevices.isFlockWildcardProbe(mac(a2), readSsid(d + 24)))
                        touch(mac(a2), ap = true, goodFrame = true)?.let { it.flock = true }
                    if (good && captureClients) touch(mac(a2), ap = false, goodFrame = true)
                }
                0, 1, 2, 3, 10, 11, 12 -> if (good && captureClients) touch(mac(a2), ap = false, goodFrame = true)?.let { it.bssid = mac(a3) }
            }
            2 -> {
                if (good) PmkidCapture.scan(b, d, end, pad, ch, rssi) { m -> sightings[m]?.ssid }
                if (good && captureClients) when {
                    toDs == 1 && fromDs == 0 -> touch(mac(a2), ap = false, goodFrame = true)?.let { it.bssid = mac(a1) }
                    toDs == 0 && fromDs == 1 -> touch(mac(a1), ap = false, goodFrame = true)?.let { it.bssid = mac(a2) }
                    toDs == 0 && fromDs == 0 -> { touch(mac(a2), ap = false, goodFrame = true)?.let { it.bssid = mac(a3) }; touch(mac(a1), ap = false, goodFrame = true) }
                    else -> touch(mac(a2), ap = false, goodFrame = true)
                }
            }
            1 -> if (good && captureClients && (sub == 11 || sub == 10)) touch(mac(a2), ap = false, goodFrame = true)
        }
    }

    private fun cleanSsid(s: String?): Boolean {
        if (s.isNullOrEmpty() || s.length > 32) return false
        return s.none { it.code == 0xFFFD || it.isISOControl() }
    }

    private fun emitSightings(onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit) {
        val now = System.currentTimeMillis()
        UsbBeast.seenWindowMs = FRESH_MS
        UsbBeast.defaultDwellMs = DWELL_MS.toInt()
        val batch = sightings.values.filter { it.trusted() && now - it.lastSeen < FRESH_MS }.map { s ->
            com.rocketgod.warble.scan.RawObservation(
                key = s.mac,
                type = com.rocketgod.warble.model.SignalType.WIFI,
                name = s.ssid,
                rssi = s.rssi.coerceIn(-120, -20),
                channel = if (s.channel > 0) s.channel else null,
                frequency = if (s.channel > 0) freqOf(s.channel) else null,
                viaMonitor = true,
                wifiClient = !s.isAp,
                flockProbe = s.flock
            )
        }
        sightings.entries.removeAll { now - it.value.lastSeen > PRUNE_MS }
        if (batch.isNotEmpty()) onFrame(batch)
    }

    private fun readEeprom(conn: UsbDeviceConnection) {
        val ee = ByteArray(512)
        val efuse = (regRd(conn, EFUSE_CTRL) and 0x80000000.toInt()) != 0
        if (efuse) {
            var i = 0
            while (i < 256) {
                var reg = regRd(conn, EFUSE_CTRL)
                reg = setf(reg, 0x03ff0000, i)
                reg = reg and 0xc0000000.toInt().inv()
                reg = reg or 0x40000000
                regWr(conn, EFUSE_CTRL, reg)
                val end = System.currentTimeMillis() + 50
                while ((regRd(conn, EFUSE_CTRL) and 0x40000000) != 0 && System.currentTimeMillis() < end) sleep(1)
                val data = intArrayOf(0x059c, 0x0598, 0x0594, 0x0590)
                for (k in 0 until 4) {
                    val v = regRd(conn, data[k]); val o = (i + k * 2) * 2
                    if (o + 4 <= 512) putLe32(ee, o, v)
                }
                i += 8
            }
        } else {
            val n = conn.controlTransfer(IN_VENDOR, EEPROM_READ, 0, 0, ee, ee.size, 2000)
            if (n <= 0) BeastDiag.log("RT3070: EEPROM read returned $n — using defaults")
        }
        fun ew(word: Int) = (ee[word * 2].toInt() and 0xff) or ((ee[word * 2 + 1].toInt() and 0xff) shl 8)
        fun s8(v: Int) = if (v and 0x80 != 0) v - 256 else v

        freqOffset = ew(0x1d) and 0xff
        val lg = ew(0x22) and 0xff
        lnaGain = if (lg == 0xff) 0 else s8(lg)
        val ro = ew(0x23) and 0xff
        rssiOff0 = if (ro == 0xff) 0 else s8(ro)
        val mac = (0 until 6).joinToString(":") { "%02x".format(ee[4 + it].toInt() and 0xff) }
        BeastDiag.log("RT3070: EEPROM(${if (efuse) "efuse" else "93c46"}) MAC $mac")
    }

    private fun regWr(conn: UsbDeviceConnection, addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, 0, addr, b, 4, 1000)
    }
    private fun regRd(conn: UsbDeviceConnection, addr: Int): Int {
        val b = ByteArray(4)
        return if (conn.controlTransfer(IN_VENDOR, MULTI_READ, 0, addr, b, 4, 1000) == 4) le32(b, 0) else 0
    }
    private fun regSet(conn: UsbDeviceConnection, addr: Int, bits: Int) = regWr(conn, addr, regRd(conn, addr) or bits)
    private fun regClear(conn: UsbDeviceConnection, addr: Int, bits: Int) = regWr(conn, addr, regRd(conn, addr) and bits.inv())
    private fun regRmw(conn: UsbDeviceConnection, addr: Int, mask: Int, v: Int) = regWr(conn, addr, setf(regRd(conn, addr), mask, v))

    private fun bbpBusy(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 50
        do { if (regRd(conn, BBP_CSR_CFG) and 0x20000 == 0) return true; sleep(1) } while (System.currentTimeMillis() < end)
        return false
    }
    private fun bbpWr(conn: UsbDeviceConnection, reg: Int, v: Int) {
        bbpBusy(conn)
        regWr(conn, BBP_CSR_CFG, (v and 0xff) or ((reg and 0xff) shl 8) or 0x20000 or 0x80000)
    }
    private fun bbpRd(conn: UsbDeviceConnection, reg: Int): Int {
        bbpBusy(conn)
        regWr(conn, BBP_CSR_CFG, ((reg and 0xff) shl 8) or 0x20000 or 0x10000 or 0x80000)
        bbpBusy(conn)
        return regRd(conn, BBP_CSR_CFG) and 0xff
    }

    private fun rfBusy(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 50
        do { if (regRd(conn, RF_CSR_CFG) and 0x20000 == 0) return true; sleep(1) } while (System.currentTimeMillis() < end)
        return false
    }
    private fun rfWr(conn: UsbDeviceConnection, reg: Int, v: Int) {
        rfBusy(conn)
        regWr(conn, RF_CSR_CFG, (v and 0xff) or ((reg and 0x3f) shl 8) or 0x10000 or 0x20000)
    }
    private fun rfRd(conn: UsbDeviceConnection, reg: Int): Int {
        rfBusy(conn)
        regWr(conn, RF_CSR_CFG, ((reg and 0x3f) shl 8) or 0x20000)
        rfBusy(conn)
        return regRd(conn, RF_CSR_CFG) and 0xff
    }

    private fun waitCsrReady(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 1000
        do { val v = regRd(conn, MAC_CSR0); if (v != 0 && v != 0xffffffff.toInt()) return true; sleep(1) } while (System.currentTimeMillis() < end)
        return false
    }
    private fun waitWpdma(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 1000
        do { if (regRd(conn, WPDMA_GLO_CFG) and 0x0a == 0) return true; sleep(10) } while (System.currentTimeMillis() < end)
        return false
    }
    private fun waitBbpRfReady(conn: UsbDeviceConnection) {
        val end = System.currentTimeMillis() + 1000
        while (regRd(conn, MAC_STATUS_CFG) and 0x3 != 0 && System.currentTimeMillis() < end) sleep(10)
    }

    private fun sh(mask: Int) = Integer.numberOfTrailingZeros(mask)
    private fun setf(reg: Int, mask: Int, v: Int) = (reg and mask.inv()) or ((v shl sh(mask)) and mask)
    private fun putLe32(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i+1] = (v ushr 8).toByte(); b[i+2] = (v ushr 16).toByte(); b[i+3] = (v ushr 24).toByte() }
    private fun le32(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) or ((b[i+2].toInt() and 0xff) shl 16) or ((b[i+3].toInt() and 0xff) shl 24)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
