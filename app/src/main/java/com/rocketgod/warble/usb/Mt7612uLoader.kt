package com.rocketgod.warble.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

object Mt7612uLoader {

    private const val OUT_VENDOR = 0x40
    private const val IN_VENDOR = 0xC0
    private const val OUT_CLASS = 0x20

    private const val DEV_MODE = 0x01
    private const val MULTI_WRITE = 0x06
    private const val MULTI_READ = 0x07
    private const val WRITE_FCE = 0x42
    private const val WRITE_CFG = 0x46

    private const val ASIC_VERSION = 0x0000
    private const val MCU_CLOCK_CTL = 0x0708
    private const val MCU_COM_REG0 = 0x0730
    private const val FCE_PSE_CTRL = 0x0800
    private const val FCE_DMA_ADDR = 0x0230
    private const val FCE_DMA_LEN = 0x0234
    private const val TX_CPU_FROM_FCE_BASE_PTR = 0x09a0
    private const val TX_CPU_FROM_FCE_MAX_COUNT = 0x09a4
    private const val TX_CPU_FROM_FCE_CPU_DESC_IDX = 0x09a8
    private const val FCE_PDMA_GLOBAL_CONF = 0x09c4
    private const val FCE_SKIP_FS = 0x0a6c
    private const val USB_U3DMA_CFG = 0x9018

    private const val USB_DMA_CFG_VAL = (1 shl 22) or (1 shl 23) or 0x20

    private const val ILM_OFFSET = 0x80000
    private const val DLM_OFFSET = 0x110000
    private const val ROM_PATCH_OFFSET = 0x90000
    private const val FW_URB_MAX_PAYLOAD = 0x3900
    private const val ROM_PATCH_MAX_PAYLOAD = 2048

    private const val FW_HDR_LEN = 32
    private const val PATCH_HDR_LEN = 30
    private const val REV_E3 = 0x22

    private const val DMA_INFO_BASE = (2 shl 27) or (1 shl 30)

