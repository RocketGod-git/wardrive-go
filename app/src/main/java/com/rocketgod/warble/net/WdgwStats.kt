package com.rocketgod.warble.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WdgwStats {

    private const val BASE = "https://wdgwars.pl/api"

    data class Hunter(
        val rank: Int, val name: String, val score: Long, val patron: Boolean,
        val wifi: Long = 0, val ble: Long = 0, val aircraft: Long = 0, val mesh: Long = 0
    )
    data class Gang(val rank: Int, val name: String, val members: Long, val apCount: Long)

    data class Board(
        val ok: Boolean, val message: String, val asOf: String,
        val today: List<Hunter>, val week: List<Hunter>, val allTime: List<Hunter>,
        val gangs: List<Gang>, val cells: List<Hunter>
    )

    data class Me(
        val ok: Boolean, val name: String, val total: Long,
        val wifi: Long, val ble: Long, val aircraft: Long, val mesh: Long,
        val rankToday: Int?, val rankWeek: Int?, val rankAllTime: Int?, val rankTopN: Int?,
        val gang: String?, val gangRole: String?, val recentToday: Long, val recent7d: Long,
        val reinforceTotal: Long, val cracked: Long, val credits: Long
    )

    private fun open(path: String, apiKey: String): HttpURLConnection =
        (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("X-API-Key", apiKey)
            setRequestProperty("Accept", "application/json")
        }

    fun leaderboard(apiKey: String): Board {
        if (apiKey.isBlank()) return Board(false, "Missing WDGWars API key", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/leaderboard", apiKey)
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            com.rocketgod.warble.usb.BeastDiag.log("WDGW leaderboard HTTP $code (${body.length}B)")
            if (code == 401 || code == 403) return Board(false, "WDGWars rejected the API key (HTTP $code).", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            if (code !in 200..299) return Board(false, "WDGWars leaderboard failed (HTTP $code).", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            val j = JSONObject(body)
            Board(
                ok = true, message = "ok", asOf = j.optString("_as_of"),
                today = hunters(j.optJSONArray("today"), "total"),
                week = hunters(j.optJSONArray("week"), "total"),
                allTime = hunters(j.optJSONArray("all_time"), "total"),
                gangs = gangs(j.optJSONArray("gangs")),
                cells = hunters(j.optJSONArray("cells"), "cells")
            )
        } catch (e: Exception) {
            Board(false, e.message ?: "error", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        } finally {
            conn?.disconnect()
        }
    }

    fun me(apiKey: String): Me? {
        if (apiKey.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/me", apiKey)
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) { com.rocketgod.warble.usb.BeastDiag.log("WDGW /me HTTP $code"); return null }
            val j = JSONObject(body)
            if (!j.optBoolean("ok", false) && !j.has("username")) return null

            val rankObj = j.optJSONObject("your_rank") ?: j.optJSONObject("rank")
            fun rk(vararg keys: String): Int? {
                keys.forEach { k -> rankObj?.optIntOrNull(k)?.let { return it } }
                keys.forEach { k -> j.optIntOrNull(k)?.let { return it } }
                return null
            }
            com.rocketgod.warble.usb.BeastDiag.log(
                "WDGW /me HTTP $code: rank(all=${rankObj?.opt("all_time")} today=${rankObj?.opt("today")} " +
                    "week=${rankObj?.opt("week")} top=${rankObj?.opt("top_n")}) total=${j.optLong("total")} gang=${j.opt("gang")}"
            )
            Me(
                ok = true, name = j.optString("username"), total = j.optLong("total"),
                wifi = j.optLong("wifi"), ble = j.optLong("ble"), aircraft = j.optLong("aircraft"), mesh = j.optLong("mesh"),
                rankToday = rk("today", "day", "daily", "rank_today", "today_rank"),
                rankWeek = rk("week", "weekly", "7d", "rank_week", "week_rank"),
                rankAllTime = rk("all_time", "alltime", "allTime", "all", "overall", "global", "rank_all_time", "rank", "all_time_rank"),
                rankTopN = rankObj?.optIntOrNull("top_n") ?: rankObj?.optIntOrNull("topN"),
                gang = j.optString("gang").ifBlank { null },
                gangRole = j.optString("gang_role").ifBlank { null },
                recentToday = j.optLong("recent_today"), recent7d = j.optLong("recent_7d"),
                reinforceTotal = j.optLong("reinforce_total"),
                cracked = j.optLong("cracked"),
                credits = j.optJSONObject("credits")?.optLong("balance") ?: 0L
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    private fun hunters(arr: JSONArray?, scoreKey: String): List<Hunter> {
        val out = ArrayList<Hunter>(arr?.length() ?: 0)
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr!!.optJSONObject(i) ?: continue
            out.add(
                Hunter(
                    rank = i + 1, name = o.optString("username").ifBlank { "player${i + 1}" },
                    score = o.optLong(scoreKey), patron = o.optBoolean("is_patron", false),
                    wifi = o.optLong("wifi"), ble = o.optLong("ble"),
                    aircraft = o.optLong("aircraft"), mesh = o.optLong("mesh")
                )
            )
        }
        return out
    }

    private fun gangs(arr: JSONArray?): List<Gang> {
        val out = ArrayList<Gang>(arr?.length() ?: 0)
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr!!.optJSONObject(i) ?: continue
            out.add(Gang(rank = i + 1, name = o.optString("name").ifBlank { "gang${i + 1}" },
                members = o.optLong("member_count"), apCount = o.optLong("ap_count")))
        }
        return out
    }
}
