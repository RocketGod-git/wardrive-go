package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

object Rt3070Loader {

    private const val OUT_VENDOR = 0x40
    private const val IN_VENDOR = 0xC0

    private const val DEVICE_MODE = 0x01
    private const val MULTI_WRITE = 0x06
    private const val MULTI_READ = 0x07

    private const val USB_MODE_RESET = 1
    private const val USB_MODE_FIRMWARE = 8
    private const val USB_MODE_AUTORUN = 17

    private const val FIRMWARE_IMAGE_BASE = 0x3000
    private const val WPDMA_GLO_CFG = 0x0208
    private const val PBF_SYS_CTRL = 0x0400
    private const val HOST_CMD_CSR = 0x0404
    private const val MAC_CSR0 = 0x1000
    private const val MAC_SYS_CTRL = 0x1004
    private const val AUTOWAKEUP_CFG = 0x1208
    private const val H2M_MAILBOX_CSR = 0x7010
    private const val H2M_MAILBOX_CID = 0x7014
    private const val H2M_MAILBOX_STATUS = 0x701c
    private const val H2M_INT_SRC = 0x7024
    private const val H2M_BBP_AGENT = 0x7028

    private const val PBF_SYS_CTRL_READY = 0x80
    private const val WPDMA_ENABLE_TX_DMA = 1 shl 0
    private const val WPDMA_ENABLE_RX_DMA = 1 shl 2
    private const val H2M_OWNER = 0xff000000.toInt()
    private const val MCU_BOOT_SIGNAL = 0x72

    private const val FW_BLOCK = 4096
    private const val CSR_CACHE_SIZE = 64

    fun upload(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("RT3070: couldn't open adapter"); return false }
        return try {
            if (device.interfaceCount > 0) runCatching { conn.claimInterface(device.getInterface(0), true) }
            val fw = ctx.assets.open("rt2870.bin").use { it.readBytes() }
            if (fw.size < FW_BLOCK) { onStatus("RT3070: rt2870.bin missing/short (${fw.size} B)"); return false }
            BeastDiag.log("=== RT3070 firmware load (rt2800usb) ===")
            BeastDiag.log("RT3070: rt2870.bin size=${fw.size} B, using first $FW_BLOCK B -> base 0x%04x".format(FIRMWARE_IMAGE_BASE))
            onStatus("RT3070 · uploading firmware…")

            regWr(conn, AUTOWAKEUP_CFG, 0x0)
            if (!waitCsrReady(conn)) { BeastDiag.log("RT3070: MAC_CSR0 never became ready"); onStatus("RT3070: MAC not responding"); return false }
            disableWpdma(conn)

            val autorun = detectAutorun(conn)
            BeastDiag.log("RT3070: autorun=${autorun} (${if (autorun) "firmware already resident — skip upload" else "uploading image"})")
            if (!autorun) writeFirmwareImage(conn, fw)
            regWr(conn, H2M_MAILBOX_CID, 0xffffffff.toInt())
            regWr(conn, H2M_MAILBOX_STATUS, 0xffffffff.toInt())

            deviceMode(conn, USB_MODE_FIRMWARE)
            sleep(10)
            regWr(conn, H2M_MAILBOX_CSR, 0x0)

            val ready = pollPbfReady(conn)
            disableWpdma(conn)
            regWr(conn, H2M_BBP_AGENT, 0x0)
            regWr(conn, H2M_MAILBOX_CSR, 0x0)
            regWr(conn, H2M_INT_SRC, 0x0)
            mcuBoot(conn)
            sleep(1)

            if (!ready) {
                BeastDiag.log("RT3070: PBF_SYS_CTRL READY never set — firmware did not start ✗")
                onStatus("RT3070: firmware failed to start"); return false
            }
            BeastDiag.log("RT3070: firmware RUNNING ✓ (Phase 1 complete) — MAC_CSR0=0x%08x".format(regRd(conn, MAC_CSR0)))
            onStatus("RT3070: firmware running · monitor init next")
            true
        } catch (e: Exception) {
            BeastDiag.log("RT3070: load error — ${e.message}")
            onStatus("RT3070: load error · ${e.message}")
            false
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun detectAutorun(conn: UsbDeviceConnection): Boolean {
        val b = ByteArray(4)
        val n = conn.controlTransfer(IN_VENDOR, DEVICE_MODE, USB_MODE_AUTORUN, 0, b, 4, 1000)
        return n == 4 && (le32(b, 0) and 0x3) == 2
    }

    private fun writeFirmwareImage(conn: UsbDeviceConnection, fw: ByteArray) {
        var off = 0
        var chunks = 0
        while (off < FW_BLOCK) {
            val len = minOf(CSR_CACHE_SIZE, FW_BLOCK - off)
            val chunk = fw.copyOfRange(off, off + len)
            val r = conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, 0, FIRMWARE_IMAGE_BASE + off, chunk, len, 1000)
            if (r < 0) { BeastDiag.log("RT3070: fw chunk failed at off=$off rc=$r"); throw RuntimeException("fw upload stalled at $off B") }
            off += len; chunks++
        }
        BeastDiag.log("RT3070: firmware image written — $off B in $chunks chunks ✓")
    }

    private fun mcuBoot(conn: UsbDeviceConnection) {

        val end = System.currentTimeMillis() + 100
        while ((regRd(conn, H2M_MAILBOX_CSR) and H2M_OWNER) != 0 && System.currentTimeMillis() < end) sleep(1)

        regWr(conn, H2M_MAILBOX_CSR, 0x01000000)

        regWr(conn, HOST_CMD_CSR, MCU_BOOT_SIGNAL and 0xff)
    }

    private fun deviceMode(conn: UsbDeviceConnection, mode: Int) {
        conn.controlTransfer(OUT_VENDOR, DEVICE_MODE, mode, 0, null, 0, 1000)
    }

    private fun disableWpdma(conn: UsbDeviceConnection) {
        val v = regRd(conn, WPDMA_GLO_CFG) and (WPDMA_ENABLE_TX_DMA or WPDMA_ENABLE_RX_DMA).inv()
        regWr(conn, WPDMA_GLO_CFG, v)
    }

    private fun waitCsrReady(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 1000
        do {
            val v = regRd(conn, MAC_CSR0)
            if (v != 0 && v != 0xffffffff.toInt()) return true
            sleep(1)
        } while (System.currentTimeMillis() < end)
        return false
    }

    private fun pollPbfReady(conn: UsbDeviceConnection): Boolean {
        val end = System.currentTimeMillis() + 1000
        do {
            if (regRd(conn, PBF_SYS_CTRL) and PBF_SYS_CTRL_READY != 0) return true
            sleep(1)
        } while (System.currentTimeMillis() < end)
        return false
    }

    private fun regWr(conn: UsbDeviceConnection, addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, 0, addr, b, 4, 1000)
    }
    private fun regRd(conn: UsbDeviceConnection, addr: Int): Int {
        val b = ByteArray(4)
        return if (conn.controlTransfer(IN_VENDOR, MULTI_READ, 0, addr, b, 4, 1000) == 4) le32(b, 0) else 0
    }

    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    private fun sleep(ms: Int) = try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
}