    fun upload(ctx: Context, device: UsbDevice, onStatus: (String) -> Unit): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run { onStatus("MT7612U: couldn't open adapter"); return false }
        var epOut: UsbEndpoint? = null
        return try {

            val intf = device.getInterface(0)
            conn.claimInterface(intf, true)
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                    epOut = ep; break
                }
            }
            val out = epOut ?: run { BeastDiag.log("MT7612U: no bulk-out endpoint"); onStatus("MT7612U: no bulk endpoint"); return false }
            BeastDiag.log("=== MT7612U firmware load (mt76x2u) ===")
            BeastDiag.log("MT7612U: iface claimed, bulk-out EP=0x%02x".format(out.address))

            val ver = regRd(conn, ASIC_VERSION)
            val rev = ver and 0xffff
            val e3 = rev >= REV_E3
            BeastDiag.log("MT7612U: ASIC_VERSION=0x%08x rev=0x%02x (E3+=%b)".format(ver, rev, e3))

            val patch = ctx.assets.open("mt7662_rom_patch.bin").use { it.readBytes() }
            val fw = ctx.assets.open("mt7662.bin").use { it.readBytes() }
            if (patch.size <= PATCH_HDR_LEN || fw.size <= FW_HDR_LEN) {
                onStatus("MT7612U: firmware assets missing/short"); return false
            }

            if (!loadRomPatch(conn, out, patch, e3, onStatus)) return false
            if (!loadFirmware(conn, out, fw, e3, onStatus)) return false

            BeastDiag.log("MT7612U: firmware RUNNING ✓ (Phase 1 complete)")
            onStatus("MT7612U: firmware running · monitor init next")
            true
        } catch (e: Exception) {
            BeastDiag.log("MT7612U: load error — ${e.message}")
            onStatus("MT7612U: load error · ${e.message}")
            false
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun loadRomPatch(
        conn: UsbDeviceConnection, out: UsbEndpoint, patch: ByteArray, e3: Boolean, onStatus: (String) -> Unit
    ): Boolean {

        BeastDiag.log("MT7612U: ROM patch build='${asciiz(patch, 0, 16)}' size=${patch.size - PATCH_HDR_LEN} B")
        onStatus("MT7612U: uploading ROM patch…")

        regWrCfg(conn, USB_U3DMA_CFG, USB_DMA_CFG_VAL)
        fwReset(conn); sleep(8)
        configureFce(conn)

        if (!fwSendData(conn, out, patch, PATCH_HDR_LEN, patch.size - PATCH_HDR_LEN, ROM_PATCH_MAX_PAYLOAD, ROM_PATCH_OFFSET)) {
            BeastDiag.log("MT7612U: ROM patch upload FAILED"); onStatus("MT7612U: ROM patch upload failed"); return false
        }
        enablePatch(conn)
        resetWmt(conn)
        sleep(20)

        val patchReg = if (e3) MCU_CLOCK_CTL else MCU_COM_REG0
        val patchMask = if (e3) 0x1 else 0x2
        val applied = poll(conn, patchReg, patchMask, patchMask, 100)

        BeastDiag.log("MT7612U: ROM patch ${if (applied) "applied ✓" else "ack timeout (continuing)"}  reg=0x%04x".format(patchReg))
        return true
    }

    private fun loadFirmware(
        conn: UsbDeviceConnection, out: UsbEndpoint, fw: ByteArray, e3: Boolean, onStatus: (String) -> Unit
    ): Boolean {
        val ilmLen = le32(fw, 0)
        val dlmLen = le32(fw, 4)
        val fwVer = le16(fw, 10)
        if (FW_HDR_LEN + ilmLen + dlmLen != fw.size) {
            BeastDiag.log("MT7612U: fw size mismatch (hdr+ilm+dlm=${FW_HDR_LEN + ilmLen + dlmLen}, file=${fw.size})")
            onStatus("MT7612U: bad firmware file"); return false
        }
        BeastDiag.log("MT7612U: firmware v%d.%d.%02d build='%s' ILM=%d B DLM=%d B".format(
            (fwVer shr 12) and 0xf, (fwVer shr 8) and 0xf, fwVer and 0xf, asciiz(fw, 16, 16), ilmLen, dlmLen))

        fwReset(conn); sleep(8)
        regWrCfg(conn, USB_U3DMA_CFG, USB_DMA_CFG_VAL)
        configureFce(conn)

        onStatus("MT7612U: uploading firmware ILM…")
        if (!fwSendData(conn, out, fw, FW_HDR_LEN, ilmLen, FW_URB_MAX_PAYLOAD, ILM_OFFSET)) {
            BeastDiag.log("MT7612U: ILM upload FAILED"); onStatus("MT7612U: ILM upload failed"); return false
        }
        val dlmOffset = if (e3) DLM_OFFSET + 0x800 else DLM_OFFSET
        onStatus("MT7612U: uploading firmware DLM…")
        if (!fwSendData(conn, out, fw, FW_HDR_LEN + ilmLen, dlmLen, FW_URB_MAX_PAYLOAD, dlmOffset)) {
            BeastDiag.log("MT7612U: DLM upload FAILED"); onStatus("MT7612U: DLM upload failed"); return false
        }

        loadIvb(conn)
        if (!poll(conn, MCU_COM_REG0, 0x1, 0x1, 100)) {
            BeastDiag.log("MT7612U: firmware failed to start (COM_REG0 bit0 low) ✗")
            onStatus("MT7612U: firmware failed to start"); return false
        }
        regSet(conn, MCU_COM_REG0, 0x2)
        regWr(conn, FCE_PSE_CTRL, 0x1)
        return true
    }

    private fun fwSendData(
        conn: UsbDeviceConnection, out: UsbEndpoint, src: ByteArray, srcOff: Int, dataLen: Int, maxPayload: Int, dstAddr: Int
    ): Boolean {
        val maxLen = maxPayload - 8
        var pos = 0
        while (pos < dataLen) {
            val len = minOf(dataLen - pos, maxLen)
            val rounded = (len + 3) and 3.inv()
            val buf = ByteArray(4 + rounded + 4)
            putLe32(buf, 0, DMA_INFO_BASE or (len and 0xffff))
            System.arraycopy(src, srcOff + pos, buf, 4, len)
            singleWr(conn, WRITE_FCE, FCE_DMA_ADDR, dstAddr + pos)
            singleWr(conn, WRITE_FCE, FCE_DMA_LEN, rounded shl 16)
            val n = conn.bulkTransfer(out, buf, buf.size, 1000)
            if (n < 0) { BeastDiag.log("MT7612U: bulk chunk failed at pos=$pos rc=$n"); return false }
            regWr(conn, TX_CPU_FROM_FCE_CPU_DESC_IDX, regRd(conn, TX_CPU_FROM_FCE_CPU_DESC_IDX) + 1)
            pos += len
            sleep(5)
        }
        BeastDiag.log("MT7612U: uploaded $dataLen B -> 0x%06x".format(dstAddr))
        return true
    }

    private fun configureFce(conn: UsbDeviceConnection) {
        regWr(conn, FCE_PSE_CTRL, 0x1)
        regWr(conn, TX_CPU_FROM_FCE_BASE_PTR, 0x400230)
        regWr(conn, TX_CPU_FROM_FCE_MAX_COUNT, 0x1)
        regWr(conn, FCE_PDMA_GLOBAL_CONF, 0x44)
        regWr(conn, FCE_SKIP_FS, 0x3)
    }

    private fun fwReset(conn: UsbDeviceConnection) =
        vendor(conn, DEV_MODE, OUT_VENDOR, 0x1, 0, null)
    private fun loadIvb(conn: UsbDeviceConnection) =
        vendor(conn, DEV_MODE, OUT_VENDOR, 0x12, 0, null)
    private fun enablePatch(conn: UsbDeviceConnection) =
        vendor(conn, DEV_MODE, OUT_CLASS, 0x12, 0,
            byteArrayOf(0x6f, 0xfc.toByte(), 0x08, 0x01, 0x20, 0x04, 0x00, 0x00, 0x00, 0x09, 0x00))
    private fun resetWmt(conn: UsbDeviceConnection) =
        vendor(conn, DEV_MODE, OUT_CLASS, 0x12, 0,
            byteArrayOf(0x6f, 0xfc.toByte(), 0x05, 0x01, 0x07, 0x01, 0x00, 0x04))

    private fun vendor(conn: UsbDeviceConnection, req: Int, reqType: Int, value: Int, index: Int, data: ByteArray?): Int =
        conn.controlTransfer(reqType, req, value, index, data, data?.size ?: 0, 1000)

    private fun regWr(conn: UsbDeviceConnection, addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        conn.controlTransfer(OUT_VENDOR, MULTI_WRITE, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000)
    }
    private fun regWrCfg(conn: UsbDeviceConnection, addr: Int, v: Int) {
        val b = ByteArray(4); putLe32(b, 0, v)
        conn.controlTransfer(OUT_VENDOR, WRITE_CFG, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000)
    }
    private fun regRd(conn: UsbDeviceConnection, addr: Int): Int {
        val b = ByteArray(4)
        val n = conn.controlTransfer(IN_VENDOR, MULTI_READ, (addr ushr 16) and 0xffff, addr and 0xffff, b, 4, 1000)
        return if (n == 4) le32(b, 0) else 0
    }
    private fun regSet(conn: UsbDeviceConnection, addr: Int, bits: Int) = regWr(conn, addr, regRd(conn, addr) or bits)

    private fun singleWr(conn: UsbDeviceConnection, req: Int, offset: Int, v: Int) {
        conn.controlTransfer(OUT_VENDOR, req, v and 0xffff, offset, null, 0, 1000)
        conn.controlTransfer(OUT_VENDOR, req, (v ushr 16) and 0xffff, offset + 2, null, 0, 1000)
    }

    private fun poll(conn: UsbDeviceConnection, addr: Int, mask: Int, want: Int, ms: Int): Boolean {
        val end = System.currentTimeMillis() + ms
        do { if ((regRd(conn, addr) and mask) == want) return true; sleep(10) } while (System.currentTimeMillis() < end)
        return false
    }

    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun asciiz(b: ByteArray, off: Int, max: Int): String =
        String(b, off, minOf(max, b.size - off), Charsets.US_ASCII).trim { it <= ' ' || it == ' ' }
    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    private fun sleep(ms: Int) = sleep(ms.toLong())
}
