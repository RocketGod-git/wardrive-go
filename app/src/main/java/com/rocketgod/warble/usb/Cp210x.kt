package com.rocketgod.warble.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

class Cp210x(private val conn: UsbDeviceConnection, private val device: UsbDevice) : SerialLink {
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var ifaceId = 0

    private companion object {
        const val REQTYPE_OUT = 0x41
        const val IFC_ENABLE = 0x00
        const val SET_BAUDRATE = 0x1E
        const val SET_LINE_CTL = 0x03
        const val SET_MHS = 0x07
    }

    fun open(baud: Int = 115200): Boolean {
        val intf = device.getInterface(0)
        ifaceId = intf.id
        if (!conn.claimInterface(intf, true)) return false
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }
        if (epIn == null || epOut == null) return false
        ctrl(IFC_ENABLE, 0x0001)
        setBaud(baud)
        ctrl(SET_LINE_CTL, 0x0800)
        setDtrRts(dtr = false, rts = false)
        return true
    }

    private fun ctrl(request: Int, value: Int): Int =
        conn.controlTransfer(REQTYPE_OUT, request, value, ifaceId, null, 0, 1000)

    fun setBaud(baud: Int) {
        val d = byteArrayOf(
            (baud and 0xFF).toByte(), ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(), ((baud shr 24) and 0xFF).toByte()
        )
        conn.controlTransfer(REQTYPE_OUT, SET_BAUDRATE, 0, ifaceId, d, d.size, 1000)
    }

    fun setDtrRts(dtr: Boolean, rts: Boolean) {
        var v = 0x0300
        if (dtr) v = v or 0x01
        if (rts) v = v or 0x02
        ctrl(SET_MHS, v)
    }

    fun resetEsp32() {
        setDtrRts(dtr = false, rts = true)
        Thread.sleep(150)
        setDtrRts(dtr = false, rts = false)
    }

    override fun write(s: String) {
        val b = s.toByteArray()
        conn.bulkTransfer(epOut!!, b, b.size, 1000)
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int = conn.bulkTransfer(epIn!!, buf, buf.size, timeoutMs)

    override fun drain() {
        val b = ByteArray(2048)
        repeat(4) { if (conn.bulkTransfer(epIn!!, b, b.size, 120) <= 0) return }
    }

    override fun close() {
        runCatching { conn.releaseInterface(device.getInterface(0)) }
        runCatching { conn.close() }
    }
}
