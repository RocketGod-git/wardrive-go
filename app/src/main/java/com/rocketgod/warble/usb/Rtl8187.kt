package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

object Rtl8187 {
    private const val REQT_READ = 0xC0
    private const val REQ_GET_REG = 0x05
    private const val ADDR_MAC = 0x0000

    fun probe(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit) {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        BeastDiag.clear()
        BeastDiag.log("device ${String.format("%04x:%04x", device.vendorId, device.productId)} (RTL8187 path)")
        val conn = mgr.openDevice(device) ?: run { onStatus("RTL8187 · can't open (USB permission?)"); return }
        try {
            if (device.interfaceCount > 0) runCatching { conn.claimInterface(device.getInterface(0), true) }
            val mac = ByteArray(6)
            var ok = true
            for (i in 0..5) {
                val one = ByteArray(1)
                val n = conn.controlTransfer(REQT_READ, REQ_GET_REG, ADDR_MAC + i, 0, one, 1, 1000)
                if (n == 1) mac[i] = one[0] else { ok = false; break }
            }
            val macStr = mac.joinToString(":") { String.format("%02x", it) }
            BeastDiag.log("MAC reg read ok=$ok  mac=$macStr")
            val allZero = mac.all { it.toInt() == 0 }
            val allFF = mac.all { (it.toInt() and 0xff) == 0xff }
            onStatus(
                when {
                    !ok -> "RTL8187 · register read failed · tap for dump"
                    allZero || allFF -> "RTL8187 · reg I/O ok, MAC unset ($macStr) · tap for dump"
                    else -> "RTL8187 · MAC $macStr · alive · tap for dump"
                }
            )
        } catch (e: Exception) {
            BeastDiag.log("exception: ${e.message}")
            onStatus("RTL8187 · ${e.message} · tap for dump")
        } finally {
            runCatching { conn.close() }
        }
    }
}
