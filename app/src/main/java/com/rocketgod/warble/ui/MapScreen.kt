package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay

data class MapPoint(val lat: Double, val lng: Double, val label: String, val key: String)

enum class MapMarkerKind { OFFENSIVE, PMKID, HANDSHAKE }

enum class MapMarkerIcon { CAMERA, DRONE, BOLT, TAG, KEY, LINK }
data class MapMarker(val lat: Double, val lng: Double, val kind: MapMarkerKind,
                     val colorArgb: Int, val label: String, val key: String, val icon: MapMarkerIcon)

internal fun wigleTileSource(): OnlineTileSourceBase = object : OnlineTileSourceBase(
    "WiGLEMine", 1, 20, 512, "", arrayOf("https://wigle.net/clientTile"), "© WiGLE.net"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val toYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1
        return "https://wigle.net/clientTile?zoom=$z&x=$x&y=$y" +
            "&startTransID=20010000-00000&endTransID=${toYear}0000-00000&sizeX=512&sizeY=512&onlymine=1"
    }
}

internal fun syncWigleOverlay(mv: MapView, on: Boolean, name: String, token: String, ref: MutableState<TilesOverlay?>) {
    val existing = ref.value
    if (on && existing == null) {
        val b64 = android.util.Base64.encodeToString("$name:$token".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        Configuration.getInstance().additionalHttpRequestProperties.put("Authorization", "Basic $b64")
        val ov = TilesOverlay(MapTileProviderBasic(mv.context, wigleTileSource()), mv.context).apply {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            loadingLineColor = android.graphics.Color.TRANSPARENT
        }
        mv.overlays.add(0, ov)
        ref.value = ov
        mv.invalidate()
    } else if (!on && existing != null) {
        mv.overlays.remove(existing)
        ref.value = null
        mv.invalidate()
    }
}

private class DotsOverlay(
    private val points: List<MapPoint>,
    colorInt: Int,
    private val onTap: (String) -> Unit
) : Overlay() {
    private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; alpha = 55 }
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1618.toInt() }
    private val core = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
    private val hi = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 205 }

    private val labelFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
    private val labelHalo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6000000.toInt(); textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 6f
    }
    private val tmp = android.graphics.Point()

    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val w = canvas.width; val h = canvas.height

        val showLabels = mapView.zoomLevelDouble >= 17.0
        for (p in points) {
            proj.toPixels(GeoPoint(p.lat, p.lng), tmp)
            val x = tmp.x.toFloat(); val y = tmp.y.toFloat()
            if (x < -40f || y < -40f || x > w + 40f || y > h + 40f) continue
            canvas.drawCircle(x, y, 16f, glow)
            canvas.drawCircle(x, y, 9.5f, ring)
            canvas.drawCircle(x, y, 6f, core)
            canvas.drawCircle(x - 1.8f, y - 1.8f, 2f, hi)
            if (showLabels) {
                val label = p.label.ifBlank { "<hidden>" }.take(24)
                val ly = y - 16f
                canvas.drawText(label, x, ly, labelHalo)
                canvas.drawText(label, x, ly, labelFill)
            }
        }
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        var best: String? = null
        var bestD = Float.MAX_VALUE
        for (p in points) {
            proj.toPixels(GeoPoint(p.lat, p.lng), tmp)
            val dx = e.x - tmp.x; val dy = e.y - tmp.y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = p.key }
        }
        return if (best != null && bestD < 44f * 44f) { onTap(best); true } else false
    }
}

