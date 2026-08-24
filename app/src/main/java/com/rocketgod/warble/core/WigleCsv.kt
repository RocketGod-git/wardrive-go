package com.rocketgod.warble.core

import android.os.Build
import com.rocketgod.warble.data.ObservationEntity
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WigleCsv {

    private const val HEADER_16 =
        "MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude," +
            "CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type"

    private fun ts(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun esc(s: String?): String {
        val v = s ?: ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n'))
            "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun header(appRelease: String): String =
        "WigleWifi-1.6,appRelease=$appRelease,model=${Build.MODEL},release=${Build.VERSION.RELEASE}," +
            "device=${Build.DEVICE},display=${Build.DISPLAY},board=${Build.BOARD},brand=${Build.BRAND}\n" +
            HEADER_16 + "\n"

    fun appendRow(out: Appendable, o: ObservationEntity) {
        val type = when (o.type) { "WIFI" -> "WIFI"; "CELL" -> o.capabilities ?: "LTE"; else -> "BLE" }
        val auth = o.capabilities ?: ""
        val mfgr = o.companyId?.let { it.toString() } ?: ""
        out.append(esc(o.key)).append(',')
            .append(esc(o.name)).append(',')
            .append(esc(auth)).append(',')
            .append(ts(o.firstSeen)).append(',')
            .append((o.channel ?: 0).toString()).append(',')
            .append((o.frequency ?: 0).toString()).append(',')
            .append(o.bestRssi.toString()).append(',')
            .append((o.lat ?: "").toString()).append(',')
            .append((o.lng ?: "").toString()).append(',')
            .append((o.altitude ?: "").toString()).append(',')
            .append((o.accuracy ?: "").toString()).append(',')
            .append("").append(',')
            .append(mfgr).append(',')
            .append(type).append('\n')
    }

    fun export(rows: List<ObservationEntity>, appRelease: String): String {
        val sb = StringBuilder()
        sb.append(header(appRelease))
        for (o in rows) appendRow(sb, o)
        return sb.toString()
    }

    data class Imported(val key: String, val name: String?, val type: String)

    fun import(input: InputStream): List<Imported> {
        val out = ArrayList<Imported>()
        input.bufferedReader().useLines { seq ->
            var headerSeen = false
            var typeIdx = -1
            var ssidIdx = 1
            for (raw in seq) {
                val line = raw.trimEnd()
                if (line.isBlank()) continue
                if (line.startsWith("WigleWifi")) continue
                if (!headerSeen && line.startsWith("MAC,")) {
                    val cols = line.split(',')
                    typeIdx = cols.indexOf("Type")
                    ssidIdx = cols.indexOf("SSID").let { if (it < 0) 1 else it }
                    headerSeen = true
                    continue
                }
                val cols = splitCsv(line)
                if (cols.isEmpty()) continue
                val key = cols[0].trim()
                if (key.isEmpty()) continue
                val name = cols.getOrNull(ssidIdx)?.ifBlank { null }
                val type = if (typeIdx in cols.indices) cols[typeIdx].trim() else "WIFI"
                out.add(Imported(key, name, type))
            }
        }
        return out
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> if (inQ && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ }
                            else inQ = !inQ
                ch == ',' && !inQ -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
