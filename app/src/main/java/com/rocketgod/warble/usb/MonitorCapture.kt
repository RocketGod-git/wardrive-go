package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.util.Collections

data class Sighting(
    val mac: String,
    val isAp: Boolean,
    var ssid: String? = null,
    var bssid: String? = null,
    var rssi: Int = -100,
    var frames: Int = 0,
    var lastSeen: Long = 0L,
    var channel: Int = 6,
    var probedSsids: MutableSet<String> = linkedSetOf(),
    var flock: Boolean = false
)

object MonitorCapture {

    private const val RX_FILTER_MONITOR = 0x0018ffff

    private val HOP_CHANNELS = intArrayOf(1, 6, 11, 3, 9, 2, 7, 13, 4, 10, 5, 8, 12)
    private const val HOP_DWELL_MS = 500L
    @Volatile private var curChannel = 6
    private fun chFreq(ch: Int) = 2407 + ch * 5

    val sightings: MutableMap<String, Sighting> =
        java.util.Collections.synchronizedMap(LinkedHashMap<String, Sighting>())

    fun snapshotSightings(): List<Sighting> = synchronized(sightings) {

        val now = System.currentTimeMillis()

        val fresh = (UsbBeast.effectiveSweepMs(HOP_CHANNELS, HOP_DWELL_MS) * 9 / 5).coerceAtLeast(3000L)
        UsbBeast.seenWindowMs = fresh
        UsbBeast.defaultDwellMs = HOP_DWELL_MS.toInt()
        sightings.values.filter { now - it.lastSeen < fresh }.map { it.copy() }
    }

    private const val ANT_EEPROM_READ = false

    private const val ANT_EEPROM_EARLY = false
    @Volatile private var running = false

    @Volatile var lastHandshakeOk = false

    @Volatile var captureClients = true

    private val rxAbort = java.util.concurrent.atomic.AtomicBoolean(false)

    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val frames: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    fun isRunning() = running

    fun abort() {
        cancelled.set(true)
        rxAbort.set(true)
        BeastDiag.log("abort() — adapter gone or re-engaging; unwinding probe")
    }

    fun probe(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit) {
        if (running) return
        running = true
        cancelled.set(false)
        val id = String.format("%04x:%04x", device.vendorId, device.productId)
        BeastDiag.clear()
        BeastDiag.log("device $id  class=${device.deviceClass}  ifaces=${device.interfaceCount}")
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("Monitor · can't open $id (USB permission?)"); running = false; return }
        try {
            val intf = device.getInterface(0)
            conn.claimInterface(intf, true)
            rxAbort.set(false)
            sightings.clear()
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            fun tag(t: Int) = when (t) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "b"; UsbConstants.USB_ENDPOINT_XFER_INT -> "i"; else -> "?"
            }
            val map = eps.joinToString(" ") { e ->
                String.format("%02x/%s%s(mps=%d)", e.address, tag(e.type), if (e.direction == UsbConstants.USB_DIR_IN) "in" else "out", e.maxPacketSize)
            }
            BeastDiag.log("endpoints: $map")
            onStatus("Monitor · $id · eps · reading…")

            val results = StringBuilder()
            var ready: ByteArray? = null
            var regIn: UsbEndpoint? = null
            var regOut: UsbEndpoint? = null
            for (e in eps) {
                if (e.address == 0x83) regIn = e
                if (e.address == 0x04) regOut = e
                if (e.direction != UsbConstants.USB_DIR_IN) continue
                val buf = ByteArray(maxOf(e.maxPacketSize, 512))
                var t = 0
                var first: ByteArray? = null
                val until = System.currentTimeMillis() + 900
                while (System.currentTimeMillis() < until) {
                    val n = conn.bulkTransfer(e, buf, buf.size, 300)
                    if (n > 0) { if (first == null) first = buf.copyOf(n); t += n; frames.add(buf.copyOf(n)) }
                }
                results.append(String.format("%02x:%dB ", e.address, t))
                BeastDiag.log(String.format("ep%02x read %dB  first=%s", e.address, t, BeastDiag.hex(first)))
                if (e.address == 0x83 && first != null) ready = first
            }
            var htc = ready?.let { parseHtcReady(it) }
            BeastDiag.log("HTC ready parse:${htc ?: " (none)"}")

            fun htcOk(x: String?) = x != null && x.contains("HTC READY")
            if (htcOk(htc)) lastHandshakeOk = true
            if (!htcOk(htc)) {
                val rIn = regIn
                if (rIn != null) {
                    val b2 = ByteArray(512)
                    outer@ for (attempt in 1..10) {
                        try { Thread.sleep(250) } catch (t: Throwable) {}
                        for (k in 0 until 6) {
                            val n = try { conn.bulkTransfer(rIn, b2, b2.size, 300) } catch (e: Throwable) { -1 }
                            if (n >= 16) {
                                val cand = b2.copyOf(n)
                                val p = parseHtcReady(cand)
                                if (htcOk(p)) {
                                    ready = cand; htc = p
                                    lastHandshakeOk = true
                                    BeastDiag.log("HTC READY recovered on retry $attempt: $p")
                                    break@outer
                                }
                            }
                        }
                        BeastDiag.log("HTC handshake retry $attempt/10 — still no valid READY")
                    }
                }
            }
            if (!htcOk(htc)) {
                BeastDiag.log("ABORT: firmware never produced a valid HTC READY.")
                BeastDiag.log("  The adapter is not running its firmware (wedged or bad enumeration).")
                BeastDiag.log("  Fix: unplug, power the phone OFF fully, wait 30s, power on, replug.")
                lastHandshakeOk = false
                onStatus("Monitor · adapter not ready — re-uploading firmware next attempt · tap for dump")
                return
            }

