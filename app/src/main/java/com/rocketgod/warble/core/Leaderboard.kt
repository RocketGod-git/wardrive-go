package com.rocketgod.warble.core

import com.rocketgod.warble.model.SignalType
import com.rocketgod.warble.model.Stats

data class Achievement(
    val id: String,
    val title: String,
    val detail: String,
    val threshold: Long,
    val metric: Metric
) {
    enum class Metric { LIFETIME, BEST_RUN, THIS_RUN }

    fun unlocked(stats: Stats): Boolean = when (metric) {
        Metric.LIFETIME -> stats.wifiLifetime >= threshold
        Metric.BEST_RUN -> stats.wifiBestRun >= threshold
        Metric.THIS_RUN -> stats.wifiThisRun >= threshold
    }
    fun progress(stats: Stats): Float {
        val v = when (metric) {
            Metric.LIFETIME -> stats.wifiLifetime
            Metric.BEST_RUN -> stats.wifiBestRun
            Metric.THIS_RUN -> stats.wifiThisRun
        }
        return (v.toFloat() / threshold).coerceIn(0f, 1f)
    }
}

data class RecordRow(val label: String, val value: String)

object Leaderboard {

    val achievements = listOf(

        Achievement("first_contact", "1 Wi-Fi", "Your first Wi-Fi", 1, Achievement.Metric.LIFETIME),
        Achievement("century", "100 Wi-Fi", "100 lifetime Wi-Fi", 100, Achievement.Metric.LIFETIME),
        Achievement("getting_warm", "500 Wi-Fi", "500 lifetime Wi-Fi", 500, Achievement.Metric.LIFETIME),
        Achievement("thousand_cuts", "1K Wi-Fi", "1,000 lifetime Wi-Fi", 1_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_10k", "10K Wi-Fi", "10,000 lifetime Wi-Fi", 10_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_50k", "50K Wi-Fi", "50,000 lifetime Wi-Fi", 50_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_100k", "100K Wi-Fi", "100,000 lifetime Wi-Fi", 100_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_250k", "250K Wi-Fi", "250,000 lifetime Wi-Fi", 250_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_500k", "500K Wi-Fi", "500,000 lifetime Wi-Fi", 500_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_1m", "1M Wi-Fi", "1,000,000 lifetime Wi-Fi", 1_000_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_5m", "5M Wi-Fi", "5,000,000 lifetime Wi-Fi", 5_000_000, Achievement.Metric.LIFETIME),
        Achievement("contacts_10m", "10M Wi-Fi", "10,000,000 lifetime Wi-Fi", 10_000_000, Achievement.Metric.LIFETIME),

        Achievement("pack_hunter", "50 / run", "50+ new Wi-Fi in one run", 50, Achievement.Metric.BEST_RUN),
        Achievement("run_100", "100 / run", "100+ new Wi-Fi in one run", 100, Achievement.Metric.BEST_RUN),
        Achievement("run_250", "250 / run", "250+ new Wi-Fi in one run", 250, Achievement.Metric.BEST_RUN),
        Achievement("run_500", "500 / run", "500+ new Wi-Fi in one run", 500, Achievement.Metric.BEST_RUN),
        Achievement("run_1k", "1K / run", "1,000+ new Wi-Fi in one run", 1_000, Achievement.Metric.BEST_RUN),
        Achievement("run_5k", "5K / run", "5,000+ new Wi-Fi in one run", 5_000, Achievement.Metric.BEST_RUN)
    )

    val runAchievements = listOf(
        Achievement("this_run_50", "50 this run", "50+ new Wi-Fi since your last WiGLE upload", 50, Achievement.Metric.THIS_RUN),
        Achievement("this_run_100", "100 this run", "100+ new Wi-Fi since your last WiGLE upload", 100, Achievement.Metric.THIS_RUN),
        Achievement("this_run_250", "250 this run", "250+ new Wi-Fi since your last WiGLE upload", 250, Achievement.Metric.THIS_RUN),
        Achievement("this_run_500", "500 this run", "500+ new Wi-Fi since your last WiGLE upload", 500, Achievement.Metric.THIS_RUN),
        Achievement("this_run_1k", "1K this run", "1,000+ new Wi-Fi since your last WiGLE upload", 1_000, Achievement.Metric.THIS_RUN),
        Achievement("this_run_5k", "5K this run", "5,000+ new Wi-Fi since your last WiGLE upload", 5_000, Achievement.Metric.THIS_RUN)
    )

    fun unlockedCount(stats: Stats) = achievements.count { it.unlocked(stats) }
    fun runUnlockedCount(stats: Stats) = runAchievements.count { it.unlocked(stats) }

    fun records(stats: Stats): List<RecordRow> {
        val rows = mutableListOf(
            RecordRow("Lifetime Wi-Fi", fmt(stats.wifiLifetime)),
            RecordRow("Best Wi-Fi run", fmt(stats.wifiBestRun)),
            RecordRow("All signals (lifetime)", fmt(stats.lifetime)),
            RecordRow("Runs", fmt(stats.runs)),
            RecordRow("Smart home", fmt(stats.smartHome))
        )
        for (t in stats.perType) rows.add(RecordRow("${t.type.label} unique", fmt(t.unique)))
        return rows
    }

    fun typeStanding(stats: Stats): List<Pair<SignalType, Long>> =
        stats.perType.map { it.type to it.unique }.sortedByDescending { it.second }

    fun fmt(n: Long): String {
        if (n < 1000) return n.toString()
        val units = listOf("k", "M", "B")
        var v = n.toDouble(); var i = -1
        while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
        return if (v >= 100) "${v.toLong()}${units[i]}" else String.format("%.1f%s", v, units[i])
    }
}
