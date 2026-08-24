package com.rocketgod.warble.usb

import java.io.BufferedOutputStream
import java.io.OutputStream

object PcapWriter {
    private const val LINKTYPE_IEEE802_11 = 105

    fun write(out: OutputStream, frames: List<ByteArray>, linkType: Int = LINKTYPE_IEEE802_11) {
        BufferedOutputStream(out).use { b ->

            le32(b, -0x5e655860)
            le16(b, 2); le16(b, 4)
            le32(b, 0); le32(b, 0)
            le32(b, 65535)
            le32(b, linkType)
            var ts = System.currentTimeMillis()
            for (f in frames) {
                le32(b, (ts / 1000).toInt())
                le32(b, ((ts % 1000) * 1000).toInt())
                le32(b, f.size); le32(b, f.size)
                b.write(f)
                ts += 1
            }
            b.flush()
        }
    }

    private fun le32(o: OutputStream, v: Int) {
        o.write(v and 0xff); o.write((v ushr 8) and 0xff); o.write((v ushr 16) and 0xff); o.write((v ushr 24) and 0xff)
    }
    private fun le16(o: OutputStream, v: Int) { o.write(v and 0xff); o.write((v ushr 8) and 0xff) }
}
