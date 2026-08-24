package com.rocketgod.warble.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

class CdcAcm(private val conn: UsbDeviceConnection, private val device: UsbDevice) : SerialLink {
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var commIntf: UsbInterface? = null
    private var dataIntf: UsbInterface? = null

    private companion object {
        const val REQTYPE_OUT = 0x21
        const val SET_LINE_CODING = 0x20
        const val SET_CONTROL_LINE_STATE = 0x22
    }

    fun open(baud: Int = 115200, assertDtr: Boolean = true): Boolean {

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> if (commIntf == null) commIntf = intf
                UsbConstants.USB_CLASS_CDC_DATA -> if (dataIntf == null && hasBulkPair(intf)) dataIntf = intf
            }
        }

        if (dataIntf == null) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (hasBulkPair(intf)) { dataIntf = intf; break }
            }
        }
        val data = dataIntf ?: return false
        val comm = commIntf ?: data
        conn.claimInterface(comm, true)
        conn.claimInterface(data, true)
        for (i in 0 until data.endpointCount) {
            val ep = data.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }
        if (epIn == null || epOut == null) return false
        setLineCoding(comm.id, baud)

        setControlLineState(comm.id, dtr = assertDtr, rts = false)
        return true
    }

    private fun hasBulkPair(intf: UsbInterface): Boolean {
        var bin = false; var bout = false
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) bin = true else bout = true
            }
        }
        return bin && bout
    }

    private fun setLineCoding(commIndex: Int, baud: Int) {
        val d = byteArrayOf(
            (baud and 0xFF).toByte(), ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(), ((baud shr 24) and 0xFF).toByte(),
            0x00,
            0x00,
            0x08
        )
        conn.controlTransfer(REQTYPE_OUT, SET_LINE_CODING, 0, commIndex, d, d.size, 1000)
    }

    fun setControlLineState(commIndex: Int, dtr: Boolean, rts: Boolean) {
        var v = 0
        if (dtr) v = v or 0x01
        if (rts) v = v or 0x02
        conn.controlTransfer(REQTYPE_OUT, SET_CONTROL_LINE_STATE, v, commIndex, null, 0, 1000)
    }

    override fun write(s: String) {
        val b = s.toByteArray()
        conn.bulkTransfer(epOut!!, b, b.size, 1000)
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int = conn.bulkTransfer(epIn!!, buf, buf.size, timeoutMs)

    override fun drain() {
        val b = ByteArray(4096)
        repeat(6) { if (conn.bulkTransfer(epIn!!, b, b.size, 120) <= 0) return }
    }

    override fun close() {
        runCatching { commIntf?.let { conn.releaseInterface(it) } }
        runCatching { dataIntf?.let { conn.releaseInterface(it) } }
        runCatching { conn.close() }
    }
}