@Composable
fun MapScreen(
    title: String, points: List<MapPoint>, accent: Color,
    markers: List<MapMarker> = emptyList(),
    currentPose: () -> Triple<Double, Double, Float?>? = { null },
    onSelectKey: (String) -> Unit, onBack: () -> Unit
) {
    val accentInt = accent.toArgb()
    val clean = points.filter { !(it.lat == 0.0 && it.lng == 0.0) }

    val ctx = LocalContext.current

    val enabledIcons = MapLayerFilter.enabled
    var filterOpen by remember { mutableStateOf(false) }
    var markersOv by remember { mutableStateOf<MarkersOverlay?>(null) }
    var mapRefState by remember { mutableStateOf<MapView?>(null) }
    LaunchedEffect(enabledIcons) {
        markersOv?.let { it.enabled = enabledIcons; mapRefState?.invalidate() }
    }

    Box(Modifier.fillMaxSize().background(Palette.paper)) {
        if (clean.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(120.dp))
                Icon(Icons.Filled.LocationOff, null, tint = Palette.muted, modifier = Modifier.width(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No geotagged devices here yet.", color = Palette.ink, fontFamily = Mono, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text("Contacts get a location stamp when a GPS fix is available while scanning. Move around outdoors and they'll start appearing on the map.",
                    color = Palette.muted, fontFamily = Mono, fontSize = 12.sp)
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        mapRefState = this
                        applyBasemap(this, UiFlags.darkMap)
                        setMultiTouchControls(true)
                        setUseDataConnection(true)
                        setTilesScaledToDpi(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                        val cLat = clean.map { it.lat }.average()
                        val cLng = clean.map { it.lng }.average()

                        controller.setZoom(if (clean.size == 1) 18.0 else 13.0)
                        controller.setCenter(GeoPoint(cLat, cLng))

                        if (clean.size > 3000) {
                            val geos = clean.map { GeoPoint(it.lat, it.lng) as org.osmdroid.api.IGeoPoint }
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = accentInt; style = android.graphics.Paint.Style.FILL
                            }
                            val opts = org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions.getDefaultStyle()
                                .setAlgorithm(org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                                .setRadius(5f).setIsClickable(false).setCellSize(12).setPointStyle(paint)
                            overlays.add(org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay(
                                org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme(geos, false), opts))
                        } else {
                            overlays.add(DotsOverlay(clean, accentInt, onSelectKey))
                        }

                        if (markers.isNotEmpty()) {
                            val mk = MarkersOverlay(ctx, markers, enabledIcons) { onSelectKey(it.key) }
                            overlays.add(mk); markersOv = mk
                        }

                        overlays.add(MeOverlay(accentInt, currentPose))
                        invalidate()
                        if (clean.size > 1) {
                            val geos = clean.map { GeoPoint(it.lat, it.lng) }
                            post { zoomToBoundingBox(BoundingBox.fromGeoPoints(geos).increaseByScale(1.4f), true, 110) }
                        }
                        onResume()
                    }
                },
                update = { mv -> applyBasemap(mv, UiFlags.darkMap) }
            )
        }

        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Palette.paper.copy(alpha = 0.92f), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(Palette.surface.copy(alpha = 0.92f), CircleShape)
                        .border(1.dp, accent.copy(alpha = 0.6f), CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.ArrowBack, "back", tint = accent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                    Text("${clean.size} geotagged · tap a dot for detail", color = accent, fontFamily = Mono, fontSize = 12.sp)
                }
            }
        }

        if (clean.isNotEmpty()) {
            Text(
                "© OpenStreetMap · CARTO",
                color = Palette.paper.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Palette.ink.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        if (clean.isNotEmpty()) {
            val filterChips = listOf(
                com.rocketgod.warble.R.drawable.ic_marker_camera to setOf(MapMarkerIcon.CAMERA),
                com.rocketgod.warble.R.drawable.ic_marker_drone to setOf(MapMarkerIcon.DRONE),
                com.rocketgod.warble.R.drawable.ic_marker_bolt to setOf(MapMarkerIcon.BOLT),
                com.rocketgod.warble.R.drawable.ic_marker_tag to setOf(MapMarkerIcon.TAG),
                com.rocketgod.warble.R.drawable.ic_marker_key to setOf(MapMarkerIcon.KEY, MapMarkerIcon.LINK)
            )

            Column(
                Modifier.align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp)
                    .systemGestureExclusion(),
                horizontalAlignment = Alignment.End
            ) {
                if (markers.isNotEmpty()) {
                    if (filterOpen) filterChips.forEach { (res, icons) ->
                        val on = enabledIcons.containsAll(icons)
                        Box(
                            Modifier.size(36.dp)
                                .background(if (on) accent else Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(9.dp))
                                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
                                .clickable { MapLayerFilter.toggle(ctx, icons) },
                            contentAlignment = Alignment.Center
                        ) { Icon(painterResource(res), null, tint = if (on) inkOn(accent) else Palette.muted, modifier = Modifier.size(19.dp)) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Box(
                        Modifier.size(40.dp)
                            .background(if (filterOpen) accent else Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .clickable { filterOpen = !filterOpen },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.FilterList, "marker filters", tint = if (filterOpen) inkOn(accent) else accent, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.height(8.dp))
                }

                Box(
                    Modifier.size(46.dp)
                        .background(Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                        .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .clickable {
                            val mv = mapRefState
                            val pose = currentPose()?.takeIf { !(it.first == 0.0 && it.second == 0.0) }
                            if (mv != null) {
                                when {
                                    pose != null -> mv.controller.animateTo(GeoPoint(pose.first, pose.second), 17.0, 800L)
                                    clean.size == 1 -> mv.controller.animateTo(GeoPoint(clean[0].lat, clean[0].lng), 17.0, 800L)
                                    clean.size > 1 -> {
                                        val geos = clean.map { GeoPoint(it.lat, it.lng) }
                                        mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(geos).increaseByScale(1.4f), true, 110)
                                    }
                                }
                                mv.invalidate()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.MyLocation, "my location", tint = accent, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}
