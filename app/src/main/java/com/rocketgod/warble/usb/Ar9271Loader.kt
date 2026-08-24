package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

object Ar9271Loader {
    private const val REQ_TYPE = 0x40
    private const val FW_DOWNLOAD = 0x30
    private const val FW_DOWNLOAD_COMP = 0x31
    private const val FW_ADDR = 0x501000
    private const val FW_TEXT_ADDR = 0x903000
    private const val BLOCK = 4096

    fun upload(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("Couldn't open the adapter"); return false }
        return try {
            if (device.interfaceCount > 0) runCatching { conn.claimInterface(device.getInterface(0), true) }
            val fw = ctx.assets.open("htc_9271.fw").use { it.readBytes() }
            BeastDiag.log("=== firmware upload ===")
            BeastDiag.log("fw htc_9271.fw size=${fw.size} B  target=0x%06x".format(FW_ADDR))
            onStatus("Uploading firmware · ${fw.size / 1024} KB…")
            var addr = FW_ADDR
            var off = 0
            var blocks = 0
            while (off < fw.size) {
                val len = minOf(BLOCK, fw.size - off)
                val block = fw.copyOfRange(off, off + len)
                val r = conn.controlTransfer(REQ_TYPE, FW_DOWNLOAD, addr ushr 8, 0, block, len, 2000)
                if (r < 0) {
                    BeastDiag.log("FW BLOCK FAILED at off=$off len=$len rc=$r")
                    onStatus("Firmware upload stalled at ${off / 1024} KB"); return false
                }
                blocks++
                off += len
                addr += len
            }
            BeastDiag.log("fw uploaded: $off B in $blocks blocks ✓")

            val comp = conn.controlTransfer(REQ_TYPE, FW_DOWNLOAD_COMP, FW_TEXT_ADDR ushr 8, 0, null, 0, 2000)
            BeastDiag.log("FW_DOWNLOAD_COMP rc=$comp (negative is normal: chip resets immediately)")
            BeastDiag.log("adapter will re-enumerate — handle must be re-acquired")
            onStatus("Firmware loaded · adapter booting")
            true
        } catch (e: Exception) {
            onStatus("Firmware error · ${e.message}")
            false
        } finally {
            runCatching { conn.close() }
        }
    }
}
