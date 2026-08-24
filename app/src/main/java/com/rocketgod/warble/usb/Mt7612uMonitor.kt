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

object Mt7612uMonitor {
    @Volatile var running = false; private set
    @Volatile private var stop = false
    @Volatile var captureClients = true
    @Volatile private var epResp: UsbEndpoint? = null
    private var mcuLogged = 0
    @Volatile private var mcuGain = 0

    private var rssiOff2g = 0; private var rssiOff5g = 0; private var lna2g = 0; private var lna5g = 0

    private class Sighting(val mac: String, var isAp: Boolean) {
        var ssid: String? = null; var bssid: String? = null
        var rssi = -128; var channel = 0
        var good = false
        var hits = 0
        var lastSeen = 0L
        var flock = false
        fun trusted() = good || hits >= 2 || flock
    }
    private val sightings = LinkedHashMap<String, Sighting>()

    private val FRESH_MS get() = (UsbBeast.effectiveSweepMs(HOP, DWELL_MS) * 9 / 5).coerceAtLeast(3000L)
    private const val PRUNE_MS = 60_000L

    private const val REPORT_MS = 10_000L

    private const val OUT_VENDOR = 0x40; private const val IN_VENDOR = 0xC0
    private const val MULTI_WRITE = 0x06; private const val MULTI_READ = 0x07
    private const val WRITE_CFG = 0x46; private const val READ_CFG = 0x47; private const val READ_EEPROM = 0x09

    private const val WLAN_FUN_CTRL = 0x0080
    private const val MAC_CSR0 = 0x1000
    private const val MAC_SYS_CTRL = 0x1004
    private const val MAC_STATUS = 0x1200
    private const val CH_IDLE = 0x1130; private const val CH_BUSY = 0x1134; private const val RX_STAT_0 = 0x1700
    private const val WPDMA_GLO_CFG = 0x0208
    private const val RX_FILTR_CFG = 0x1400
    private const val US_CYC_CFG = 0x02a4
    private const val TXOP_CTRL_CFG = 0x1340
    private const val COEXCFG0 = 0x0040
    private const val EXT_CCA_CFG = 0x141c
    private const val TX_ALC_CFG_4 = 0x13c0
    private const val PBF_TX_MAX_PCNT = 0x0408
    private const val PBF_RX_MAX_PCNT = 0x040c
    private const val TX_LINK_CFG = 0x1350
    private const val AUTO_RSP_CFG = 0x1404
    private const val MAX_LEN_CFG = 0x1018
    private const val WMM_AIFSN = 0x0214
    private const val WMM_CWMIN = 0x0218
    private const val WMM_CWMAX = 0x021c
    private const val USB_U3DMA_CFG = 0x9018
    private const val CH_TIME_CFG = 0x110c
    private const val TX_PIN_CFG = 0x1328
    private const val TXOP_HLDR_ET = 0x1608
    private const val XIFS_TIME_CFG = 0x1100
    private const val BKOFF_SLOT_CFG = 0x1104
    private const val FCE_L2_STUFF = 0x080c
    private const val XO_CTRL5 = 0x0114
    private const val XO_CTRL6 = 0x0118
    private const val XO_CTRL7 = 0x011c
    private const val ED_CCA_TIMER = 0x1140
    private const val ADDR_504 = 0x0504
    private const val ADDR_50C = 0x050c

    private const val WLAN_EN = 1 shl 0; private const val WLAN_CLK_EN = 1 shl 1
    private const val WLAN_RESET_RF = 1 shl 2; private const val FRC_WL_ANT_SEL = 1 shl 5
    private const val SYS_CTRL_RESET_CSR = 1 shl 0; private const val SYS_CTRL_RESET_BBP = 1 shl 1
    private const val SYS_CTRL_ENABLE_TX = 1 shl 2; private const val SYS_CTRL_ENABLE_RX = 1 shl 3
    private const val STATUS_TX = 1 shl 0; private const val STATUS_RX = 1 shl 1
    private const val WPDMA_TX_DMA_BUSY = 1 shl 1; private const val WPDMA_RX_DMA_BUSY = 1 shl 3
    private const val DMA_RX_DROP_OR_PAD = 1 shl 18; private const val DMA_RX_BULK_AGG_EN = 1 shl 21
    private const val DMA_RX_BULK_EN = 1 shl 22; private const val DMA_TX_BULK_EN = 1 shl 23
    private const val COEX_EN = 1 shl 0
    private const val FILTR_CRC_ERR = 1 shl 0; private const val FILTR_PHY_ERR = 1 shl 1

    private const val DMA_HDR = 4; private const val RXWI_LEN = 32
    private const val RXINFO_L2PAD = 1 shl 14; private const val RXINFO_CRCERR = 1 shl 8

