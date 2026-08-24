package com.rocketgod.warble.usb

import java.util.concurrent.ConcurrentLinkedQueue

object PmkidCapture {

    data class Hit(
        val pmkid: String, val bssid: String, val sta: String, val ssid: String?,
        val channel: Int, val rssi: Int, val time: Long,
        val kind: Int = 1, val hashline: String? = null
    )

    private val seen = HashSet<String>()
    private val pending = ConcurrentLinkedQueue<Hit>()
    @Volatile var total = 0; private set
    @Volatile var handshakes = 0; private set

    @Volatile var dataSeen = 0L; private set
    @Volatile var eapolSeen = 0; private set
    private val msg = IntArray(5)
    private var logged = 0

    private class M1(val anonce: String, val replay: String, val time: Long)
    private val m1cache = HashMap<String, M1>()

    fun diag(): String =
        "PMKID: data=$dataSeen eapol=$eapolSeen (M1=${msg[1]} M2=${msg[2]} M3=${msg[3]} M4=${msg[4]}) pmkid=$total hs=$handshakes"

    fun scan(buf: ByteArray, d: Int, end: Int, pad: Int, ch: Int, rssi: Int, ssidFor: (String) -> String?) {
        try {
            if (d + 2 > end) return
            val fc = buf[d].toInt() and 0xff
            if (((fc shr 2) and 3) != 2) return
            dataSeen++
            val fc1 = buf[d + 1].toInt() and 0xff
            val fromDs = (fc1 shr 1) and 1

            var p = -1
            val hi = minOf(end - 8, d + 40)
            var q = d + 24
            while (q <= hi) {
                if ((buf[q].toInt() and 0xff) == 0xAA && (buf[q + 1].toInt() and 0xff) == 0xAA &&
                    (buf[q + 2].toInt() and 0xff) == 0x03 &&
                    (buf[q + 6].toInt() and 0xff) == 0x88 && (buf[q + 7].toInt() and 0xff) == 0x8E
                ) { p = q; break }
                q++
            }
            if (p < 0) return
            val eapol = p + 8
            if (eapol + 7 > end) return
            if ((buf[eapol + 1].toInt() and 0xff) != 3) return

            val keyInfo = ((buf[eapol + 5].toInt() and 0xff) shl 8) or (buf[eapol + 6].toInt() and 0xff)
            val ack = keyInfo and 0x080 != 0; val hasMic = keyInfo and 0x100 != 0
            val install = keyInfo and 0x040 != 0; val secure = keyInfo and 0x200 != 0
            val m = when {
                ack && !hasMic -> 1
                hasMic && !ack && !secure && !install -> 2
                hasMic && ack && install -> 3
                hasMic && secure && !ack -> 4
                else -> 0
            }
            eapolSeen++; if (m in 1..4) msg[m]++
            fun mac(at: Int) = (0 until 6).joinToString(":") { "%02x".format(buf[at + it].toInt() and 0xff) }
            fun hex(off: Int, len: Int): String { val sb = StringBuilder(len * 2); for (k in 0 until len) sb.append("%02x".format(buf[off + k].toInt() and 0xff)); return sb.toString() }
            val apAt = if (fromDs == 1) d + 10 else d + 4
            val staAt = if (fromDs == 1) d + 4 else d + 10
            val ap = mac(apAt); val sta = mac(staAt)
            val pairKey = "$ap|$sta"
            val now = System.currentTimeMillis()
            if (logged < 20) { logged++; BeastDiag.log("EAPOL M$m — AP $ap STA $sta ch$ch (handshake capture working)") }

            if (m == 1 && eapol + 49 <= end) {

                if (m1cache.size > 256) m1cache.entries.removeAll { now - it.value.time > 30_000 }
                m1cache[pairKey] = M1(hex(eapol + 17, 32), hex(eapol + 9, 8), now)
            }
            if (m == 2 && eapol + 99 <= end) {
                val bodyLen = ((buf[eapol + 2].toInt() and 0xff) shl 8) or (buf[eapol + 3].toInt() and 0xff)
                val frameLen = 4 + bodyLen
                val m1 = m1cache[pairKey]
                if (m1 != null && m1.replay == hex(eapol + 9, 8) && eapol + frameLen <= end) {
                    val micHex = hex(eapol + 81, 16)
                    if (micHex != "00000000000000000000000000000000") {
                        val dup = synchronized(seen) { !seen.add("hs:$micHex") }
                        if (!dup) {

                            val fr = buf.copyOfRange(eapol, eapol + frameLen)
                            for (k in 81 until 97) if (k < fr.size) fr[k] = 0
                            val eapolHex = fr.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                            val ssid = runCatching { ssidFor(ap) }.getOrNull()
                            val essidHex = (ssid ?: "").toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

                            val line = "WPA*02*$micHex*${ap.replace(":", "")}*${sta.replace(":", "")}*$essidHex*${m1.anonce}*$eapolHex*00"
                            pending.add(Hit(micHex, ap, sta, ssid, ch, rssi, now, kind = 2, hashline = line))
                            handshakes++
                            m1cache.remove(pairKey)
                            BeastDiag.log("★ HANDSHAKE captured (M1+M2) — ${ssid ?: "<hidden>"} ($ap) ch$ch")
                        }
                    }
                }
            }

            var i = eapol
            val limit = end - 22
            while (i <= limit) {
                if ((buf[i].toInt() and 0xff) == 0xDD && (buf[i + 1].toInt() and 0xff) == 0x14 &&
                    (buf[i + 2].toInt() and 0xff) == 0x00 && (buf[i + 3].toInt() and 0xff) == 0x0F &&
                    (buf[i + 4].toInt() and 0xff) == 0xAC && (buf[i + 5].toInt() and 0xff) == 0x04
                ) {
                    val sb = StringBuilder(32); var allZero = true
                    for (k in 0 until 16) { val b = buf[i + 6 + k].toInt() and 0xff; if (b != 0) allZero = false; sb.append("%02x".format(b)) }
                    if (allZero) return
                    val pmkid = sb.toString()
                    synchronized(seen) { if (!seen.add(pmkid)) return }
                    val ssid = runCatching { ssidFor(ap) }.getOrNull()
                    pending.add(Hit(pmkid, ap, sta, ssid, ch, rssi, now))
                    total++
                    BeastDiag.log("★ PMKID captured — ${ssid ?: "<hidden>"} ($ap) ch$ch")
                    return
                }
                i++
            }
        } catch (_: Throwable) {
        }
    }

    fun drainNew(): List<Hit> {
        val out = ArrayList<Hit>()
        while (true) { out.add(pending.poll() ?: break) }
        return out
    }

    fun hashcat22000(rows: List<com.rocketgod.warble.data.PmkidEntity>): String =
        rows.joinToString("\n") { p ->
            p.hashline ?: run {
                val essidHex = (p.ssid ?: "").toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
                "WPA*01*${p.pmkid}*${p.bssid.replace(":", "")}*${p.sta.replace(":", "")}*$essidHex***"
            }
        }
}
