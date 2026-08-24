package com.rocketgod.warble.net

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WigleStats {

    private const val BASE = "https://api.wigle.net/api/v2"

    data class WUser(
        val rank: Long, val monthRank: Long, val name: String,
        val wifiGps: Long, val monthCount: Long, val bt: Long, val cell: Long,
        val prevRank: Long, val self: Boolean,

        val wifiTotal: Long = 0, val wifiPercent: Double = 0.0,
        val btTotal: Long = 0, val cellTotal: Long = 0,
        val prevMonthRank: Long = 0, val prevMonthCount: Long = 0,
        val totalWiFiLocations: Long = 0,
        val firstSeen: String = "", val lastSeen: String = ""
    )

    data class WGroup(
        val id: String, val name: String, val owner: String,
        val discovered: Long, val total: Long, val members: Long
    )

    data class Personal(
        val name: String, val rank: Long, val monthRank: Long,
        val wifiGps: Long, val bt: Long, val cell: Long,
        val prevRank: Long, val prevMonthRank: Long,
        val wifiTotal: Long, val totalWiFiLocations: Long,
        val wifiPercent: Double = 0.0, val btTotal: Long = 0, val cellTotal: Long = 0,
        val monthCount: Long = 0, val prevMonthCount: Long = 0,
        val firstSeen: String = "", val lastSeen: String = ""
    ) {

        val rankDelta: Long get() = if (prevRank in 1..Long.MAX_VALUE && rank in 1..Long.MAX_VALUE) prevRank - rank else 0
        val monthRankDelta: Long get() = if (prevMonthRank in 1..Long.MAX_VALUE && monthRank in 1..Long.MAX_VALUE) prevMonthRank - monthRank else 0
    }

    data class UserDetail(
        val name: String, val self: Boolean, val context: String,
        val rank: Long, val prevRank: Long, val monthRank: Long, val prevMonthRank: Long,
        val wifiGps: Long, val wifiTotal: Long, val wifiPercent: Double,
        val bt: Long, val btTotal: Long, val cell: Long, val cellTotal: Long,
        val totalWiFiLocations: Long, val monthCount: Long, val prevMonthCount: Long,
        val firstSeen: String, val lastSeen: String, val teamRank: Int = 0
    ) {

        val rankDelta: Long get() = if (prevRank in 1..Long.MAX_VALUE && rank in 1..Long.MAX_VALUE) prevRank - rank else 0
        val monthRankDelta: Long get() = if (prevMonthRank in 1..Long.MAX_VALUE && monthRank in 1..Long.MAX_VALUE) prevMonthRank - monthRank else 0
        val monthDelta: Long get() = monthCount - prevMonthCount
    }

    fun detailFromUser(u: WUser, month: Boolean = false): UserDetail = UserDetail(
        name = u.name, self = u.self, context = "global board",
        rank = if (month) 0 else u.rank, prevRank = if (month) 0 else u.prevRank,
        monthRank = if (month) u.rank else 0, prevMonthRank = if (month) u.prevMonthRank else 0,
        wifiGps = u.wifiGps, wifiTotal = u.wifiTotal, wifiPercent = u.wifiPercent,
        bt = u.bt, btTotal = u.btTotal, cell = u.cell, cellTotal = u.cellTotal,
        totalWiFiLocations = u.totalWiFiLocations, monthCount = u.monthCount, prevMonthCount = u.prevMonthCount,
        firstSeen = u.firstSeen, lastSeen = u.lastSeen
    )

    fun detailFromPersonal(p: Personal): UserDetail = UserDetail(
        name = p.name, self = true, context = "global board",
        rank = p.rank, prevRank = p.prevRank, monthRank = p.monthRank, prevMonthRank = p.prevMonthRank,
        wifiGps = p.wifiGps, wifiTotal = p.wifiTotal, wifiPercent = p.wifiPercent,
        bt = p.bt, btTotal = p.btTotal, cell = p.cell, cellTotal = p.cellTotal,
        totalWiFiLocations = p.totalWiFiLocations, monthCount = p.monthCount, prevMonthCount = p.prevMonthCount,
        firstSeen = p.firstSeen, lastSeen = p.lastSeen
    )

    fun detailFromMember(m: GroupMember, teamName: String): UserDetail = UserDetail(
        name = m.name, self = m.self, context = teamName,
        rank = 0, prevRank = 0, monthRank = 0, prevMonthRank = 0,
        wifiGps = m.discovered, wifiTotal = m.total, wifiPercent = 0.0,
        bt = 0, btTotal = 0, cell = 0, cellTotal = 0,
        totalWiFiLocations = 0, monthCount = m.monthCount, prevMonthCount = m.prevMonthCount,
        firstSeen = m.firstSeen, lastSeen = m.lastSeen, teamRank = m.rank
    )

    data class Standings(val ok: Boolean, val message: String, val totalUsers: Long, val myName: String, val users: List<WUser>)
    data class Groups(val ok: Boolean, val message: String, val groups: List<WGroup>)

    data class GroupMember(
        val rank: Int, val name: String, val discovered: Long, val total: Long,
        val monthCount: Long, val prevMonthCount: Long, val genDisc: Long,
        val firstSeen: String, val lastSeen: String, val self: Boolean
    )
    data class GroupMembers(val ok: Boolean, val message: String, val groupName: String, val members: List<GroupMember>)

    const val SORT_ALLTIME = "discovered"
    const val SORT_MONTH = "monthcount"

    private fun open(path: String, apiName: String?, apiToken: String?): HttpURLConnection {
        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        if (!apiName.isNullOrBlank() && !apiToken.isNullOrBlank()) {
            val basic = Base64.encodeToString("$apiName:$apiToken".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $basic")
        }
        return conn
    }

    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        return code to body
    }

    fun standings(sort: String, pageStart: Int, pageEnd: Int, apiName: String? = null, apiToken: String? = null): Standings {
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/stats/standings?sort=$sort&pagestart=$pageStart&pageend=$pageEnd", apiName, apiToken)
            val (code, body) = read(conn)
            if (code !in 200..299) return Standings(false, "WiGLE standings HTTP $code", 0, "", emptyList())
            val j = JSONObject(body)
            val arr = j.optJSONArray("results")
            val out = ArrayList<WUser>(arr?.length() ?: 0)
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.getJSONObject(i)
                out.add(
                    WUser(
                        rank = o.optLong("rank"), monthRank = o.optLong("monthRank"),
                        name = o.optString("userName"),
                        wifiGps = o.optLong("discoveredWiFiGPS"), monthCount = o.optLong("eventMonthCount"),
                        bt = o.optLong("discoveredBtGPS"), cell = o.optLong("discoveredCellGPS"),
                        prevRank = o.optLong("prevRank"), self = o.optBoolean("self", false),
                        wifiTotal = o.optLong("discoveredWiFi"), wifiPercent = o.optDouble("discoveredWiFiGPSPercent", 0.0),
                        btTotal = o.optLong("discoveredBt"), cellTotal = o.optLong("discoveredCell"),
                        prevMonthRank = o.optLong("prevMonthRank"), prevMonthCount = o.optLong("eventPrevMonthCount"),
                        totalWiFiLocations = o.optLong("totalWiFiLocations"),
                        firstSeen = transDate(o.optString("first")), lastSeen = transDate(o.optString("last"))
                    )
                )
            }

            val real = out.filterNot { it.name.equals("anonymous", ignoreCase = true) }
            val removed = out.size - real.size
            val renumbered = real.mapIndexed { i, u -> u.copy(rank = (pageStart + i + 1).toLong()) }
            Standings(true, "ok", (j.optLong("totalUsers") - removed).coerceAtLeast(0), j.optString("myUsername"), renumbered)
        } catch (e: Exception) {
            Standings(false, e.message ?: "error", 0, "", emptyList())
        } finally {
            conn?.disconnect()
        }
    }

    fun raceWindow(centerRank: Long, myName: String, span: Int = 5, apiName: String? = null, apiToken: String? = null): List<WUser> {
        if (centerRank <= 0) return emptyList()
        val rawCenter = centerRank + 1
        val pageStart = (rawCenter - 1 - span).coerceAtLeast(0L).toInt()
        val pageEnd = (rawCenter - 1 + span).toInt()
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/stats/standings?sort=$SORT_ALLTIME&pagestart=$pageStart&pageend=$pageEnd", apiName, apiToken)
            val (code, body) = read(conn)
            if (code !in 200..299) return emptyList()
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = ArrayList<WUser>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("userName")
                if (name.equals("anonymous", ignoreCase = true)) continue
                val raw = o.optLong("rank")
                out.add(
                    WUser(
                        rank = if (raw > 1) raw - 1 else raw, monthRank = o.optLong("monthRank"), name = name,
                        wifiGps = o.optLong("discoveredWiFiGPS"), monthCount = o.optLong("eventMonthCount"),
                        bt = o.optLong("discoveredBtGPS"), cell = o.optLong("discoveredCellGPS"),
                        prevRank = o.optLong("prevRank"),
                        self = o.optBoolean("self", false) || name.equals(myName, ignoreCase = true),
                        wifiTotal = o.optLong("discoveredWiFi"), wifiPercent = o.optDouble("discoveredWiFiGPSPercent", 0.0),
                        btTotal = o.optLong("discoveredBt"), cellTotal = o.optLong("discoveredCell"),
                        prevMonthRank = o.optLong("prevMonthRank"), prevMonthCount = o.optLong("eventPrevMonthCount"),
                        totalWiFiLocations = o.optLong("totalWiFiLocations"),
                        firstSeen = transDate(o.optString("first")), lastSeen = transDate(o.optString("last"))
                    )
                )
            }
            out.sortedBy { it.rank }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    fun groups(apiName: String? = null, apiToken: String? = null): Groups {
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/stats/group", apiName, apiToken)
            val (code, body) = read(conn)
            if (code !in 200..299) return Groups(false, "WiGLE group HTTP $code", emptyList())
            val j = JSONObject(body)
            val arr = j.optJSONArray("groups")
            val out = ArrayList<WGroup>(arr?.length() ?: 0)
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.getJSONObject(i)
                out.add(
                    WGroup(
                        id = o.optString("groupId"), name = o.optString("groupName"), owner = o.optString("owner"),
                        discovered = o.optLong("discovered"), total = o.optLong("total"), members = o.optLong("members")
                    )
                )
            }
            Groups(true, "ok", out)
        } catch (e: Exception) {
            Groups(false, e.message ?: "error", emptyList())
        } finally {
            conn?.disconnect()
        }
    }

    fun groupMembers(groupId: String, myName: String? = null, apiName: String? = null, apiToken: String? = null): GroupMembers {
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/group/groupMembers?groupid=$groupId", apiName, apiToken)
            val (code, body) = read(conn)
            if (code !in 200..299) return GroupMembers(false, "WiGLE members HTTP $code", "", emptyList())
            val j = JSONObject(body)
            val gname = j.optJSONObject("group")?.optString("groupName") ?: ""
            val arr = j.optJSONArray("users")
            val raw = ArrayList<GroupMember>(arr?.length() ?: 0)
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.getJSONObject(i)
                if (o.optString("status").equals("L", ignoreCase = true)) continue
                val name = o.optString("username")
                raw.add(GroupMember(
                    rank = 0, name = name,
                    discovered = o.optLong("discovered"), total = o.optLong("total"),
                    monthCount = o.optLong("monthCount"), prevMonthCount = o.optLong("prevMonthCount"),
                    genDisc = o.optLong("genDisc"),
                    firstSeen = transDate(o.optString("firstTransid")), lastSeen = transDate(o.optString("lastTransid")),
                    self = !myName.isNullOrBlank() && name.equals(myName, ignoreCase = true)))
            }
            val ranked = raw.sortedByDescending { it.discovered }.mapIndexed { i, m -> m.copy(rank = i + 1) }
            GroupMembers(true, "ok", gname, ranked)
        } catch (e: Exception) {
            GroupMembers(false, e.message ?: "error", "", emptyList())
        } finally {
            conn?.disconnect()
        }
    }

    private fun transDate(t: String): String {
        val d = t.substringBefore("-")
        return if (d.length == 8 && d.all { it.isDigit() }) "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" else ""
    }

    fun user(apiName: String, apiToken: String): Personal? {
        if (apiName.isBlank() || apiToken.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/stats/user", apiName, apiToken)
            val (code, body) = read(conn)
            if (code !in 200..299) return null
            val j = JSONObject(body)
            if (!j.optBoolean("success", false)) return null
            val s = j.optJSONObject("statistics") ?: JSONObject()

            fun deanon(rank: Long) = if (rank > 1) rank - 1 else rank
            Personal(
                name = j.optString("user").ifBlank { s.optString("userName") },
                rank = deanon(j.optLong("rank", s.optLong("rank"))),
                monthRank = deanon(j.optLong("monthRank", s.optLong("monthRank"))),
                wifiGps = s.optLong("discoveredWiFiGPS"),
                bt = s.optLong("discoveredBtGPS"),
                cell = s.optLong("discoveredCellGPS"),
                prevRank = deanon(s.optLong("prevRank")),
                prevMonthRank = deanon(s.optLong("prevMonthRank")),
                wifiTotal = s.optLong("discoveredWiFi"),
                totalWiFiLocations = s.optLong("totalWiFiLocations"),
                wifiPercent = s.optDouble("discoveredWiFiGPSPercent", 0.0),
                btTotal = s.optLong("discoveredBt"), cellTotal = s.optLong("discoveredCell"),
                monthCount = s.optLong("eventMonthCount"), prevMonthCount = s.optLong("eventPrevMonthCount"),
                firstSeen = transDate(s.optString("first")), lastSeen = transDate(s.optString("last"))
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
