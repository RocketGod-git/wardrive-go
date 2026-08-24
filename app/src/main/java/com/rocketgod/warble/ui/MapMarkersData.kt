package com.rocketgod.warble.ui

import com.rocketgod.warble.core.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun buildMapMarkersIn(
    repo: Repository, south: Double, north: Double, west: Double, east: Double
): List<MapMarker> = withContext(Dispatchers.IO) {
    val out = ArrayList<MapMarker>()
    val blocked = repo.blockedKeysNow()

    for (r in repo.notableRowsIn(south, north, west, east)) {
        if (r.key.lowercase() in blocked) continue
        val t = com.rocketgod.warble.classify.NotableDevices.threat(r.name, r.key, r.companyId, r.category) ?: continue

        val cat = com.rocketgod.warble.classify.NotableDevices.match(r.name, r.key, r.companyId, emptyList())?.category
        val icon = when (cat) {
            com.rocketgod.warble.classify.NotableCategory.DRONE -> MapMarkerIcon.DRONE
            com.rocketgod.warble.classify.NotableCategory.FLIPPER,
            com.rocketgod.warble.classify.NotableCategory.RFTOOL,
            com.rocketgod.warble.classify.NotableCategory.HACKTOOL -> MapMarkerIcon.BOLT
            com.rocketgod.warble.classify.NotableCategory.TRACKER -> MapMarkerIcon.TAG
            null -> when (t.lane) {
                com.rocketgod.warble.classify.ThreatLane.ATTACK -> MapMarkerIcon.BOLT
                com.rocketgod.warble.classify.ThreatLane.TRACKING -> MapMarkerIcon.TAG
                else -> MapMarkerIcon.CAMERA
            }
            else -> MapMarkerIcon.CAMERA
        }
        out.add(MapMarker(
            r.lat, r.lng, MapMarkerKind.OFFENSIVE,
            t.lane.colorArgb.toInt(), t.label, r.key, icon))
        if (out.size >= 800) break
    }

    for (p in repo.pmkidsAllNow()) {
        if (p.bssid.lowercase() in blocked) continue
        val la = p.lat ?: continue; val ln = p.lng ?: continue
        if (la < south || la > north || ln < west || ln > east) continue
        val hs = p.kind == 2
        out.add(MapMarker(
            la, ln, if (hs) MapMarkerKind.HANDSHAKE else MapMarkerKind.PMKID,
            if (hs) 0xFF00E5FF.toInt() else 0xFFFFC107.toInt(),
            p.ssid?.ifBlank { null } ?: p.bssid, p.pmkid,
            if (hs) MapMarkerIcon.LINK else MapMarkerIcon.KEY))
    }
    spreadColocatedMarkers(out)
}

private fun spreadColocatedMarkers(markers: List<MapMarker>): List<MapMarker> {
    if (markers.size < 2) return markers
    val groups = markers.groupBy { "%.5f,%.5f".format(it.lat, it.lng) }
    if (groups.none { it.value.size > 1 }) return markers
    val out = ArrayList<MapMarker>(markers.size)
    for ((_, g) in groups) {
        if (g.size == 1) { out.add(g[0]); continue }
        val n = g.size
        g.forEachIndexed { i, m ->
            val ang = 2.0 * Math.PI * i / n
            val dLat = (8.0 * kotlin.math.cos(ang)) / 111_111.0
            val dLng = (8.0 * kotlin.math.sin(ang)) / (111_111.0 * kotlin.math.cos(Math.toRadians(m.lat)).coerceAtLeast(1e-6))
            out.add(m.copy(lat = m.lat + dLat, lng = m.lng + dLng))
        }
    }
    return out
}