    private const val EE_SIZE = 512
    private const val EE_MAC_ADDR = 0x004
    private const val EE_NIC_CONF_0 = 0x034
    private const val EE_NIC_CONF_1 = 0x036
    private const val EE_LNA_GAIN = 0x044
    private const val EE_RSSI_OFFSET_2G_0 = 0x046
    private const val EE_RSSI_OFFSET_2G_1 = 0x048
    private const val EE_RSSI_OFFSET_5G_0 = 0x04a

    private const val CMD_FUN_SET_OP = 1; private const val CMD_LOAD_CR = 2; private const val CMD_INIT_GAIN_OP = 3
    private const val CMD_POWER_SAVING_OP = 20; private const val CMD_SWITCH_CHANNEL_OP = 30; private const val CMD_CALIBRATION_OP = 31
    private const val Q_SELECT = 1; private const val RADIO_ON = 0x31; private const val MT_RF_BBP_CR = 2

    private const val MCU_CAL_TEMP = 2; private const val MCU_CAL_RXDCOC = 3; private const val MCU_CAL_RC = 4
    private const val MCU_CAL_LC = 6; private const val MCU_CAL_TX_LOFT = 7; private const val MCU_CAL_TXIQ = 8
    private const val MCU_CAL_RXIQC_FI = 12; private const val MCU_CAL_TX_SHAPING = 15

    private const val EE_XTAL_TRIM_1 = 0x03a; private const val EE_XTAL_TRIM_2 = 0x09e; private const val EE_NIC_CONF_2 = 0x042

    private const val TX_BAND_CFG = 0x132c
    private const val BBP_CORE1 = 0x2004
    private const val BBP_AGC0 = 0x2300; private const val BBP_AGC2 = 0x2308; private const val BBP_AGC7 = 0x231c
    private const val BBP_AGC11 = 0x232c; private const val BBP_AGC61 = 0x23f4
    private const val BBP_TXO4 = 0x2610; private const val BBP_RXO13 = 0x2934

