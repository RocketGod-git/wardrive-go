package com.rocketgod.warble.classify

import android.content.Context

class OuiLookup private constructor(private val map: Map<String, String>) {

    fun vendor(key: String?): String? {
        if (key == null || key.length < 6) return null
        val oui = key.replace(":", "").take(6).uppercase()
        if (oui.length != 6) return null
        return map[oui]
    }

    companion object {
        @Volatile private var INSTANCE: OuiLookup? = null
        fun get(context: Context): OuiLookup =
            INSTANCE ?: synchronized(this) { INSTANCE ?: load(context).also { INSTANCE = it } }

        private fun load(context: Context): OuiLookup {
            val m = HashMap<String, String>(95_000)
            runCatching {
                context.assets.open("oui_manuf.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line[0] == '#') continue
                        val t = line.indexOf('\t')
                        if (t <= 0) continue
                        val oui = line.substring(0, t).trim().uppercase()
                        if (oui.length == 6) m[oui] = line.substring(t + 1).trim()
                    }
                }
            }
            return OuiLookup(m)
        }
    }
}
