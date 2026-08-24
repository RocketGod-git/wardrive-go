package com.rocketgod.warble.net

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WigleSearch {

    private const val BASE = "https://api.wigle.net/api/v2/network/search"
    private const val PER_PAGE = 100

    data class Net(val bssid: String, val ssid: String, val lat: Double?, val lng: Double?, val channel: Int?, val encryption: String?)
    data class Page(val ok: Boolean, val message: String, val nets: List<Net>, val searchAfter: String?, val totalResults: Long)

    fun myNetworks(apiName: String, apiToken: String, searchAfter: String?, bbox: DoubleArray? = null): Page {
        if (apiName.isBlank() || apiToken.isBlank()) return Page(false, "Missing WiGLE credentials", emptyList(), null, 0)
        var conn: HttpURLConnection? = null
        return try {
            val sb = StringBuilder("$BASE?onlymine=true&resultsPerPage=$PER_PAGE")
            if (bbox != null)
                sb.append("&latrange1=${bbox[0]}&latrange2=${bbox[1]}&longrange1=${bbox[2]}&longrange2=${bbox[3]}")
            if (!searchAfter.isNullOrBlank()) sb.append("&searchAfter=").append(java.net.URLEncoder.encode(searchAfter, "UTF-8"))
            conn = (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                val basic = Base64.encodeToString("$apiName:$apiToken".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $basic")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || code == 403)
                return Page(false, "WiGLE rejected the credentials (HTTP $code).", emptyList(), null, 0)
            if (code !in 200..299) {
                com.rocketgod.warble.usb.BeastDiag.log("WiGLE search HTTP $code: ${body.take(200)}")
                return Page(false, "WiGLE search failed (HTTP $code).", emptyList(), null, 0)
            }
            val j = JSONObject(body)

            if (searchAfter.isNullOrBlank())
                com.rocketgod.warble.usb.BeastDiag.log("WiGLE search HTTP $code: ${body.take(220)}")
            if (!j.optBoolean("success", false)) {
                val msg = j.optString("message").ifBlank { j.optString("error").ifBlank { "WiGLE search returned success=false." } }
                return Page(false, msg, emptyList(), null, 0)
            }
            val arr = j.optJSONArray("results")
            val out = ArrayList<Net>(arr?.length() ?: 0)
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.getJSONObject(i)
                val bssid = o.optString("netid")
                if (bssid.isBlank()) continue
                out.add(
                    Net(
                        bssid = bssid,
                        ssid = o.optString("ssid"),
                        lat = if (o.has("trilat")) o.optDouble("trilat") else null,
                        lng = if (o.has("trilong")) o.optDouble("trilong") else null,
                        channel = if (o.has("channel")) o.optInt("channel") else null,
                        encryption = o.optString("encryption").ifBlank { null }
                    )
                )
            }

            val next = j.optString("searchAfter", j.optString("search_after", "")).ifBlank { null }
            Page(true, "ok", out, next, j.optLong("totalResults"))
        } catch (e: Exception) {
            Page(false, e.message ?: "error", emptyList(), null, 0)
        } finally {
            conn?.disconnect()
        }
    }
}