    private val HOP = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
        36, 40, 44, 48,
        52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165
    )

    private const val DWELL_MS = 125L
    private fun freqOf(ch: Int) = if (ch <= 14) 2407 + ch * 5 else 5000 + ch * 5

    private var seq = 0

    fun abort() { stop = true }

    fun run(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit,
            onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit) {
        if (running) return
        running = true; stop = false; mcuLogged = 0; sightings.clear(); lastCalMs.clear()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("MT7612U: couldn't reopen adapter"); running = false; return }
        var epRx: UsbEndpoint? = null; var epCmdOut: UsbEndpoint? = null
        try {
            val intf = device.getInterface(0)
            conn.claimInterface(intf, true)
            val bulkIn = ArrayList<UsbEndpoint>(); val bulkOut = ArrayList<UsbEndpoint>()
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn.add(ep) else bulkOut.add(ep)
                }
            }
            epRx = bulkIn.getOrNull(0)
            epResp = bulkIn.getOrNull(1)
            epCmdOut = bulkOut.getOrNull(0)
            if (epRx == null || epCmdOut == null) { BeastDiag.log("MT7612U: missing bulk endpoints"); onStatus("MT7612U: no RX endpoint"); return }
            BeastDiag.log("=== MT7612U bring-up (mt76x2u) ===")
            BeastDiag.log("MT7612U: bulk IN=[${bulkIn.joinToString { "0x%02x".format(it.address) }}] OUT=[${bulkOut.joinToString { "0x%02x".format(it.address) }}]")
            BeastDiag.log("MT7612U: RX EP=0x%02x  cmd-out EP=0x%02x  cmd-resp EP=%s".format(epRx.address, epCmdOut.address, epResp?.let { "0x%02x".format(it.address) } ?: "none"))

            onStatus("MT7612U · powering on radio…")
            resetWlan(conn)
            powerOn(conn)
            if (!waitForMac(conn)) { fail(onStatus, "MAC not ready after power-on"); return }
            BeastDiag.log("MT7612U: MAC powered on ✓")

            initDma(conn)
            mcuCmd(conn, epCmdOut, CMD_FUN_SET_OP, leWords(Q_SELECT, 1), false)
            mcuCmd(conn, epCmdOut, CMD_POWER_SAVING_OP, leWords(RADIO_ON, 0), false)
            BeastDiag.log("MT7612U: MCU radio on ✓")
            onStatus("MT7612U · radio on · MAC reset…")

            macReset(conn)
            val eeprom = readEeprom(conn)
            val mac = eeprom.copyOfRange(EE_MAC_ADDR, EE_MAC_ADDR + 6)
            BeastDiag.log("MT7612U: MAC addr " + mac.joinToString(":") { "%02x".format(it.toInt() and 0xff) })
            mcuGain = computeMcuGain(eeprom)
            computeRssiCal(eeprom)
            BeastDiag.log("MT7612U: RX gain (LNA) mcu_gain=0x%08x  rssi_off(2g=%d 5g=%d) lna(2g=%d 5g=%d)".format(
                mcuGain, rssiOff2g, rssiOff5g, lna2g, lna5g))
            fixupXtal(conn, eeprom)
            regRmw(conn, US_CYC_CFG, 0xff, 0x1e)
            regWr(conn, TXOP_CTRL_CFG, 0x583f)

            onStatus("MT7612U · loading RF/BBP registers…")
            loadCr(conn, epCmdOut, eeprom, HOP[0])
            BeastDiag.log("MT7612U: RF/BBP CR loaded ✓")

            onStatus("MT7612U · tuning + calibrating radio…")
            phySetRxpath(conn)
            tuneChannel(conn, epCmdOut, HOP[0], scan = false)
            BeastDiag.log("MT7612U: tuned to ch ${HOP[0]} ✓")

            regWr(conn, RX_FILTR_CFG, FILTR_CRC_ERR or FILTR_PHY_ERR)
            macStart(conn)

            BeastDiag.log("MT7612U: SYS_CTRL=0x%08x STATUS=0x%08x U3DMA=0x%08x RXFILTR=0x%08x AGC0=0x%08x".format(
                regRd(conn, MAC_SYS_CTRL), regRd(conn, MAC_STATUS), cfgRd(conn, USB_U3DMA_CFG),
                regRd(conn, RX_FILTR_CFG), regRd(conn, BBP_AGC0)))
            BeastDiag.log("MT7612U: RX enabled — monitor live on ch ${HOP[0]} ✓")
            onStatus("MT7612U · live · scanning both bands…")

            rxLoop(conn, epCmdOut, epRx, onFrame, onStatus)
        } catch (e: Exception) {
            BeastDiag.log("MT7612U: bring-up error — ${e.message}")
            onStatus("MT7612U: error · ${e.message}")
        } finally {
            runCatching { conn.close() }
            running = false
        }
    }

    private fun fail(onStatus: (String) -> Unit, why: String) {
        BeastDiag.log("MT7612U: bring-up FAILED — $why"); onStatus("MT7612U: $why")
    }

    private fun resetWlan(conn: UsbDeviceConnection) {
        var v = regRd(conn, WLAN_FUN_CTRL) and FRC_WL_ANT_SEL.inv()
        if (v and WLAN_EN != 0) {
            regWr(conn, WLAN_FUN_CTRL, v or WLAN_RESET_RF); sleep(1)
            v = v and WLAN_RESET_RF.inv()
        }
        regWr(conn, WLAN_FUN_CTRL, v); sleep(1)
        v = v or WLAN_EN; regWr(conn, WLAN_FUN_CTRL, v); sleep(1)
        v = v or WLAN_CLK_EN; regWr(conn, WLAN_FUN_CTRL, v); sleep(1)
    }

    private fun powerOn(conn: UsbDeviceConnection) {
        cfgSet(conn, 0x148, 1 shl 0)
        val up = (1 shl 28) or (1 shl 12) or (1 shl 13)
        cfgPoll(conn, 0x148, up, up, 1000)
        cfgClear(conn, 0x148, 0x7f shl 16); sleep(1)
        cfgClear(conn, 0x148, 0xf shl 24); sleep(1)
        cfgSet(conn, 0x148, 0xf shl 24); cfgClear(conn, 0x148, 0xfff)
        cfgClear(conn, 0x1204, 1 shl 3)
        cfgSet(conn, 0x80, 1 shl 0)
        cfgClear(conn, 0x64, 1 shl 18)
        powerOnRf(conn, 0); powerOnRf(conn, 1)
    }
    private fun powerOnRf(conn: UsbDeviceConnection, unit: Int) {
        val s = if (unit != 0) 8 else 0
        cfgSet(conn, 0x130, 1 shl s); sleep(1)
        cfgSet(conn, 0x130, ((1 shl 1) or (1 shl 3) or (1 shl 4) or (1 shl 5)) shl s); sleep(1)
        cfgClear(conn, 0x130, (1 shl 2) shl s); sleep(1)

        cfgSet(conn, 0x130, (1 shl 0) or (1 shl 16)); sleep(1)
        cfgClear(conn, 0x1c, 0xff); cfgSet(conn, 0x1c, 0x30)
        cfgWr(conn, 0x14, 0x484f); sleep(1)
        cfgSet(conn, 0x130, 1 shl 17); sleep(1)
        cfgClear(conn, 0x130, 1 shl 16); sleep(1)
        cfgSet(conn, 0x14c, (1 shl 19) or (1 shl 20))
        regWr(conn, 0x530, 0xf)
    }

    private fun initDma(conn: UsbDeviceConnection) {
        var v = cfgRd(conn, USB_U3DMA_CFG)
        v = v or DMA_RX_DROP_OR_PAD or DMA_RX_BULK_EN or DMA_TX_BULK_EN
        v = v and DMA_RX_BULK_AGG_EN.inv()
        cfgWr(conn, USB_U3DMA_CFG, v)
    }

    private fun macReset(conn: UsbDeviceConnection) {
        regWr(conn, WPDMA_GLO_CFG, (1 shl 4) or (1 shl 5))
        regWr(conn, PBF_TX_MAX_PCNT, 0xefef3f1f.toInt())
        regWr(conn, PBF_RX_MAX_PCNT, 0xfebf)
        for (p in MAC_INITVALS) regWr(conn, p[0], p[1])
        regWr(conn, TX_LINK_CFG, 0x1020)
        regWr(conn, AUTO_RSP_CFG, 0x13)
        regWr(conn, MAX_LEN_CFG, 0x2f00)
        regWr(conn, WMM_AIFSN, 0x2273); regWr(conn, WMM_CWMIN, 0x2344); regWr(conn, WMM_CWMAX, 0x34aa)
        regClear(conn, MAC_SYS_CTRL, SYS_CTRL_RESET_CSR or SYS_CTRL_RESET_BBP)
        regClear(conn, COEXCFG0, COEX_EN)
        regSet(conn, EXT_CCA_CFG, 0xf000)
        regClear(conn, TX_ALC_CFG_4, 1 shl 31)
    }

    private fun macStart(conn: UsbDeviceConnection) {
        regWr(conn, MAC_SYS_CTRL, SYS_CTRL_ENABLE_TX)
        waitWpdma(conn)
        regWr(conn, RX_FILTR_CFG, FILTR_CRC_ERR or FILTR_PHY_ERR)
        regWr(conn, MAC_SYS_CTRL, SYS_CTRL_ENABLE_TX or SYS_CTRL_ENABLE_RX)
        waitWpdma(conn)

        regWr(conn, CH_TIME_CFG, 0x15f)
        regRd(conn, CH_BUSY); regRd(conn, CH_IDLE)
    }

    private fun phySetRxpath(conn: UsbDeviceConnection) {
        val v = regRd(conn, BBP_AGC0)
        regWr(conn, BBP_AGC0, (v and (1 shl 4).inv()) or (1 shl 3))
    }

    private val lastCalMs = HashMap<Int, Long>()
    private const val CAL_TTL_MS = 30_000L

    private fun tuneChannel(conn: UsbDeviceConnection, ep: UsbEndpoint, ch: Int, scan: Boolean) {
        val is5 = if (ch <= 14) 0 else 1
        if (is5 == 0) { regSet(conn, TX_BAND_CFG, 1 shl 2); regClear(conn, TX_BAND_CFG, 1 shl 1) }
        else { regClear(conn, TX_BAND_CFG, 1 shl 2); regSet(conn, TX_BAND_CFG, 1 shl 1) }

        regRmw(conn, BBP_CORE1, 0x18, 0)
        regRmw(conn, BBP_AGC0, 0x7000, 1 shl 12)

        val w = !scan
        setChannel(conn, ep, ch, scan)

        val now = System.currentTimeMillis()
        if (!scan || now - (lastCalMs[ch] ?: 0L) > CAL_TTL_MS) {
            mcuCmd(conn, ep, CMD_INIT_GAIN_OP, leWords(ch or (1 shl 31), mcuGain), w)
            regSet(conn, BBP_RXO13, 1 shl 10)
            mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_RXDCOC, ch), w)
            mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_RC, 0), w)
            regWr(conn, BBP_AGC61, 0xff64a4e2.toInt()); regWr(conn, BBP_AGC7, 0x08081010)
            regWr(conn, BBP_AGC11, 0x00000404); regWr(conn, BBP_AGC2, 0x00007070)
            regWr(conn, TXOP_CTRL_CFG, 0x04101b3f)
            regSet(conn, BBP_TXO4, 1 shl 25); regSet(conn, BBP_RXO13, 1 shl 8)
            lastCalMs[ch] = now
        }

        if (scan) return

        if (is5 == 1) mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_LC, 0), true)
        mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TX_LOFT, is5), true)
        mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TXIQ, is5), true)
        mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_RXIQC_FI, is5), true)
        mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TEMP, 0), true)
        mcuCmd(conn, ep, CMD_CALIBRATION_OP, leWords(MCU_CAL_TX_SHAPING, 0), true)
        edccaInit(conn)
    }

    private fun edccaInit(conn: UsbDeviceConnection) {
        regSet(conn, TX_LINK_CFG, 1 shl 12)
        regClear(conn, TXOP_CTRL_CFG, 1 shl 20)
        regWr(conn, BBP_AGC2, 0x00007070)
        regSet(conn, TXOP_HLDR_ET, 1 shl 1)
        regSet(conn, MAC_SYS_CTRL, SYS_CTRL_ENABLE_TX)
        regSet(conn, AUTO_RSP_CFG, 1 shl 0)

        val v = regRd(conn, TX_PIN_CFG) or 0xf or (0xf shl 8) or (1 shl 16) or (1 shl 18)
        regWr(conn, TX_PIN_CFG, v)
        regRd(conn, ED_CCA_TIMER)
    }

    private fun fixupXtal(conn: UsbDeviceConnection, ee: ByteArray) {
        var ev = le16(ee, EE_XTAL_TRIM_2)
        var offset = ev and 0x7f
        if ((ev and 0xff) == 0xff) offset = 0
        else if (ev and 0x80 != 0) offset = -offset
        ev = ev shr 8
        if (ev == 0x00 || ev == 0xff) {
            ev = le16(ee, EE_XTAL_TRIM_1) and 0xff
            if (ev == 0x00 || ev == 0xff) ev = 0x14
        }
        ev = ev and 0x7f
        val c2 = (ev + offset) and 0x7f
        cfgWr(conn, XO_CTRL5, (cfgRd(conn, XO_CTRL5) and (0x7f shl 8).inv()) or (c2 shl 8))
        cfgSet(conn, XO_CTRL6, 0x7f shl 8)
        regWr(conn, ADDR_504, 0x06000000); regWr(conn, ADDR_50C, 0x08800000); sleep(5); regWr(conn, ADDR_504, 0)
        regRmw(conn, XIFS_TIME_CFG, 0xff shl 8, 0xd shl 8)
        regRmw(conn, BKOFF_SLOT_CFG, 0xf shl 8, 1 shl 8)
        regClear(conn, FCE_L2_STUFF, 1 shl 4)
        when ((le16(ee, EE_NIC_CONF_2) shr 9) and 0x3) {
            0 -> regWr(conn, XO_CTRL7, 0x5c1fee80)
            1 -> regWr(conn, XO_CTRL7, 0x5c1feed0.toInt())
        }
    }

    private fun setChannel(conn: UsbDeviceConnection, ep: UsbEndpoint, ch: Int, scan: Boolean) {

        val w = !scan
        val m = byteArrayOf(ch.toByte(), if (scan) 1 else 0, 0, 0, 0x03, 0x00, 0x00, 0x00)
        mcuCmd(conn, ep, CMD_SWITCH_CHANNEL_OP, m, w); sleep(6)
        m[6] = 0xe0.toByte()
        mcuCmd(conn, ep, CMD_SWITCH_CHANNEL_OP, m, w)
    }
    private fun loadCr(conn: UsbDeviceConnection, ep: UsbEndpoint, ee: ByteArray, ch: Int) {
        val nc0 = le16(ee, EE_NIC_CONF_0); val nc1 = le16(ee, EE_NIC_CONF_1)
        val cfg = (1 shl 31) or ((nc0 shr 8) and 0xff) or ((nc1 shl 8) and 0xff00)
        val m = ByteArray(8); m[0] = MT_RF_BBP_CR.toByte(); m[2] = ch.toByte(); putLe32(m, 4, cfg)
        mcuCmd(conn, ep, CMD_LOAD_CR, m, true)
    }
    private fun mcuCmd(conn: UsbDeviceConnection, ep: UsbEndpoint, cmd: Int, payload: ByteArray, waitResp: Boolean) {
        seq = (seq + 1) and 0xf; if (seq == 0) seq = 1
        val rounded = (payload.size + 3) and 3.inv()
        val txinfo = (rounded and 0xffff) or (2 shl 27) or (seq shl 16) or (cmd shl 20) or (1 shl 30)
        val buf = ByteArray(4 + rounded + 4)
        putLe32(buf, 0, txinfo); System.arraycopy(payload, 0, buf, 4, payload.size)
        conn.bulkTransfer(ep, buf, buf.size, 1000)
        if (waitResp) {
            val rep = epResp
            if (rep != null) {
                val rb = ByteArray(512)
                val n = conn.bulkTransfer(rep, rb, rb.size, 300)
                if (mcuLogged < 5) {
                    mcuLogged++
                    if (n > 0) {
                        val evt = if (n >= 4) (le32(rb, 0) shr 20) and 0xf else -1
                        BeastDiag.log("MT7612U: MCU cmd=$cmd resp=$n B fce=0x%08x evt=$evt".format(if (n >= 4) le32(rb, 0) else 0))
                    } else BeastDiag.log("MT7612U: MCU cmd=$cmd NO RESPONSE (rc=$n)")
                }
            } else sleep(10)
        }
    }
    private fun leWords(vararg w: Int): ByteArray { val b = ByteArray(w.size * 4); for (i in w.indices) putLe32(b, i * 4, w[i]); return b }

    private class RxState {
        var total = 0L; var bufs = 0L; var aliveLogged = false
        var lastLog = 0L; var hopAt = 0L; var lastEmit = 0L; var lastRxAt = 0L; var hopIdx = 0
        fun start() { val t = System.currentTimeMillis(); lastLog = t; hopAt = t; lastEmit = t; lastRxAt = t }
    }

    private fun rxTick(conn: UsbDeviceConnection, epCmd: UsbEndpoint, now: Long, st: RxState,
                       onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        if (now - st.lastLog > 3000) {

            val busy = regRd(conn, CH_BUSY); val idle = regRd(conn, CH_IDLE); val rs0 = regRd(conn, RX_STAT_0)
            val shown = sightings.values.filter { it.trusted() && now - it.lastSeen < REPORT_MS }
            val aps = shown.count { it.isAp }; val cli = shown.size - aps
            if (!st.aliveLogged && busy > 0) {
                st.aliveLogged = true
                BeastDiag.log("MT7612U: ✓ RECEIVER ALIVE — RF energy detected, monitor live (CH_BUSY=$busy)")
            }
            BeastDiag.log("MT7612U: RX ${st.bufs} bufs / ${st.total} B ch ${HOP[st.hopIdx]} | ${shown.size} live ($aps AP · $cli client, ${sightings.size} tracked) | CH_BUSY=$busy IDLE=$idle STAT0(crc=${rs0 and 0xffff} phy=${(rs0 ushr 16) and 0xffff}) | ${PmkidCapture.diag()}")

            if (busy > 50000 && now - st.lastRxAt > 6000) {
                BeastDiag.log("MT7612U: ⚠ RX stalled (energy present, no frames for ${(now - st.lastRxAt) / 1000}s) — re-kicking RX")
                runCatching { reKickRx(conn) }
                st.lastRxAt = now
            }
            st.lastLog = now
        }
        if (now - st.lastEmit > 2000) { emitSightings(onFrame); st.lastEmit = now }
        if (now - st.hopAt > UsbBeast.dwell(DWELL_MS)) {
            val ni = UsbBeast.nextHop(st.hopIdx, HOP)
            if (ni != st.hopIdx) { st.hopIdx = ni; runCatching { tuneChannel(conn, epCmd, HOP[st.hopIdx], scan = true) } }
            drainCmdResp(conn)

            val shown = sightings.values.filter { it.trusted() && now - it.lastSeen < REPORT_MS }
            val aps = shown.count { it.isAp }
            MonitorLive.push(onStatus, "MT7612U", aps, shown.size - aps, HOP[st.hopIdx])
            st.hopAt = now
        }
    }

    private fun rxLoop(conn: UsbDeviceConnection, epCmd: UsbEndpoint, epRx: UsbEndpoint,
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
            BeastDiag.log("MT7612U: async RX unavailable (${reqs.size}/$nbuf reqs) — using synchronous RX")
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
            rxLoopSync(conn, epCmd, epRx, onFrame, onStatus)
            return
        }

        regWr(conn, RX_FILTR_CFG, FILTR_PHY_ERR)
        BeastDiag.log("MT7612U: async RX live — $nbuf buffers in flight, CRC-keep ON (distant APs)")
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
                        val nbytes = b.position()
                        val parseLen = if (nbytes in 1..bufSz) {
                            st.total += nbytes; st.bufs++; st.lastRxAt = now
                            b.rewind(); b.get(scratch, 0, nbytes); nbytes
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
                rxTick(conn, epCmd, now, st, onFrame, onStatus)
            }
        } finally {
            reqs.forEach { runCatching { it.cancel() }; runCatching { it.close() } }
        }
        emitSightings(onFrame)
        BeastDiag.log("MT7612U: RX loop stopped (${st.total} B total)")
    }

    private fun rxLoopSync(conn: UsbDeviceConnection, epCmd: UsbEndpoint, epRx: UsbEndpoint,
                           onFrame: (List<com.rocketgod.warble.scan.RawObservation>) -> Unit, onStatus: (String) -> Unit) {
        val buf = ByteArray(4096)
        val st = RxState().apply { start() }
        while (!stop) {
            val n = conn.bulkTransfer(epRx, buf, buf.size, 500)
            val now = System.currentTimeMillis()
            if (n > 0) {
                st.total += n; st.bufs++; st.lastRxAt = now
                runCatching { parseRxFrame(buf, n, HOP[st.hopIdx]) }
            }
            rxTick(conn, epCmd, now, st, onFrame, onStatus)
        }
        emitSightings(onFrame)
        BeastDiag.log("MT7612U: RX loop stopped (${st.total} B total)")
    }

    private fun drainCmdResp(conn: UsbDeviceConnection) {
        val ep = epResp ?: return
        val tmp = ByteArray(512)
        repeat(6) { if (conn.bulkTransfer(ep, tmp, tmp.size, 1) <= 0) return }
    }

    private fun reKickRx(conn: UsbDeviceConnection) {
        regClear(conn, MAC_SYS_CTRL, SYS_CTRL_ENABLE_RX)
        val v = cfgRd(conn, USB_U3DMA_CFG)
        cfgWr(conn, USB_U3DMA_CFG, v and DMA_RX_BULK_EN.inv()); cfgWr(conn, USB_U3DMA_CFG, v or DMA_RX_BULK_EN)
        regSet(conn, MAC_SYS_CTRL, SYS_CTRL_ENABLE_RX)
    }

    private fun parseRxFrame(b: ByteArray, n: Int, ch: Int) {
        fun u8(i: Int) = b[i].toInt() and 0xff
        if (n < DMA_HDR + RXWI_LEN + 24) return
        val dmaLen = u8(0) or (u8(1) shl 8)
        if (dmaLen < RXWI_LEN + 24 || DMA_HDR + dmaLen > n) return
        val rxwi = DMA_HDR
        val rxinfo = le32(b, rxwi)
        val ctl = le32(b, rxwi + 4)
        val rssiRaw = b[rxwi + 12].toInt()
        val mpduLen = (ctl ushr 16) and 0x3fff
        val pad = if (rxinfo and RXINFO_L2PAD != 0) 2 else 0
        val d = rxwi + RXWI_LEN
        val end = minOf(n, rxwi + RXWI_LEN + mpduLen + pad)
        if (d + 24 > end) return
        val rssi = mt76Rssi(rssiRaw, ch)

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
            s.hits++
            if (goodFrame) s.good = true
            if (rssi > s.rssi) s.rssi = rssi
            s.channel = ch
            s.lastSeen = System.currentTimeMillis()
            return s
        }

        val crc = rxinfo and RXINFO_CRCERR != 0
        val good = !crc
        val fc = u8(d); val ftype = (fc shr 2) and 3; val sub = (fc shr 4) and 0xF
        val fc1 = u8(d + 1); val toDs = fc1 and 0x01; val fromDs = (fc1 shr 1) and 0x01
        val a1 = d + 4; val a2 = d + 10; val a3 = d + 16
        when (ftype) {
            0 -> when (sub) {
                8, 5 -> {
                    val ssid = readSsid(d + 36 + pad)
                    if (good || cleanSsid(ssid)) touch(mac(a3), ap = true, goodFrame = good)?.let {
                        if (ssid != null && it.ssid == null) it.ssid = ssid
                    }
                }

                4 -> {

                    if (good && com.rocketgod.warble.classify.NotableDevices.isFlockWildcardProbe(mac(a2), readSsid(d + 24 + pad)))
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

    private fun mt76Rssi(raw: Int, ch: Int): Int =
        if (ch <= 14) raw + rssiOff2g - lna2g else raw + rssiOff5g - lna5g

    private fun computeRssiCal(ee: ByteArray) {
        fun signExt(v: Int, bits: Int): Int { val s = 1 shl (bits - 1); return if (v and s != 0) v - (1 shl bits) else v }

        fun rssiOff(v: Int): Int { val b0 = v and 0xff; return if (b0 == 0 || b0 == 0xff || b0 and 0x80 == 0) 0 else signExt(b0, 7) }

        fun lnaOf(v: Int): Int { val b0 = v and 0xff; return if (b0 == 0xff) 0 else signExt(b0, 8) }
        rssiOff2g = rssiOff(le16(ee, EE_RSSI_OFFSET_2G_0))
        rssiOff5g = rssiOff(le16(ee, EE_RSSI_OFFSET_5G_0))
        lna2g = lnaOf(le16(ee, EE_LNA_GAIN))
        lna5g = lnaOf(le16(ee, EE_LNA_GAIN) ushr 8)
    }

    private fun regWr(conn: UsbDeviceConnection, addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000)
    }
    private fun regRd(conn: UsbDeviceConnection, addr: Int): Int {
        val b = ByteArray(4)
        return if (conn.controlTransfer(IN_VENDOR, MULTI_READ, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000) == 4) le32(b, 0) else 0
    }
    private fun regSet(conn: UsbDeviceConnection, addr: Int, bits: Int) = regWr(conn, addr, regRd(conn, addr) or bits)
    private fun regClear(conn: UsbDeviceConnection, addr: Int, bits: Int) = regWr(conn, addr, regRd(conn, addr) and bits.inv())
    private fun regRmw(conn: UsbDeviceConnection, addr: Int, mask: Int, v: Int) = regWr(conn, addr, (regRd(conn, addr) and mask.inv()) or v)

    private fun cfgWr(conn: UsbDeviceConnection, addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        conn.controlTransfer(OUT_VENDOR, WRITE_CFG, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000)
    }
    private fun cfgRd(conn: UsbDeviceConnection, addr: Int): Int {
        val b = ByteArray(4)
        return if (conn.controlTransfer(IN_VENDOR, READ_CFG, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000) == 4) le32(b, 0) else 0
    }
    private fun cfgSet(conn: UsbDeviceConnection, addr: Int, bits: Int) = cfgWr(conn, addr, cfgRd(conn, addr) or bits)
    private fun cfgClear(conn: UsbDeviceConnection, addr: Int, bits: Int) = cfgWr(conn, addr, cfgRd(conn, addr) and bits.inv())
    private fun cfgPoll(conn: UsbDeviceConnection, addr: Int, mask: Int, want: Int, ms: Int): Boolean {
        val end = System.currentTimeMillis() + ms
        do { if ((cfgRd(conn, addr) and mask) == want) return true; sleep(2) } while (System.currentTimeMillis() < end); return false
    }

    private fun readEeprom(conn: UsbDeviceConnection): ByteArray {
        val ee = ByteArray(EE_SIZE)
        var i = 0
        while (i + 4 <= EE_SIZE) {
            val b = ByteArray(4)
            if (conn.controlTransfer(IN_VENDOR, READ_EEPROM, (i ushr 16) and 0xffff, i and 0xffff, b, 4, 1000) == 4)
                System.arraycopy(b, 0, ee, i, 4)
            i += 4
        }
        return ee
    }

    private fun waitForMac(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 1000
        var v = 0
        do { v = regRd(conn, MAC_CSR0); if (v != 0 && v != 0xffffffff.toInt()) return true; sleep(5) } while (System.currentTimeMillis() < end)
        BeastDiag.log("MT7612U: MAC_CSR0=0x%08x — not ready".format(v))
        return false
    }
    private fun waitWpdma(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 1000
        do { if (regRd(conn, WPDMA_GLO_CFG) and (WPDMA_TX_DMA_BUSY or WPDMA_RX_DMA_BUSY) == 0) return true; sleep(2) } while (System.currentTimeMillis() < end)
        return false
    }

    private fun computeMcuGain(ee: ByteArray): Int {
        val v044 = le16(ee, EE_LNA_GAIN)
        val lna2g = v044 and 0xff
        val lna5g0 = (v044 ushr 8) and 0xff
        var lna5g1 = (le16(ee, EE_RSSI_OFFSET_2G_1) ushr 8) and 0xff
        if (lna5g1 == 0 || lna5g1 == 0xff) lna5g1 = lna5g0
        return lna2g or (lna5g0 shl 8) or (lna5g1 shl 16) or (lna5g0 shl 24)
    }

    private fun putLe32(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i+1] = (v ushr 8).toByte(); b[i+2] = (v ushr 16).toByte(); b[i+3] = (v ushr 24).toByte() }
    private fun le32(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) or ((b[i+2].toInt() and 0xff) shl 16) or ((b[i+3].toInt() and 0xff) shl 24)
    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}

    private val MAC_INITVALS = arrayOf(
        intArrayOf(0x0400, 0x00080c00), intArrayOf(0x0404, 0x1efebcff), intArrayOf(0x0800, 0x00000001),
        intArrayOf(0x1004, 0x00000000), intArrayOf(0x1018, 0x003e3f00), intArrayOf(0x1030, 0xaaa99887.toInt()),
        intArrayOf(0x1034, 0x000000aa), intArrayOf(0x1100, 0x33a40d0a), intArrayOf(0x1104, 0x00000209),
        intArrayOf(0x1118, 0x00422010), intArrayOf(0x1204, 0x00000000), intArrayOf(0x1238, 0x001700c8),
        intArrayOf(0x1330, 0x00101001), intArrayOf(0x1334, 0x00010000), intArrayOf(0x1338, 0x00000000),
        intArrayOf(0x1340, 0x0400583f), intArrayOf(0x1344, 0x00ffff20), intArrayOf(0x1348, 0x000a2290),
        intArrayOf(0x134c, 0x47f01f0f), intArrayOf(0x1380, 0x002c00dc), intArrayOf(0x13e0, 0xe3f42004.toInt()),
        intArrayOf(0x13e4, 0xe3f42084.toInt()), intArrayOf(0x13e8, 0xe3f42104.toInt()), intArrayOf(0x13ec, 0x00060fff),
        intArrayOf(0x1400, 0x00015f97), intArrayOf(0x1408, 0x0000017f), intArrayOf(0x140c, 0x00004003),
        intArrayOf(0x150c, 0x00000003), intArrayOf(0x1608, 0x00000002), intArrayOf(0x0a44, 0x00000000),
        intArrayOf(0x0260, 0x00000000), intArrayOf(0x0250, 0x00000000), intArrayOf(0x120c, 0x00000000),
        intArrayOf(0x1264, 0x00000000), intArrayOf(0x13c0, 0x00000000), intArrayOf(0x13c8, 0x00000000),
        intArrayOf(0x1314, 0x3a3a3a3a), intArrayOf(0x1318, 0x3a3a3a3a), intArrayOf(0x131c, 0x3a3a3a3a),
        intArrayOf(0x1320, 0x3a3a3a3a), intArrayOf(0x1324, 0x3a3a3a3a), intArrayOf(0x13d4, 0x3a3a3a3a),
        intArrayOf(0x13d8, 0x0000003a), intArrayOf(0x13dc, 0x0000003a), intArrayOf(0x0024, 0x0000d000),
        intArrayOf(0x0a38, 0x0000000a), intArrayOf(0x0824, 0x60401c18), intArrayOf(0x0210, 0x94ff0000.toInt()),
        intArrayOf(0x1478, 0x00000004), intArrayOf(0x1384, 0x00001818), intArrayOf(0x1358, 0xedcba980.toInt()),
        intArrayOf(0x1648, 0x00830083), intArrayOf(0x1410, 0x000001ff), intArrayOf(0x1350, 0x00001020)
    )
}
