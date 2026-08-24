package com.rocketgod.warble.core

object GpsLnav {

    data class NavFields(
        val svid: Int,
        val health: Int? = null,
        val uraIndex: Int? = null,
        val svConfig: Int? = null,
        val antiSpoof: Boolean? = null,
        val weekNumber: Int? = null
    )

    @Volatile var leapSeconds: Int? = null
        private set

    private val perSv = HashMap<Int, NavFields>()

    fun fieldsFor(svid: Int): NavFields? = synchronized(perSv) { perSv[svid] }
    fun snapshot(): Map<Int, NavFields> = synchronized(perSv) { HashMap(perSv) }

    private fun word(data: ByteArray, i: Int): Int {
        val o = i * 4
        return ((data[o].toInt() and 0xFF) shl 24) or ((data[o + 1].toInt() and 0xFF) shl 16) or
            ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF)
    }

    private fun d30(w: Int): Int = w and 0x1

    private fun data24(w: Int, prevD30: Int): Int {
        val d = (w ushr 6) and 0xFFFFFF
        return if (prevD30 == 1) d.inv() and 0xFFFFFF else d
    }

    private fun bits(data: Int, a: Int, b: Int): Int {
        val len = b - a + 1
        return (data ushr (24 - b)) and ((1 shl len) - 1)
    }

    fun ingestL1ca(svid: Int, data: ByteArray): Boolean {
        if (data.size < 40) return false
        val w1 = word(data, 0)

        val pre = bits((w1 ushr 6) and 0xFFFFFF, 1, 8)
        if (pre != 0x8B) return false

        val w2 = word(data, 1)
        val d2 = data24(w2, d30(w1))
        val subframeId = bits(d2, 20, 22)
        if (subframeId !in 1..5) return false
        val antiSpoof = bits(d2, 19, 19) == 1

        var cur = perSv[svid] ?: NavFields(svid)
        cur = cur.copy(antiSpoof = antiSpoof)

        when (subframeId) {
            1 -> {
                val w3 = word(data, 2)
                val d3 = data24(w3, d30(w2))
                val week = bits(d3, 1, 10)
                val ura = bits(d3, 13, 16)
                val health = bits(d3, 17, 22)
                if (ura in 0..15) cur = cur.copy(uraIndex = ura)
                if (health in 0..63) cur = cur.copy(health = health)
                if (week in 0..1023) cur = cur.copy(weekNumber = week)
            }
            4 -> {

                val w3 = word(data, 2)
                val d3 = data24(w3, d30(w2))
                val dataId = bits(d3, 1, 2)
                val svId = bits(d3, 3, 8)
                if (svId == 56) {
                    val w9 = word(data, 8)
                    val d9 = data24(w9, d30(word(data, 7)))
                    val dtls = signed(bits(d9, 17, 24), 8)
                    if (dtls in 0..60) leapSeconds = dtls
                }
                if (svId == 63) {

                    val cfg = svConfigFromPage25(data, w2)
                    cfg[svid]?.let { if (it in 0..15) cur = cur.copy(svConfig = it) }
                }
            }
        }
        synchronized(perSv) { perSv[svid] = cur }
        return true
    }

    private fun svConfigFromPage25(data: ByteArray, w2: Int): Map<Int, Int> {
        val out = HashMap<Int, Int>()

        val stream = ArrayList<Int>()
        var prev = d30(w2)
        for (wi in 2..6) {
            val w = word(data, wi)
            val d = data24(w, prev)
            val from = if (wi == 2) 9 else 1
            for (b in from..24) stream.add((d ushr (24 - b)) and 0x1)
            prev = d30(w)
        }

        var idx = 0
        var prn = 1
        while (idx + 4 <= stream.size && prn <= 32) {
            var v = 0
            for (k in 0 until 4) v = (v shl 1) or stream[idx + k]
            out[prn] = v
            idx += 4; prn++
        }
        return out
    }

    private fun signed(v: Int, bits: Int): Int {
        val sign = 1 shl (bits - 1)
        return if (v and sign != 0) v - (1 shl bits) else v
    }

    fun clear() = synchronized(perSv) { perSv.clear(); leapSeconds = null }
}