            val rxEpP = eps.firstOrNull { it.address == 0x82 }
            val rxSeen = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())

            val rxStats = IntArray(6)
            val rxTotal = java.util.concurrent.atomic.AtomicInteger(0)
            val rxChunks = java.util.concurrent.atomic.AtomicInteger(0)
            val rxStop = java.util.concurrent.atomic.AtomicBoolean(false)
            var rxThread: Thread? = null
            val startRxReader = {
                rxThread = if (rxEpP != null) Thread {

                    val N = 8
                    val SZ = 16384
                    val reqs = ArrayList<android.hardware.usb.UsbRequest>(N)
                    val bufs = HashMap<android.hardware.usb.UsbRequest, java.nio.ByteBuffer>()
                    try {
                        for (i in 0 until N) {
                            val r = android.hardware.usb.UsbRequest()
                            if (!r.initialize(conn, rxEpP)) break
                            val bb = java.nio.ByteBuffer.allocate(SZ)
                            bufs[r] = bb
                            reqs.add(r)
                            @Suppress("DEPRECATION")
                            if (!r.queue(bb, SZ)) BeastDiag.log("rx urb $i initial queue failed")
                        }
                        BeastDiag.log("RX: ${reqs.size} async URBs queued on 0x82 (hif_usb model)")

                        val rxUntil = System.currentTimeMillis() + 24L * 3600 * 1000
                        while (!rxStop.get() && !rxAbort.get() && System.currentTimeMillis() < rxUntil) {
                            val done = try { conn.requestWait() } catch (t: Throwable) { null } ?: continue
                            val bb = bufs[done] ?: continue
                            val n = bb.position()
                            var data: ByteArray? = null
                            if (n > 0) {
                                data = ByteArray(n)
                                bb.rewind(); bb.get(data)
                            }

                            bb.clear()
                            @Suppress("DEPRECATION")
                            if (!done.queue(bb, SZ)) {

                                runCatching { done.close() }; bufs.remove(done); reqs.remove(done)
                                val nr = android.hardware.usb.UsbRequest()
                                if (runCatching { nr.initialize(conn, rxEpP) }.getOrDefault(false)) {
                                    bb.clear(); bufs[nr] = bb; reqs.add(nr)
                                    @Suppress("DEPRECATION")
                                    runCatching { nr.queue(bb, SZ) }
                                }
                            }
                            if (data != null) {
                                rxTotal.addAndGet(n)
                                val c = rxChunks.incrementAndGet()
                                if (c <= 8) BeastDiag.log("0x82 RX[$c] ${n}B ${BeastDiag.hex(data, 64)}")
                                try { parseRxStream(data, rxSeen, rxStats) } catch (t: Throwable) {}
                            }
                        }
                        BeastDiag.log("rx urb loop exited: stop=${rxStop.get()} abort=${rxAbort.get()} " +
                            "deadline=${System.currentTimeMillis() >= rxUntil}")
                    } catch (t: Throwable) {
                        BeastDiag.log("rx urb loop: ${t.message}")
                    } finally {
                        for (r in reqs) { runCatching { r.cancel() }; runCatching { r.close() } }
                    }
                }.apply { isDaemon = true; start() } else null
            }

            var wmi = ""
            val rin = regIn; val rout = regOut
            if (ready != null && rin != null && rout != null) {
                val connMsg = buildConnectSvc(0x0100, dl = 3, ul = 4)
                val sent = conn.bulkTransfer(rout, connMsg, 18, 1000)
                BeastDiag.log("WMI connect sent=$sent  bytes=${BeastDiag.hex(connMsg)}")
                val resp = readOnce(conn, rin, 1200)
                BeastDiag.log("WMI connect resp=${BeastDiag.hex(resp)}")
                val connStr = when { sent < 0 -> "send failed"; resp == null -> "no reply"; else -> parseConnResp(resp) }
                wmi = " · WMI $connStr"
                if (resp != null && connStr.contains("ep=")) {

                    BeastDiag.log("--- connect data services ---")
                    val svcs = intArrayOf(0x0101, 0x0102, 0x0103, 0x0104, 0x0107, 0x0108, 0x0106, 0x0105)
                    var sq = 80
                    for (svc in svcs) {
                        val m = buildConnectSvc(svc, dl = 2, ul = 1)
                        conn.bulkTransfer(rout, m, 18, 1000)
                        val r = readOnce(conn, rin, 1000)
                        BeastDiag.log(String.format("connect 0x%04x -> %s", svc, if (r != null) parseConnResp(r) else "noreply"))
                        sq++
                    }
                    val credits = if (ready.size >= 16) (((ready[10].toInt() and 0xff) shl 8) or (ready[11].toInt() and 0xff)) else 33
                    conn.bulkTransfer(rout, buildConfigPipe(credits), 12, 1000)
                    val cfg = readOnce(conn, rin, 1000)
                    BeastDiag.log("config-pipe resp=${BeastDiag.hex(cfg)}")
                    conn.bulkTransfer(rout, buildSetupComplete(), 10, 1000)
                    val setup = readOnce(conn, rin, 1000)
                    BeastDiag.log("setup-complete resp=${BeastDiag.hex(setup)}")
                    wmi += " · cfg=" + (if (msgIdOf(cfg) == 6) "OK" else "${msgIdOf(cfg)}") + " setup=" + (if (setup != null) "ack" else "-")

                    val wmiCmd = buildWmiCmd(epid = 1, cmdId = 3, seq = 1)
                    val ws = conn.bulkTransfer(rout, wmiCmd, wmiCmd.size, 1000)
                    BeastDiag.log("WMI GET_FW_VERSION sent=$ws bytes=${BeastDiag.hex(wmiCmd)}")
                    val wr = readOnce(conn, rin, 1200)
                    BeastDiag.log("WMI fw-version resp=${BeastDiag.hex(wr)}")
                    if (wr != null && wr.size >= 16) {
                        val maj = ((wr[12].toInt() and 0xff) shl 8) or (wr[13].toInt() and 0xff)
                        val min = ((wr[14].toInt() and 0xff) shl 8) or (wr[15].toInt() and 0xff)
                        BeastDiag.log("WMI fw version = $maj.$min")
                        wmi += " · WMIcmd✓ fw=$maj.$min"
                    } else wmi += " · WMIcmd " + (if (wr == null) "noreply" else "${wr.size}B")

                    run {
                        val stopRx = buildWmiCmd(epid = 1, cmdId = 13, seq = 380)
                        conn.bulkTransfer(rout, stopRx, stopRx.size, 1000)
                        BeastDiag.log("WMI STOP_RECV resp=${BeastDiag.hex(readOnce(conn, rin, 800))}")
                        val disInt = buildWmiCmd(epid = 1, cmdId = 4, seq = 381)
                        conn.bulkTransfer(rout, disInt, disInt.size, 1000)
                        BeastDiag.log("WMI DISABLE_INTR resp=${BeastDiag.hex(readOnce(conn, rin, 800))}")
                        try { Thread.sleep(120) } catch (t: Throwable) {}
                        BeastDiag.log("firmware quiesced — safe to reset/reclock")
                    }

                    BeastDiag.log("--- register reads ---")
                    val srev = wmiRegRead(conn, rout, rin, 0x4020, 2)
                    if (srev != null) {
                        val id = (srev and 0xFF).toInt()
                        val macVer = if (id == 0xFF) ((srev ushr 18) and 0x3FFF).toInt() else ((srev ushr 4) and 0xF).toInt()
                        val macRev = if (id == 0xFF) ((srev ushr 8) and 0xF).toInt() else (srev and 0x7).toInt()
                        val chip = if (macVer == 0x140) "AR9271" else String.format("ver0x%x", macVer)
                        BeastDiag.log(String.format("AR_SREV=0x%08x -> macVer=0x%x rev=%d (%s)", srev, macVer, macRev, chip))
                        wmi += " · SREV=$chip"
                    } else wmi += " · SREV noreply"
                    wmiRegRead(conn, rout, rin, 0x7044, 3)
                    wmiRegRead(conn, rout, rin, 0x8000, 4)
                    wmiRegRead(conn, rout, rin, 0x8004, 5)

                    BeastDiag.log("--- wake sequence ---"); onStatus("Monitor · waking radio…")
                    wmiRegWrite(conn, rout, rin, 0x704c, 0x00000003, 6)
                    wmiRegWrite(conn, rout, rin, 0x4000, 0x00000001, 7)
                    wmiRegWrite(conn, rout, rin, 0x7040, 0x00000000, 8)
                    try { Thread.sleep(3) } catch (t: Throwable) {}
                    wmiRegWrite(conn, rout, rin, 0x4000, 0x00000000, 9)
                    wmiRegWrite(conn, rout, rin, 0x7040, 0x00000001, 10)
                    var woke = false
                    for (i in 0 until 12) {
                        val st = wmiRegRead(conn, rout, rin, 0x7044, 11 + i)
                        if (st != null && (st and 0xfL) == 0x2L) { BeastDiag.log("RTC STATUS=ON after ${i + 1} polls"); woke = true; break }
                        try { Thread.sleep(5) } catch (t: Throwable) {}
                    }
                    if (!woke) BeastDiag.log("RTC did not report ON")
                    val mac0 = wmiRegRead(conn, rout, rin, 0x8000, 40)
                    val macAwake = mac0 != null && mac0 != 0xDEADBEEFL
                    BeastDiag.log(String.format("post-wake 0x8000=0x%08x macAwake=%b", mac0 ?: -1L, macAwake))

                    val antSw0Boot = wmiRegRead(conn, rout, rin, 0x9960, 240) ?: -1L
                    val antSwcBoot = wmiRegRead(conn, rout, rin, 0x9964, 241) ?: -1L
                    BeastDiag.log(String.format("BOOT board values: 0x9960=0x%08x 0x9964=0x%08x", antSw0Boot, antSwcBoot))

                    var eepAnt0 = -1L; var eepAntC = -1L
                    if (ANT_EEPROM_EARLY) {
                        BeastDiag.log("--- EEPROM read (early: post power-on, pre-PLL) ---")
                        BeastDiag.log("» eepRead word 0 (magic) reg=0x2000")
                        val mg = eepRead(conn, rout, rin, 0, 244)
                        BeastDiag.log(String.format("EEPROM magic=0x%04x (expect a55a/5aa5)", mg))
                        if (mg == 0xa55a || mg == 0x5aa5) {
                            BeastDiag.log("» eepRead words 176,177,180,181 (antCtrl)")
                            val w176 = eepRead(conn, rout, rin, 176, 245)
                            val w177 = eepRead(conn, rout, rin, 177, 246)
                            val w180 = eepRead(conn, rout, rin, 180, 247)
                            val w181 = eepRead(conn, rout, rin, 181, 248)
                            eepAnt0 = (w176 or (w177 shl 16)).toLong() and 0xffffffffL
                            eepAntC = (w180 or (w181 shl 16)).toLong() and 0xffffffffL
                            BeastDiag.log(String.format("w176=%04x w177=%04x w180=%04x w181=%04x", w176, w177, w180, w181))
                            BeastDiag.log(String.format("EEPROM antCtrlChain0=0x%08x antCtrlCommon=0x%08x", eepAnt0, eepAntC))
                        } else {
                            BeastDiag.log("magic invalid — stopping EEPROM reads (no further 0x2000 access)")
                        }
                        BeastDiag.log("» EEPROM read block complete (adapter survived)")
                    }
                    wmi += if (macAwake) " · MAC AWAKE ✓" else " · MAC asleep"

                    BeastDiag.log("--- AR9271 radio RF reset (first-boot only) ---")
                    wmiRegWrite(conn, rout, rin, 0x50044, 0x00000020, 48)
                    try { Thread.sleep(50) } catch (t: Throwable) {}

                    BeastDiag.log("--- warm reset ---"); onStatus("Monitor · warm reset…")
                    wmiRegWrite(conn, rout, rin, 0x704c, 0x00000003, 50)
                    val sync = wmiRegRead(conn, rout, rin, 0x4028, 51) ?: 0L
                    if ((sync and 0x3000L) != 0L) {
                        wmiRegWrite(conn, rout, rin, 0x402c, 0x00000000, 52)
                        wmiRegWrite(conn, rout, rin, 0x4000, 0x00000101, 53)
                    } else {
                        wmiRegWrite(conn, rout, rin, 0x4000, 0x00000001, 53)
                    }
                    wmiRegWrite(conn, rout, rin, 0x7000, 0x00000001, 54)
                    try { Thread.sleep(2) } catch (t: Throwable) {}
                    wmiRegWrite(conn, rout, rin, 0x7000, 0x00000000, 55)
                    var rcClear = false
                    for (i in 0 until 10) {
                        val rc = wmiRegRead(conn, rout, rin, 0x7000, 56 + i)
                        if (rc != null && (rc and 0x3L) == 0L) { rcClear = true; break }
                        try { Thread.sleep(3) } catch (t: Throwable) {}
                    }
                    wmiRegWrite(conn, rout, rin, 0x4000, 0x00000000, 70)
                    BeastDiag.log("warm reset rcClear=$rcClear")
                    val antSw0Rst = wmiRegRead(conn, rout, rin, 0x9960, 242) ?: -1L
                    val antSwcRst = wmiRegRead(conn, rout, rin, 0x9964, 243) ?: -1L
                    BeastDiag.log(String.format("POST-RESET board values: 0x9960=0x%08x 0x9964=0x%08x", antSw0Rst, antSwcRst))

                    wmiRegWrite(conn, rout, rin, 0x8000, 0x1a2b3c4d.toInt(), 71)
                    val rb = wmiRegRead(conn, rout, rin, 0x8000, 72)
                    val writeWorks = rb == 0x1a2b3c4dL
                    BeastDiag.log(String.format("MAC write/readback 0x8000=0x%08x writeWorks=%b", rb ?: -1L, writeWorks))
                    wmi += if (writeWorks) " · MAC RW ✓" else " · MAC RW?"

                    BeastDiag.log("--- PLL init ---"); onStatus("Monitor · PLL…")
                    wmiRegWrite(conn, rout, rin, 0x7014, 0x0000142c, 73)
                    try { Thread.sleep(1) } catch (t: Throwable) {}
                    wmiRegWrite(conn, rout, rin, 0x50040, 0x00000304, 74)
                    wmiRegWrite(conn, rout, rin, 0x7048, 0x00000002, 75)
                    try { Thread.sleep(2) } catch (t: Throwable) {}

                    wmiRegWrite(conn, rout, rin, 0x50044, 0x00004000, 76)
                    try { Thread.sleep(50) } catch (t: Throwable) {}
                    BeastDiag.log("AR9271 GATE_MAC_CTL applied (0x50044=0x4000)")

                    onStatus("Monitor · writing PHY init (~660 regs)…")
                    BeastDiag.log("--- PHY init tables ---"); onStatus("Monitor · PHY init…")
                    var s = 100
                    s = writeArray(conn, rout, rin, Ar9271Init.modes2g, "modes(2G_HT20)", s)
                    s = writeArray(conn, rout, rin, Ar9271Init.common, "common", s)
                    s = writeArray(conn, rout, rin, Ar9271Init.txGain2g, "txGain(2G)", s)

                    val chk = wmiRegRead(conn, rout, rin, 0x1030, s++)
                    val phyOk = chk == 0x160L
                    BeastDiag.log(String.format("PHY checkpoint 0x1030=0x%08x (want 0x00000160) ok=%b", chk ?: -1L, phyOk))

                    BeastDiag.log("--- ANI detection thresholds (ar9271 ANI_reg) ---")
                    val aniRegs = arrayOf(
                        intArrayOf(0x9850, 0x6d4000e2.toInt()),
                        intArrayOf(0x985c, 0x3137605e),
                        intArrayOf(0x9858, 0x7ec84d2e),
                        intArrayOf(0x986c, 0x06903881),
                        intArrayOf(0x9868, 0x5ac640d0.toInt()),
                        intArrayOf(0x9924, 0xd00a800d.toInt()),
                        intArrayOf(0x99c0, 0x05eea6d4)
                    )
                    for ((i, rv) in aniRegs.withIndex()) wmiRegWrite(conn, rout, rin, rv[0], rv[1], 280 + i)

                    val cckCur = wmiRegRead(conn, rout, rin, 0xa208, 288) ?: 0L
                    val cckVal = ((0x803e68c8L and 0x3fL) or (cckCur and 0x3fL.inv())).toInt()
                    wmiRegWrite(conn, rout, rin, 0xa208, cckVal, 289)
                    BeastDiag.log(String.format("ANI regs written (8) · 0xa208 cur=0x%08x -> 0x%08x", cckCur, cckVal))
                    wmi += if (phyOk) " · PHY INIT ✓" else String.format(" · PHY chk=%x", chk ?: -1L)

                    wmiRegWrite(conn, rout, rin, 0x99a4, 0x00000001, 250)
                    wmiRegWrite(conn, rout, rin, 0xa39c, 0x00000001, 251)
                    BeastDiag.log("chain masks set (rx=1, cal=1)")

                    wmiRegWrite(conn, rout, rin, 0xA200, 0x00000004, 252)

                    wmiRegWrite(conn, rout, rin, 0x9804, 0x000003c0, 256)

                    wmiRegWrite(conn, rout, rin, 0x8058, 0x00000000, 253)
                    wmiRegWrite(conn, rout, rin, 0x0080, -1, 254)
                    wmiRegWrite(conn, rout, rin, 0x8018, 0x00000007, 255)

                    BeastDiag.log("--- EEPROM health probe (safe: no 0x2000 access) ---")
                    BeastDiag.log("» read AR_EEPROM_STATUS_DATA 0x407c")
                    val eepStat = wmiRegRead(conn, rout, rin, 0x407c, 256) ?: -1L
                    if (eepStat < 0) {
                        BeastDiag.log("0x407c read FAILED (no response)")
                        wmi += " · eep?"
                    } else {
                        val busy    = (eepStat and 0x00010000L) != 0L
                        val busyAcc = (eepStat and 0x00020000L) != 0L
                        val prot    = (eepStat and 0x00040000L) != 0L
                        val absent  = (eepStat and 0x00080000L) != 0L
                        BeastDiag.log(String.format("0x407c=0x%08x  data=0x%04x", eepStat, eepStat and 0xffffL))
                        BeastDiag.log("  BUSY=$busy BUSY_ACCESS=$busyAcc PROT_ACCESS=$prot ABSENT_ACCESS=$absent")
                        val healthy = !busy && !busyAcc && !prot && !absent
                        BeastDiag.log(if (healthy) "  EEPROM looks PRESENT/idle -> window reads would be safe"
                                      else "  EEPROM NOT usable -> 0x2000 window reads WOULD HANG the firmware")
                        wmi += if (healthy) " · eep✓" else " · eepX"
                    }

                    BeastDiag.log("--- EEPROM 4K board values ---")
                    val eep = readEeprom4k(conn, rout, rin)
                    if (eep != null) {
                        applyBoardValues4k(conn, rout, rin, eep)
                        wmi += " · board\u2713"
                    } else {
                        BeastDiag.log("EEPROM unreadable - leaving INI defaults")
                        wmi += " · boardX"
                    }
                    BeastDiag.log(String.format("antenna: 0x9960=0x%08x 0x9964=0x%08x 0x99ac=0x%08x",
                        wmiRegRead(conn, rout, rin, 0x9960, 780) ?: -1L,
                        wmiRegRead(conn, rout, rin, 0x9964, 781) ?: -1L,
                        wmiRegRead(conn, rout, rin, 0x99ac, 782) ?: -1L))
                    @Suppress("ConstantConditionIf")
                    if (ANT_EEPROM_READ) {
                    BeastDiag.log("--- EEPROM antenna config ---")
                    BeastDiag.log("» eepRead word 0 (magic) reg=0x2000")
                    val magic = eepRead(conn, rout, rin, 0, 266)
                    BeastDiag.log(String.format("EEPROM magic=0x%04x (expect a55a/5aa5)", magic))
                    if (magic == 0xa55a || magic == 0x5aa5) {
                        val w176 = eepRead(conn, rout, rin, 176, 257)
                        val w177 = eepRead(conn, rout, rin, 177, 258)
                        val w180 = eepRead(conn, rout, rin, 180, 259)
                        val w181 = eepRead(conn, rout, rin, 181, 260)
                        val ac0 = w176 or (w177 shl 16)
                        val acc = w180 or (w181 shl 16)
                        BeastDiag.log(String.format("w176=%04x w177=%04x w180=%04x w181=%04x", w176, w177, w180, w181))
                        BeastDiag.log(String.format("antCtrlChain0=0x%08x antCtrlCommon=0x%08x", ac0, acc))
                        wmiRegWrite(conn, rout, rin, 0x9960, ac0, 261)
                        wmiRegWrite(conn, rout, rin, 0x9964, acc, 262)
                        BeastDiag.log("antenna switch applied (0x9960/0x9964)")
                        wmi += " · ant✓"
                    } else {
                        BeastDiag.log("EEPROM magic invalid — antenna NOT applied, reads skipped")
                        wmi += " · ant?"
                    }
                    } else {
                        BeastDiag.log("EEPROM antenna step SKIPPED (safe mode — no 0x2000 reads)")
                    }

                    onStatus("Monitor · tuning ch6 + RX attempt…")
                    BeastDiag.log("--- channel/RX attempt (ch6 2437MHz) ---")
                    val freq = 2437L
                    val chanSel = (freq * 0x10000L) / 15L
                    val synthOld = wmiRegRead(conn, rout, rin, 0x9874, 200) ?: 0L
                    val synth = (synthOld and 0xc0000000L) or 0x30000000L or chanSel
                    wmiRegWrite(conn, rout, rin, 0x9874, synth.toInt(), 201)
                    BeastDiag.log(String.format("synth 0x9874=0x%08x chanSel=0x%x", synth, chanSel))
                    setDeltaSlope(conn, rout, rin, 2437)

                    for (i in 0..9) wmiRegWrite(conn, rout, rin, 0x1000 + (i shl 2), 1 shl i, 260 + i)

                    val sid = wmiRegRead(conn, rout, rin, 0x8004, 271) ?: 0L
                    wmiRegWrite(conn, rout, rin, 0x8004, (sid or 0x20000000L).toInt(), 272)

                    val ahb = wmiRegRead(conn, rout, rin, 0x4024, 273) ?: 0L
                    wmiRegWrite(conn, rout, rin, 0x4024, (ahb or 0x4L).toInt(), 274)
                    val txc = wmiRegRead(conn, rout, rin, 0x0030, 275) ?: 0L
                    wmiRegWrite(conn, rout, rin, 0x0030, ((txc and 0x7L.inv()) or 5L).toInt(), 276)
                    val rxc = wmiRegRead(conn, rout, rin, 0x0034, 277) ?: 0L
                    wmiRegWrite(conn, rout, rin, 0x0034, ((rxc and 0x7L.inv()) or 5L).toInt(), 278)
                    wmiRegWrite(conn, rout, rin, 0x8114, 0x00000200, 279)
                    BeastDiag.log("init_queues + set_dma done (RXCFG DMASZ=128B, RXFIFO=0x200)")

                    wmiRegWrite(conn, rout, rin, 0x00a4, 0x010f0000, 730)
                    wmiRegWrite(conn, rout, rin, 0x00a8, 0x010f0000, 731)
                    wmiRegWrite(conn, rout, rin, 0x00ac, 0x00800000, 732)
                    wmiRegWrite(conn, rout, rin, 0x00a0, 0x81800964.toInt(), 733)
                    wmiRegWrite(conn, rout, rin, 0x4028, -1, 734)
                    wmiRegWrite(conn, rout, rin, 0x402c, 0x00023f60, 735)
                    wmiRegWrite(conn, rout, rin, 0x4034, 0x00000000, 736)
                    BeastDiag.log(String.format("AR_IMR=0x%08x (readback 0x%08x)",
                        0x81800964L, wmiRegRead(conn, rout, rin, 0x00a0, 737) ?: -1L))
                    wmiRegWrite(conn, rout, rin, 0x981c, 0x00000001, 202)
                    try { Thread.sleep(5) } catch (t: Throwable) {}

                    onStatus("Monitor · calibrating…")
                    BeastDiag.log("--- calibration (AGC offset + self-measured NF) ---")
                    var c = 300
                    c = regSet(conn, rout, rin, 0x9860, 0x1, c)
                    val cal1 = waitClear(conn, rout, rin, 0x9860, 0x1, 40, c); c += 40
                    BeastDiag.log("AGC offset cal complete=$cal1")

                    val agc = wmiRegRead(conn, rout, rin, 0x9860, 340) ?: 0L
                    wmiRegWrite(conn, rout, rin, 0x9860,
                        ((agc or 0x8000L) and 0x20000L.inv() or 0x2L).toInt(), 341)
                    val nf = waitClear(conn, rout, rin, 0x9860, 0x00000002, 60, c); c += 60
                    BeastDiag.log("noise-floor cal complete=$nf")
                    try { Thread.sleep(300) } catch (t: Throwable) {}

                    val ccaPost = wmiRegRead(conn, rout, rin, 0x9864, 349) ?: 0L
                    var nfPost = ((ccaPost and 0x1FF00000L) shr 20).toInt(); if ((nfPost and 0x100) != 0) nfPost -= 0x200

                    val nfVerdict = when {
                        nfPost > -116 -> "ABOVE max -116 → front-end likely wedged, expect deaf RX"
                        nfPost < -127 -> "BELOW min -127 (nominal -118)"
                        else          -> "in AR9271 range [-127,-116] ✓"
                    }
                    BeastDiag.log(String.format("AR_PHY_CCA=0x%08x  minCCApwr=%d dBm  (%s)",
                        ccaPost, nfPost, nfVerdict))
                    wmi += if (cal1) " · CAL ✓" else " · CAL?"

                    startRxReader()

                    val staid1 = wmiRegRead(conn, rout, rin, 0x8004, 210) ?: 0L
                    wmiRegWrite(conn, rout, rin, 0x8004, ((staid1 and (0x10000L or 0x20000L).inv()) or 0x10000000L).toInt(), 211)

                    wmiRegWrite(conn, rout, rin, 0x0014, 0x0000000a, 209)
                    BeastDiag.log("AR_CFG (0x0014) = 0x0a  (RX/TX buffer byte-swap for USB)")

                    val flushCmd = buildWmiCmd(epid = 1, cmdId = 14, seq = 207)
                    conn.bulkTransfer(rout, flushCmd, flushCmd.size, 1000)
                    BeastDiag.log("WMI FLUSH_RECV resp=${BeastDiag.hex(readOnce(conn, rin, 800))}")

                    val modeCmd = buildWmiCmd(epid = 1, cmdId = 15, seq = 219, payload = byteArrayOf(0, 1))
                    conn.bulkTransfer(rout, modeCmd, modeCmd.size, 1000)
                    BeastDiag.log("WMI SET_MODE resp=${BeastDiag.hex(readOnce(conn, rin, 800))}")

                    val initCmd = buildWmiCmd(epid = 1, cmdId = 6, seq = 208)
                    conn.bulkTransfer(rout, initCmd, initCmd.size, 1000)
                    BeastDiag.log("WMI ATH_INIT resp=${BeastDiag.hex(readOnce(conn, rin, 800))}")

                    val startCmd = buildWmiCmd(epid = 1, cmdId = 12, seq = 212)
                    conn.bulkTransfer(rout, startCmd, startCmd.size, 1000)
                    BeastDiag.log("WMI START_RECV resp=${BeastDiag.hex(readOnce(conn, rin, 800))}")

                    wmiRegWrite(conn, rout, rin, 0x0008, 0x00000004, 213)
                    wmiRegWrite(conn, rout, rin, 0x803c, RX_FILTER_MONITOR, 214)
                    wmiRegWrite(conn, rout, rin, 0x8040, -1, 215)
                    wmiRegWrite(conn, rout, rin, 0x8044, -1, 216)
                    val diag = wmiRegRead(conn, rout, rin, 0x8048, 217) ?: 0L
                    wmiRegWrite(conn, rout, rin, 0x8048, (diag and (0x20L or 0x02000000L).inv()).toInt(), 218)

                    run {
                        val macStr = eep?.mac ?: ""
                        val mac = if (macStr.contains(":"))
                            macStr.split(":").map { it.toInt(16).toByte() }.toByteArray()
                        else ByteArray(6)
                        fun send(cmd: Int, pay: ByteArray, seq: Int, name: String) {
                            val c = buildWmiCmd(epid = 1, cmdId = cmd, seq = seq, payload = pay)
                            conn.bulkTransfer(rout, c, c.size, 1000)
                            BeastDiag.log("  $name resp=${BeastDiag.hex(readOnce(conn, rin, 800), 24)}")
                        }
                        send(24, byteArrayOf(0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0, 1, 0), 790, "TARGET_IC_UPDATE")
                        send(19, byteArrayOf(0, 1) + mac + byteArrayOf(0, 9, 0, 0), 791, "VAP_CREATE")
                        send(16, mac + ByteArray(6) + byteArrayOf(0, 0, 1, 0, 0, 0, 0, 0xff.toByte()), 792, "NODE_CREATE")
                        send(15, byteArrayOf(0, 1), 793, "SET_MODE")
                        send(5, ByteArray(0), 794, "ENABLE_INTR")
                    }

                    aniSet(conn, rout, rin, ofdmWeak = true, firstepLvl = 0, spurLvl = 0)
                    val rxEp = eps.firstOrNull { it.address == 0x82 }
                    if (rxEp != null) {

                        BeastDiag.log("=== RX re-arm cycle (ath9k_htc_set_channel) ===")
                        for (pass in 1..2) {
                            if (cancelled.get()) break
                            wmiCmdLog(conn, rout, rin, 4,  "DISABLE_INTR",  810 + pass * 10)
                            wmiCmdLog(conn, rout, rin, 11, "DRAIN_TXQ_ALL", 811 + pass * 10)
                            wmiCmdLog(conn, rout, rin, 13, "STOP_RECV",     812 + pass * 10)
                            try { Thread.sleep(20) } catch (t: Throwable) {}

                            wmiRegWrite(conn, rout, rin, 0x0008, 0x00000004, 813 + pass * 10)
                            wmiRegWrite(conn, rout, rin, 0x803c, RX_FILTER_MONITOR, 814 + pass * 10)
                            wmiRegWrite(conn, rout, rin, 0x8040, -1, 815 + pass * 10)
                            wmiRegWrite(conn, rout, rin, 0x8044, -1, 816 + pass * 10)
                            val dg = wmiRegRead(conn, rout, rin, 0x8048, 817 + pass * 10) ?: 0L
                            wmiRegWrite(conn, rout, rin, 0x8048, (dg and (0x20L or 0x02000000L).inv()).toInt(), 818 + pass * 10)
                            wmiCmdLog(conn, rout, rin, 12, "START_RECV",   819 + pass * 10)
                            wmiCmdLog2(conn, rout, rin, 15, byteArrayOf(0, 1), "SET_MODE", 820 + pass * 10)
                            wmiCmdLog(conn, rout, rin, 5,  "ENABLE_INTR",  821 + pass * 10)
                            val st = wmiRegRead(conn, rout, rin, 0x9864, 822 + pass * 10) ?: 0L
                            BeastDiag.log("  re-arm pass $pass done (CCA=0x%08x)".format(st))
                            try { Thread.sleep(150) } catch (t: Throwable) {}
                        }

                        BeastDiag.log("=== LIVE monitor (continuous until unplug) ===")
                        BeastDiag.quiet = true
                        onStatus("AR9271 · live · starting sweep…")
                        val started = System.currentTimeMillis()
                        var nextTick = started + 5000
                        var hopIdx = 0
                        var nextHop = started + HOP_DWELL_MS
                        curChannel = HOP_CHANNELS[0]
                        runCatching { hopTo(conn, rout, rin, HOP_CHANNELS[0], 1000) }
                        while (!cancelled.get()) {
                            val now = System.currentTimeMillis()
                            if (now >= nextHop) {
                                val ni = UsbBeast.nextHop(hopIdx, HOP_CHANNELS)
                                if (ni != hopIdx) { hopIdx = ni; runCatching { hopTo(conn, rout, rin, HOP_CHANNELS[hopIdx], 1000 + hopIdx * 20) } }
                                nextHop = now + UsbBeast.dwell(HOP_DWELL_MS)

                                val (fa, fc) = synchronized(sightings) {
                                    MonitorLive.countFresh(sightings.values, now, { it.isAp }, { it.lastSeen })
                                }
                                MonitorLive.push(onStatus, "AR9271", fa, fc, curChannel)
                            }
                            if (now >= nextTick) {
                                val el = (now - started) / 1000
                                BeastDiag.quiet = false

                                var tgt = ""
                                runCatching {
                                    val ci = buildWmiCmd(epid = 1, cmdId = 28, seq = 900 + el.toInt())
                                    conn.bulkTransfer(rout, ci, ci.size, 400)
                                    val ri = readOnce(conn, rin, 400)
                                    val cr = buildWmiCmd(epid = 1, cmdId = 30, seq = 950 + el.toInt())
                                    conn.bulkTransfer(rout, cr, cr.size, 400)
                                    val rr = readOnce(conn, rin, 400)
                                    fun be(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or
                                        ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
                                    if (ri != null && ri.size >= 24 && rr != null && rr.size >= 24)
                                        tgt = " | tgt ast_rx=${be(ri, 12)} rxorn=${be(ri, 16)} nobuf=${be(rr, 12)} send=${be(rr, 16)} done=${be(rr, 20)}"
                                }
                                val (fa, fc) = synchronized(sightings) {
                                    MonitorLive.countFresh(sightings.values, now, { it.isAp }, { it.lastSeen })
                                }
                                val tracked = synchronized(sightings) { sightings.size }
                                BeastDiag.log("  … live t=${el}s ch=$curChannel rx=${rxTotal.get()}B frames=${rxStats[0]} live=$fa AP · $fc client ($tracked tracked)$tgt | ${PmkidCapture.diag()}")
                                BeastDiag.quiet = true

                                nextTick = now + 5000
                            }
                            try { Thread.sleep(60) } catch (t: Throwable) {}
                        }
                        BeastDiag.quiet = false
                        BeastDiag.log("=== monitor stopped (adapter unplugged) ===")
                    }

                    BeastDiag.log("=== MAC/PCU timing (init_global_settings) ===")
                    val clk = 44
                    val usecBefore = wmiRegRead(conn, rout, rin, 0x801c, 320) ?: -1L
                    val toBefore   = wmiRegRead(conn, rout, rin, 0x8014, 321) ?: -1L
                    val slotBefore = wmiRegRead(conn, rout, rin, 0x1070, 322) ?: -1L
                    val eifsBefore = wmiRegRead(conn, rout, rin, 0x10b0, 323) ?: -1L
                    var rxLat = ((usecBefore and 0x1F800000L) shr 23).toInt()
                    var txLat = ((usecBefore and 0x007FC000L) shr 14).toInt()
                    val usecFld = (usecBefore and 0x7FL).toInt()
                    BeastDiag.log(String.format("BEFORE: AR_USEC=0x%08x (usec=%d rxLat=%d txLat=%d) TIME_OUT=0x%08x SLOT=0x%08x EIFS=0x%08x",
                        usecBefore, usecFld, rxLat, txLat, toBefore, slotBefore, eifsBefore))
                    if (rxLat == 0) rxLat = 37
                    if (txLat == 0) txLat = 54
                    val sifsUs = 10; val slotUs = 9
                    val ackUs = slotUs + sifsUs
                    wmiRegWrite(conn, rout, rin, 0x1030, (sifsUs - 2) * clk, 324)
                    wmiRegWrite(conn, rout, rin, 0x1070, slotUs * clk, 325)
                    val ackClks = ackUs * clk
                    wmiRegWrite(conn, rout, rin, 0x8014, (ackClks and 0x3FFF) or ((ackClks and 0x3FFF) shl 16), 326)
                    val usecNew = ((usecBefore.toInt()) and (0x1F800000 or 0x007FC000 or 0x7F).inv()) or
                                  ((clk - 1) and 0x7F) or ((txLat and 0x1FF) shl 14) or ((rxLat and 0x3F) shl 23)
                    wmiRegWrite(conn, rout, rin, 0x801c, usecNew, 327)
                    val usecAfter = wmiRegRead(conn, rout, rin, 0x801c, 328) ?: -1L
                    BeastDiag.log(String.format("AFTER : AR_USEC=0x%08x (want usec=%d rxLat=%d txLat=%d) SIFS=%d SLOT=%d ACK/CTS=%d clks",
                        usecAfter, clk - 1, rxLat, txLat, (sifsUs - 2) * clk, slotUs * clk, ackClks))

                    run {
                        val c = buildWmiCmd(epid = 1, cmdId = 28, seq = 230); conn.bulkTransfer(rout, c, c.size, 1000)
                        val r = readOnce(conn, rin, 1000)
                        BeastDiag.log("WMI INT_STATS resp=${BeastDiag.hex(r)}")
                        if (r != null && r.size >= 24) {
                            fun be(o: Int) = ((r[o].toInt() and 0xff) shl 24) or ((r[o + 1].toInt() and 0xff) shl 16) or ((r[o + 2].toInt() and 0xff) shl 8) or (r[o + 3].toInt() and 0xff)
                            BeastDiag.log("  fw ast_rx=${be(12)} rxorn=${be(16)} rxeol=${be(20)}")
                        }
                    }
                    run {
                        val c = buildWmiCmd(epid = 1, cmdId = 30, seq = 231); conn.bulkTransfer(rout, c, c.size, 1000)
                        val r = readOnce(conn, rin, 1000)
                        BeastDiag.log("WMI RX_STATS resp=${BeastDiag.hex(r)}")
                        if (r != null && r.size >= 24) {
                            fun be(o: Int) = ((r[o].toInt() and 0xff) shl 24) or ((r[o + 1].toInt() and 0xff) shl 16) or ((r[o + 2].toInt() and 0xff) shl 8) or (r[o + 3].toInt() and 0xff)
                            BeastDiag.log("  fw rx nobuf=${be(12)} send=${be(16)} done=${be(20)}")
                        }
                    }
                    rxStop.set(true)
                    rxThread?.join(500)
                    val got = rxTotal.get()
                    BeastDiag.log("0x82 RX total ${got}B in ${rxChunks.get()} reads (persistent)")
                    BeastDiag.log("frames=${rxStats[0]} good=${rxStats[1]} crc=${rxStats[2]} phyerr=${rxStats[3]} maxDatalen=${rxStats[4]} APs=${rxSeen.size}")
                    BeastDiag.log("  devices are from FCS-GOOD frames only; ${rxStats[5]} frame(s) with FCS errors were skipped (a corrupt MAC would create a phantom device)")
                    run {
                        val snap = synchronized(sightings) { sightings.values.toList() }
                        val aps = snap.filter { it.isAp }
                        val clients = snap.filter { !it.isAp }
                        val probes = clients.sumOf { it.probedSsids.size }
                        BeastDiag.log("=== MONITOR DEVICE LIST: ${snap.size} total — ${aps.size} AP, ${clients.size} client, $probes probed SSID(s) ===")
                        aps.sortedByDescending { it.rssi }.forEach {
                            BeastDiag.log("  AP  ${it.mac}  rssi=${it.rssi}  frames=${it.frames}  ${it.ssid ?: "<hidden>"}")
                        }
                        clients.sortedByDescending { it.rssi }.forEach {
                            val probed = if (it.probedSsids.isEmpty()) "" else "  probed=${it.probedSsids.joinToString(",")}"
                            BeastDiag.log("  DEV ${it.mac}  rssi=${it.rssi}  frames=${it.frames}  bssid=${it.bssid ?: "?"}$probed")
                        }
                    }
                    if (rxSeen.isEmpty()) BeastDiag.log("  (no decodable beacons yet)")
                    wmi += " · ${rxSeen.size} AP"
                    wmi += if (got > 0) " · RX $got B ⚡" else " · RX 0B"
                    BeastDiag.log("=== tickle complete — safe to COPY ===")
                }
            }
            onStatus("Monitor · $id · ${results.toString().trim()}" + (htc ?: " · HTC?") + wmi + " · tap for dump")
        } catch (e: Exception) {
            BeastDiag.log("exception: ${e.message}")
            onStatus("Monitor · $id · ${e.message} · tap for dump")
        } finally {
            rxAbort.set(true)
            try { Thread.sleep(150) } catch (t: Throwable) {}
            runCatching { device.getInterface(0)?.let { conn.releaseInterface(it) } }
            runCatching { conn.close() }
            running = false
            BeastDiag.log("USB released + closed (clean teardown)")
        }
    }

    private fun parseHtcReady(b: ByteArray): String? {
        if (b.size < 16) return null
        fun be16(i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        val msgId = be16(8)
        return if (msgId == 1) {
            " · HTC READY ✓ cr=${be16(10)} sz=${be16(12)} eps=${b[14].toInt() and 0xff}"
        } else " · 0x83 id=$msgId (unexpected)"
    }

    private fun buildConnectSvc(serviceId: Int, dl: Int, ul: Int): ByteArray {
        val m = ByteArray(18)
        m[3] = 10
        m[8] = 0; m[9] = 2
        m[10] = ((serviceId ushr 8) and 0xff).toByte(); m[11] = (serviceId and 0xff).toByte()
        m[14] = dl.toByte(); m[15] = ul.toByte()
        return m
    }

    private fun parseConnResp(b: ByteArray): String {
        if (b.size < 14) return "short(${b.size})"
        val msgId = ((b[8].toInt() and 0xff) shl 8) or (b[9].toInt() and 0xff)
        if (msgId != 3) return "id=$msgId"
        val status = b[12].toInt() and 0xff
        val epid = b[13].toInt() and 0xff
        return if (status == 0) "ep=$epid ✓" else "st=$status"
    }

    private fun readOnce(conn: android.hardware.usb.UsbDeviceConnection, ep: UsbEndpoint, timeoutMs: Int): ByteArray? {
        if (cancelled.get()) return null
        val buf = ByteArray(maxOf(ep.maxPacketSize, 512))
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (cancelled.get()) return null
            val n = conn.bulkTransfer(ep, buf, buf.size, 300)
            if (n > 0) { val cp = buf.copyOf(n); frames.add(cp); return cp }
        }
        return null
    }

    private fun msgIdOf(b: ByteArray?): Int? =
        if (b == null || b.size < 10) null else ((b[8].toInt() and 0xff) shl 8) or (b[9].toInt() and 0xff)

    private fun buildConfigPipe(credits: Int): ByteArray {
        val m = ByteArray(12)
        m[3] = 4; m[8] = 0; m[9] = 5; m[10] = 1; m[11] = (credits and 0xff).toByte()
        return m
    }

    private fun buildSetupComplete(): ByteArray {
        val m = ByteArray(10)
        m[3] = 2; m[8] = 0; m[9] = 4
        return m
    }

    private fun streamPipe(conn: android.hardware.usb.UsbDeviceConnection, ep: UsbEndpoint, durationMs: Int, label: String): Int {
        val total = java.util.concurrent.atomic.AtomicInteger(0)
        val chunks = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            try {
                val buf = ByteArray(16384)
                val until = System.currentTimeMillis() + durationMs
                while (System.currentTimeMillis() < until && chunks.get() < 200) {
                    val n = try { conn.bulkTransfer(ep, buf, buf.size, 150) } catch (e: Throwable) { -1 }
                    if (n > 0) {
                        total.addAndGet(n)
                        val c = chunks.incrementAndGet()
                        if (c <= 30) BeastDiag.log("$label[$c] ${n}B ${BeastDiag.hex(buf.copyOf(n), 96)}")
                    }
                }
            } catch (e: Throwable) {
                BeastDiag.log("$label exc: ${e.message}")
            } finally {
                done.set(true)
            }
        }
        worker.isDaemon = true
        worker.start()
        val deadline = System.currentTimeMillis() + durationMs + 800
        while (!done.get() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100) } catch (e: Throwable) { break }
        }
        val t = total.get()
        BeastDiag.log("$label total ${t}B in ${chunks.get()} reads" + if (t == 0) " (silent/blocked)" else "")
        return t
    }

    private fun buildWmiCmd(epid: Int, cmdId: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val m = ByteArray(8 + 4 + payload.size)
        m[0] = epid.toByte()
        val plen = 4 + payload.size
        m[2] = ((plen ushr 8) and 0xff).toByte(); m[3] = (plen and 0xff).toByte()
        m[8] = ((cmdId ushr 8) and 0xff).toByte(); m[9] = (cmdId and 0xff).toByte()
        m[10] = ((seq ushr 8) and 0xff).toByte(); m[11] = (seq and 0xff).toByte()
        if (payload.isNotEmpty()) payload.copyInto(m, 12)
        return m
    }

    private fun eepRead(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, wordOff: Int, seq: Int): Int =
        ((wmiRegRead(conn, rout, rin, 0x2000 + (wordOff shl 2), seq) ?: -1L) and 0xffff).toInt()

    private fun eep4k(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, word: Int, seq: Int): Int =
        ((wmiRegRead(conn, rout, rin, 0x2000 + (word shl 2), seq) ?: -1L) and 0xffffL).toInt()

    class Eep4k {
        var version = 0; var mac = ""; var rxMask = 0; var txMask = 0
        var antCtrlChain0 = 0L; var antCtrlCommon = 0L
        var switchSettling = 0; var txRxAtten = 0; var rxTxMargin = 0
        var adcDesiredSize = 0; var txEndToRxOn = 0; var thresh62 = 0
        var xpaBiasLvl = 0; var bswAtten = 0; var bswMargin = 0
        var xatten2Db = 0; var xatten2Margin = 0; var modalVersion = 0
        var antdivCtl1 = 0; var antdivCtl2 = 0
        var txGainType = 0
    }

    private fun readEeprom4k(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint): Eep4k? {
        val w = IntArray(48)
        for (i in 0 until 48) {
            val v = eep4k(conn, rout, rin, 64 + i, 600 + i)
            if (v < 0) { BeastDiag.log("EEPROM read failed at word ${64 + i}"); return null }
            w[i] = v
        }
        val b = ByteArray(96)
        for (i in 0 until 48) { b[i * 2] = (w[i] and 0xff).toByte(); b[i * 2 + 1] = ((w[i] shr 8) and 0xff).toByte() }
        fun u8(o: Int) = b[o].toInt() and 0xff
        fun s8(o: Int) = b[o].toInt()
        fun le16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun le32(o: Int) = (le16(o).toLong() or (le16(o + 2).toLong() shl 16)) and 0xffffffffL
        val e = Eep4k()
        e.version = le16(4)
        if ((e.version shr 12) != 0xE) { BeastDiag.log(String.format("EEPROM version 0x%04x unexpected (want major 0xE)", e.version)); return null }
        e.mac = (12..17).joinToString(":") { String.format("%02x", u8(it)) }
        e.rxMask = u8(18); e.txMask = u8(19)
        val m = 32 + 20
        e.antCtrlChain0 = le32(m + 0); e.antCtrlCommon = le32(m + 4)
        e.switchSettling = u8(m + 9); e.txRxAtten = u8(m + 10); e.rxTxMargin = u8(m + 11)
        e.adcDesiredSize = s8(m + 12); e.txEndToRxOn = u8(m + 16); e.thresh62 = u8(m + 18)
        e.xpaBiasLvl = u8(m + 27); e.bswAtten = u8(m + 31); e.bswMargin = u8(m + 32)
        e.xatten2Db = u8(m + 34); e.xatten2Margin = u8(m + 35); e.modalVersion = u8(m + 37)
        e.antdivCtl1 = (u8(m + 39) shr 4) and 0xF
        e.antdivCtl2 = (u8(m + 41) shr 4) and 0xF
        e.txGainType = u8(31)
        BeastDiag.log(String.format("EEPROM 4K: ver=0x%04x mac=%s rx/tx=%d/%d modalVer=%d", e.version, e.mac, e.rxMask, e.txMask, e.modalVersion))
        BeastDiag.log(String.format("  antCtrlChain0=0x%08x antCtrlCommon=0x%08x switchSettling=%d txRxAtten=%d",
            e.antCtrlChain0, e.antCtrlCommon, e.switchSettling, e.txRxAtten))
        BeastDiag.log(String.format("  thresh62=%d adcDesired=%d xpaBias=%d antdiv_ctl1=%d antdiv_ctl2=%d",
            e.thresh62, e.adcDesiredSize, e.xpaBiasLvl, e.antdivCtl1, e.antdivCtl2))
        BeastDiag.log("  txGainType=${e.txGainType} (${if (e.txGainType == 1) "HIGH" else "NORMAL"} power) "
            + "- picks the TX-gain table, which also programs the analog RF bank 0x7820/24/38/6c")
        return e
    }

    private fun applyBoardValues4k(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, e: Eep4k) {
        fun rmw(reg: Int, mask: Long, shift: Int, v: Int, seq: Int) {
            wmiRegRmw(conn, rout, rin, reg, ((v.toLong() shl shift) and mask).toInt(), mask.toInt(), seq)
        }
        wmiRegWrite(conn, rout, rin, 0x9964, e.antCtrlCommon.toInt(), 700)
        wmiRegWrite(conn, rout, rin, 0x9960, e.antCtrlChain0.toInt(), 701)

        run {
            val g1 = wmiRegRead(conn, rout, rin, 0xa20c, 702) ?: -1L
            val g2 = wmiRegRead(conn, rout, rin, 0x9848, 704) ?: -1L
            BeastDiag.log(String.format(
                "  gain block SKIPPED (matches ath9k) — 0xa20c=0x%08x 0x9848=0x%08x left from INI", g1, g2))
        }
        if (e.modalVersion >= 3) {
            val a1 = e.antdivCtl1; val a2 = e.antdivCtl2
            val v = (((a1.toLong() shl 24) and 0x01000000L) or ((a2.toLong() shl 25) and 0x06000000L) or
                     (((a2 shr 2).toLong() shl 27) and 0x18000000L) or (((a1 shr 1).toLong() shl 29) and 0x20000000L) or
                     (((a1 shr 2).toLong() shl 30) and 0x40000000L))
            wmiRegRmw(conn, rout, rin, 0x99ac, v.toInt(), 0x7f000000, 715)
            wmiRegRmw(conn, rout, rin, 0xa208, (((a1 shr 3) shl 13) and 0x2000), 0x2000, 717)
            BeastDiag.log(String.format("  ant diversity 0x99ac=0x%08x (readback 0x%08x)", v, wmiRegRead(conn, rout, rin, 0x99ac, 718) ?: -1L))
        }
        rmw(0x9844, 0x00003F80L, 7, e.switchSettling, 720)
        rmw(0x9850, 0x000000FFL, 0, e.adcDesiredSize and 0xFF, 722)
        rmw(0x9828, 0x00FF0000L, 16, e.txEndToRxOn, 724)
        rmw(0x9864, 0x000FF000L, 12, e.thresh62, 726)
        rmw(0x99b8, 0x000000FFL, 0, e.thresh62, 728)

        if (e.txGainType != 1) {
            writeArray(conn, rout, rin, Ar9271Init.txGainNormal2g, "txGain(NORMAL, from EEPROM)", 790)
        }
        BeastDiag.log("  board values applied from EEPROM")
    }

    private fun hopTo(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, ch: Int, seq: Int) {
        val freq = chFreq(ch).toLong()
        val chanSel = (freq * 0x10000L) / 15L
        val synthOld = wmiRegRead(conn, rout, rin, 0x9874, seq) ?: 0L
        val synth = (synthOld and 0xc0000000L) or 0x30000000L or chanSel
        wmiCmdLog(conn, rout, rin, 4,  "DISABLE_INTR",  seq + 1)
        wmiCmdLog(conn, rout, rin, 11, "DRAIN_TXQ_ALL", seq + 2)
        wmiCmdLog(conn, rout, rin, 13, "STOP_RECV",     seq + 3)
        wmiRegWrite(conn, rout, rin, 0x9874, synth.toInt(), seq + 4)
        wmiRegWrite(conn, rout, rin, 0x9804, 0x000003c0, seq + 5)
        setDeltaSlope(conn, rout, rin, chFreq(ch))

        wmiRegWrite(conn, rout, rin, 0x0008, 0x00000004, seq + 6)
        wmiRegWrite(conn, rout, rin, 0x803c, RX_FILTER_MONITOR, seq + 7)
        wmiRegWrite(conn, rout, rin, 0x8040, -1, seq + 8)
        wmiRegWrite(conn, rout, rin, 0x8044, -1, seq + 9)
        val dg = wmiRegRead(conn, rout, rin, 0x8048, seq + 10) ?: 0L
        wmiRegWrite(conn, rout, rin, 0x8048, (dg and (0x20L or 0x02000000L).inv()).toInt(), seq + 11)
        wmiCmdLog(conn, rout, rin, 12, "START_RECV",    seq + 12)
        wmiCmdLog2(conn, rout, rin, 15, byteArrayOf(0, 1), "SET_MODE", seq + 13)
        wmiCmdLog(conn, rout, rin, 5,  "ENABLE_INTR",   seq + 14)
        curChannel = ch
    }

    private fun setDeltaSlope(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, chanMhz: Int) {
        fun vals(cs: Int): Pair<Int, Int> {
            var e = 31
            while (e > 0) { if (((cs shr e) and 1) == 1) break; e-- }
            e = 14 - (e - 24)
            val man = cs + (1 shl (24 - e - 1))
            return Pair(man shr (24 - e), e - 16)
        }
        val cs = 0x64000000L.toInt().let { (0x64000000L / chanMhz).toInt() }
        val (m1, e1) = vals(cs)
        var v = wmiRegRead(conn, rout, rin, 0x9814, 730) ?: 0L
        v = (v and 0xFFFE0000L.inv()) or ((m1.toLong() shl 17) and 0xFFFE0000L)
        v = (v and 0x0001E000L.inv()) or ((e1.toLong() shl 13) and 0x0001E000L)
        wmiRegWrite(conn, rout, rin, 0x9814, v.toInt(), 731)
        val (m2, e2) = vals((9 * cs) / 10)
        var h = wmiRegRead(conn, rout, rin, 0x99D0, 732) ?: 0L
        h = (h and 0x0007FFF0L.inv()) or ((m2.toLong() shl 4) and 0x0007FFF0L)
        h = (h and 0x0000000FL.inv()) or (e2.toLong() and 0x0000000FL)
        wmiRegWrite(conn, rout, rin, 0x99D0, h.toInt(), 733)
        BeastDiag.log("delta_slope: TIMING3 man=$m1 exp=$e1 | HALFGI man=$m2 exp=$e2")
    }

    private fun aniSet(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint,
                       ofdmWeak: Boolean, firstepLvl: Int, spurLvl: Int) {
        fun rmw(reg: Int, mask: Long, shift: Int, v: Int, seq: Int) {
            wmiRegRmw(conn, rout, rin, reg, ((v.toLong() shl shift) and mask).toInt(), mask.toInt(), seq)
        }
        if (!ofdmWeak) {
            rmw(0x986C, 0x001FC000L, 14, 127, 740); rmw(0x986C, 0x0FE00000L, 21, 127, 742)
            rmw(0x986C, 0x00003F00L, 8, 63, 744);   rmw(0x9868, 0x00FE0000L, 17, 127, 746)
            rmw(0x9868, 0x7F000000L, 24, 127, 748); rmw(0x9868, 0x0000001FL, 0, 31, 750)
            rmw(0x99C0, 0x001FC000L, 14, 127, 752); rmw(0x99C0, 0x0FE00000L, 21, 127, 754)
            rmw(0x99C0, 0x0000007FL, 0, 127, 756);  rmw(0x99C0, 0x00003F80L, 7, 127, 758)
            val v = wmiRegRead(conn, rout, rin, 0x986C, 760) ?: 0L
            wmiRegWrite(conn, rout, rin, 0x986C, (v and 1L.inv()).toInt(), 761)
        }
        val fs = firstepLvl * 2
        rmw(0x9858, 0x0003F000L, 12, fs, 762)
        rmw(0x9840, 0x00000FC0L, 6, fs, 764)
        val sp = (spurLvl + 1) * 2
        rmw(0x9924, 0x000000FEL, 1, sp, 766)
        rmw(0x99BC, 0x0000FE00L, 9, maxOf(sp - 1, 0), 768)
        BeastDiag.log("ANI: ofdmWeakSig=${if (ofdmWeak) "ON" else "OFF"} firstep=$firstepLvl($fs) spur=$spurLvl($sp)")
    }

    private const val HTC_RX_FRAME_HEADER_SIZE = 40

    fun parseRxStream(buf: ByteArray, seen: MutableMap<String, String>, stats: IntArray): Int {
        var off = 0; var frames = 0; val n = buf.size
        fun u8(i: Int) = buf[i].toInt() and 0xff
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)

        fun handleHtc(ps: Int, plen: Int) {
            if (plen < HTC_RX_FRAME_HEADER_SIZE) return
            frames++
            val status = u8(ps + 10)
            stats[0]++; if (status == 0) stats[1]++
            if ((status and 0x01) != 0) stats[2]++
            if ((status and 0x02) != 0) stats[3]++
            var rssi = u8(ps + 12); if (rssi > 127) rssi -= 256
            val dlen = (u8(ps + 8) shl 8) or u8(ps + 9)

            if (status == 0 && dlen > stats[4]) stats[4] = dlen
            if ((status and 0x02) != 0) return
            val d = ps + HTC_RX_FRAME_HEADER_SIZE
            if (d + 24 > n || dlen < 24) return
            val fc = u8(d)
            val ftype = (fc shr 2) and 3; val sub = (fc shr 4) and 0xF

            if (status != 0) { stats[5]++; return }

            fun mac(at: Int) = (at until at + 6).joinToString(":") { String.format("%02x", u8(it)) }

            fun isRealDevice(m: String) = m != "ff:ff:ff:ff:ff:ff" &&
                !m.startsWith("01:00:5e") && !m.startsWith("33:33") && !m.startsWith("01:80:c2") &&
                m != "00:00:00:00:00:00"

            val end = minOf(n, d + dlen)
            fun readSsid(from: Int): String? {
                var i = from
                while (i + 2 <= end) {
                    val tag = u8(i); val tl = u8(i + 1)
                    if (i + 2 + tl > end) break
                    if (tag == 0) return String(buf, i + 2, tl, Charsets.UTF_8).trim()
                    i += 2 + tl
                }
                return null
            }
            fun touch(m: String, ap: Boolean): Sighting? {
                if (!isRealDevice(m)) return null
                val s = sightings.getOrPut(m) {
                    Sighting(mac = m, isAp = ap).also {
                        BeastDiag.log("  ${if (ap) "AP " else "DEV"} $m  rssi=$rssi")
                    }
                }
                s.frames++
                if (rssi > s.rssi) s.rssi = rssi
                s.lastSeen = System.currentTimeMillis()
                s.channel = curChannel
                return s
            }

            val fc1 = u8(d + 1)
            val toDs = fc1 and 0x01
            val fromDs = (fc1 shr 1) and 0x01
            val a1 = d + 4; val a2 = d + 10; val a3 = d + 16

            when (ftype) {
                0 -> when (sub) {
                    8, 5 -> {
                        val b = mac(a3)
                        val ssid = readSsid(d + 36) ?: return
                        touch(b, ap = true)?.let { if (it.ssid == null) it.ssid = ssid }
                        if (!seen.containsKey(b)) {
                            seen[b] = ssid
                            BeastDiag.log("      ssid=${if (ssid.isEmpty()) "<hidden>" else ssid}")
                        }
                    }
                    4 -> {
                        val c = mac(a2)
                        val probed = readSsid(d + 24)

                        if (com.rocketgod.warble.classify.NotableDevices.isFlockWildcardProbe(c, probed))
                            touch(c, ap = true)?.let { it.flock = true }
                        if (captureClients) touch(c, ap = false)?.let { s ->
                            if (!probed.isNullOrEmpty()) {
                                if (s.probedSsids.add(probed))
                                    BeastDiag.log("      probe-req $c -> \"$probed\"")
                            }
                        }
                    }
                    0, 1, 2, 3, 10, 11, 12 -> if (captureClients) {
                        touch(mac(a2), ap = false)?.bssid = mac(a3)
                    }
                }
                2 -> {

                    PmkidCapture.scan(buf, d, end, 0, curChannel, rssi) { m -> sightings[m]?.ssid }
                    if (captureClients) when {
                        toDs == 1 && fromDs == 0 ->
                            touch(mac(a2), ap = false)?.bssid = mac(a1)
                        toDs == 0 && fromDs == 1 ->
                            touch(mac(a1), ap = false)?.bssid = mac(a2)
                        toDs == 0 && fromDs == 0 -> {
                            touch(mac(a2), ap = false)?.bssid = mac(a3)
                            touch(mac(a1), ap = false)
                        }
                        else -> touch(mac(a2), ap = false)
                    }
                }
                1 -> if (captureClients && (sub == 11 || sub == 10)) touch(mac(a2), ap = false)
            }
        }

        while (off + 4 <= n) {

            if (le16(off + 2) == 0x4e00) {
                val pktLen = le16(off)
                if (pktLen in 12..1700 && off + 4 + pktLen <= n) {
                    val hs = off + 4
                    if (u8(hs) == 3) {
                        var hlen = (u8(hs + 2) shl 8) or u8(hs + 3)
                        if ((u8(hs + 1) and 0x02) != 0) hlen -= u8(hs + 4)
                        if (hlen > 0 && hlen + 8 <= pktLen + 4) handleHtc(hs + 8, hlen)
                    }
                    var nxt = off + 4 + pktLen
                    nxt += (4 - (nxt and 3)) and 3
                    off = nxt
                    continue
                }
            }

            if (off + 8 <= n && u8(off) in 1..9) {
                val plen = (u8(off + 2) shl 8) or u8(off + 3)
                if (plen in 12..1700 && off + 8 + plen <= n) {
                    var pl = plen
                    if ((u8(off + 1) and 0x02) != 0) pl -= u8(off + 4)
                    if (u8(off) == 3 && pl > 0) handleHtc(off + 8, pl)
                    var nxt = off + 8 + plen
                    nxt += (4 - (nxt and 3)) and 3
                    off = nxt
                    continue
                }
            }
            off++
        }
        return frames
    }

    private fun wmiRegRmw(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint,
                          reg: Int, setBits: Int, clrBits: Int, seq: Int) {
        val p = ByteArray(12)
        fun be(o: Int, v: Int) { p[o]=(v ushr 24).toByte(); p[o+1]=(v ushr 16).toByte(); p[o+2]=(v ushr 8).toByte(); p[o+3]=v.toByte() }
        be(0, reg); be(4, setBits); be(8, clrBits)
        val cmd = buildWmiCmd(epid = 1, cmdId = 32, seq = seq, payload = p)
        conn.bulkTransfer(rout, cmd, cmd.size, 1000)
        readOnce(conn, rin, 600)
    }

    private fun wmiRegRead(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, reg: Int, seq: Int): Long? {
        if (cancelled.get()) return null
        val payload = byteArrayOf(
            ((reg ushr 24) and 0xff).toByte(), ((reg ushr 16) and 0xff).toByte(),
            ((reg ushr 8) and 0xff).toByte(), (reg and 0xff).toByte()
        )
        val cmd = buildWmiCmd(epid = 1, cmdId = 0x14, seq = seq, payload = payload)
        val sent = conn.bulkTransfer(rout, cmd, cmd.size, 1000)
        val resp = readOnce(conn, rin, 1200)
        BeastDiag.log(String.format("REG_READ 0x%04x sent=%d resp=%s", reg, sent, BeastDiag.hex(resp)))
        if (resp == null || resp.size < 16) return null
        return (((resp[12].toInt() and 0xff).toLong() shl 24) or
                ((resp[13].toInt() and 0xff).toLong() shl 16) or
                ((resp[14].toInt() and 0xff).toLong() shl 8) or
                (resp[15].toInt() and 0xff).toLong())
    }

    private fun wmiRegWrite(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, reg: Int, value: Int, seq: Int) {
        if (cancelled.get()) return
        val payload = byteArrayOf(
            ((reg ushr 24) and 0xff).toByte(), ((reg ushr 16) and 0xff).toByte(),
            ((reg ushr 8) and 0xff).toByte(), (reg and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(), ((value ushr 16) and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(), (value and 0xff).toByte()
        )
        val cmd = buildWmiCmd(epid = 1, cmdId = 0x15, seq = seq, payload = payload)
        val sent = conn.bulkTransfer(rout, cmd, cmd.size, 1000)
        val resp = readOnce(conn, rin, 800)
        BeastDiag.log(String.format("REG_WRITE 0x%04x=0x%08x sent=%d ack=%s", reg, value, sent, if (resp != null) "y" else "n"))
    }

    private fun regSet(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, reg: Int, bits: Long, seq: Int): Int {
        val v = wmiRegRead(conn, rout, rin, reg, seq) ?: 0L
        wmiRegWrite(conn, rout, rin, reg, (v or bits).toInt(), seq + 1)
        return seq + 2
    }
    private fun regClr(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, reg: Int, bits: Long, seq: Int): Int {
        val v = wmiRegRead(conn, rout, rin, reg, seq) ?: 0L
        wmiRegWrite(conn, rout, rin, reg, (v and bits.inv()).toInt(), seq + 1)
        return seq + 2
    }
    private fun waitClear(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, reg: Int, mask: Long, tries: Int, seq: Int): Boolean {
        for (i in 0 until tries) {
            val v = wmiRegRead(conn, rout, rin, reg, seq + i) ?: return false
            if ((v and mask) == 0L) return true
            try { Thread.sleep(3) } catch (t: Throwable) {}
        }
        return false
    }

    private fun wmiCmdLog(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint,
                          cmd: Int, name: String, seq: Int) {
        val c = buildWmiCmd(epid = 1, cmdId = cmd, seq = seq)
        conn.bulkTransfer(rout, c, c.size, 1000)
        val r = readOnce(conn, rin, 800)
        if (!BeastDiag.quiet) BeastDiag.log("  $name -> ${if (r != null) "ack" else "no reply"}")
    }

    private fun wmiCmdLog2(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint,
                           cmd: Int, payload: ByteArray, name: String, seq: Int) {
        val c = buildWmiCmd(epid = 1, cmdId = cmd, seq = seq, payload = payload)
        conn.bulkTransfer(rout, c, c.size, 1000)
        val r = readOnce(conn, rin, 800)
        if (!BeastDiag.quiet) BeastDiag.log("  $name -> ${if (r != null) "ack" else "no reply"}")
    }

    private fun writeArray(conn: android.hardware.usb.UsbDeviceConnection, rout: UsbEndpoint, rin: UsbEndpoint, arr: LongArray, label: String, seq0: Int): Int {
        var seq = seq0
        val n = arr.size / 2
        var i = 0; var acks = 0; var batches = 0
        while (i < n) {
            if (cancelled.get()) { BeastDiag.log("$label aborted (adapter gone)"); break }
            val batch = minOf(16, n - i)
            val payload = ByteArray(batch * 8)
            for (j in 0 until batch) {
                val reg = arr[(i + j) * 2]
                val v = arr[(i + j) * 2 + 1]
                val o = j * 8
                payload[o] = ((reg ushr 24) and 0xff).toByte(); payload[o + 1] = ((reg ushr 16) and 0xff).toByte()
                payload[o + 2] = ((reg ushr 8) and 0xff).toByte(); payload[o + 3] = (reg and 0xff).toByte()
                payload[o + 4] = ((v ushr 24) and 0xff).toByte(); payload[o + 5] = ((v ushr 16) and 0xff).toByte()
                payload[o + 6] = ((v ushr 8) and 0xff).toByte(); payload[o + 7] = (v and 0xff).toByte()
            }
            val cmd = buildWmiCmd(epid = 1, cmdId = 0x15, seq = seq++, payload = payload)
            conn.bulkTransfer(rout, cmd, cmd.size, 1000)
            if (readOnce(conn, rin, 400) != null) acks++
            batches++; i += batch
        }
        BeastDiag.log("$label: $n regs / $batches batches / $acks acks")
        return seq
    }
}
