package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme

internal fun cartoBasemap(dark: Boolean): XYTileSource {
    val style = if (dark) "dark_nolabels" else "rastertiles/voyager"
    return XYTileSource(
        if (dark) "CartoDarkNoLabels" else "CartoVoyager", 1, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/$style/",
            "https://b.basemaps.cartocdn.com/$style/",
            "https://c.basemaps.cartocdn.com/$style/",
            "https://d.basemaps.cartocdn.com/$style/"
        ),
        "© OpenStreetMap contributors, © CARTO"
    )
}

private val DARK_TILE_FILTER = android.graphics.ColorMatrixColorFilter(
    android.graphics.ColorMatrix(floatArrayOf(
        1.35f, 0f, 0f, 0f, 48f,
        0f, 1.35f, 0f, 0f, 48f,
        0f, 0f, 1.35f, 0f, 48f,
        0f, 0f, 0f, 1f, 0f
    ))
)

private val DARK_LABEL_FILTER = android.graphics.ColorMatrixColorFilter(
    android.graphics.ColorMatrix(floatArrayOf(
        1.3f, 0f, 0f, 0f, 10f,
        0f, 1.3f, 0f, 0f, 10f,
        0f, 0f, 1.3f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f
    ))
)

private class DarkLabelsOverlay(p: org.osmdroid.tileprovider.MapTileProviderBase, ctx: android.content.Context) : TilesOverlay(p, ctx)

internal fun applyBasemap(mv: MapView, dark: Boolean) {
    val want = if (dark) "CartoDarkNoLabels" else "CartoVoyager"
    if (mv.tileProvider.tileSource.name() != want) {
        mv.setTileSource(cartoBasemap(dark))
        mv.overlayManager.tilesOverlay.setColorFilter(if (dark) DARK_TILE_FILTER else null)

        mv.overlays.removeAll { it is DarkLabelsOverlay }
        if (dark) {

            val labelSrc = XYTileSource("CartoDarkLabels2x", 1, 20, 512, "@2x.png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/dark_only_labels/",
                    "https://b.basemaps.cartocdn.com/dark_only_labels/",
                    "https://c.basemaps.cartocdn.com/dark_only_labels/",
                    "https://d.basemaps.cartocdn.com/dark_only_labels/"
                ), "© OpenStreetMap contributors, © CARTO")
            val prov = org.osmdroid.tileprovider.MapTileProviderBasic(mv.context, labelSrc)
            mv.overlays.add(0, DarkLabelsOverlay(prov, mv.context).apply {
                loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                setColorFilter(DARK_LABEL_FILTER)
            })
        }
        mv.invalidate()
    }
}

internal class MeOverlay(accentInt: Int, private val getPose: () -> Triple<Double, Double, Float?>?) : Overlay() {
    private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accentInt; alpha = 55 }
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accentInt }
    private val hi = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt() }
    private val pt = android.graphics.Point()
    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pose = getPose() ?: return
        val (lat, lng, _) = pose
        mapView.projection.toPixels(GeoPoint(lat, lng), pt)
        val x = pt.x.toFloat(); val y = pt.y.toFloat()
        val d = mapView.resources.displayMetrics.density
        canvas.drawCircle(x, y, 15f * d, glow)
        canvas.drawCircle(x, y, 8f * d, ring)
        canvas.drawCircle(x, y, 5.5f * d, dot)
        canvas.drawCircle(x - 1.6f * d, y - 1.6f * d, 1.8f * d, hi)
    }
}

private class FindsPointsOverlay(color: Int, @Volatile var points: List<MapPoint>, private val onTap: (String) -> Unit) : Overlay() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = android.graphics.Paint.Style.FILL
    }
    private val pt = android.graphics.Point()
    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val w = mapView.width; val h = mapView.height
        val margin = kotlin.math.max(w, h)
        for (p in points) {
            proj.toPixels(GeoPoint(p.lat, p.lng), pt)
            if (pt.x < -margin || pt.y < -margin || pt.x > w + margin || pt.y > h + margin) continue
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5f, paint)
        }
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        var best: String? = null; var bestD = Float.MAX_VALUE
        for (p in points) {
            proj.toPixels(GeoPoint(p.lat, p.lng), pt)
            val dx = e.x - pt.x; val dy = e.y - pt.y; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = p.key }
        }
        val r = 24f * mapView.resources.displayMetrics.density
        return if (best != null && bestD < r * r) { onTap(best); true } else false
    }
}

internal class MarkersOverlay(
    private val ctx: android.content.Context,
    @Volatile var markers: List<MapMarker>,
    @Volatile var enabled: Set<MapMarkerIcon>,
    private val onTap: (MapMarker) -> Unit
) : Overlay() {
    private val density = ctx.resources.displayMetrics.density
    private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1618.toInt() }
    private val core = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    private val pt = android.graphics.Point()

    private val coreR = 7f * density; private val ringR = 8.7f * density; private val glowR = 11f * density
    private val iconPx = (13f * density).toInt().coerceAtLeast(12)
    private val tapR = 24f * density
    private val iconCache = HashMap<MapMarkerIcon, android.graphics.Bitmap?>()
    private fun resOf(icon: MapMarkerIcon) = when (icon) {
        MapMarkerIcon.CAMERA -> com.rocketgod.warble.R.drawable.ic_marker_camera
        MapMarkerIcon.DRONE -> com.rocketgod.warble.R.drawable.ic_marker_drone
        MapMarkerIcon.BOLT -> com.rocketgod.warble.R.drawable.ic_marker_bolt
        MapMarkerIcon.TAG -> com.rocketgod.warble.R.drawable.ic_marker_tag
        MapMarkerIcon.KEY -> com.rocketgod.warble.R.drawable.ic_marker_key
        MapMarkerIcon.LINK -> com.rocketgod.warble.R.drawable.ic_marker_link
    }
    private fun bmp(icon: MapMarkerIcon): android.graphics.Bitmap? = iconCache.getOrPut(icon) {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, resOf(icon)) ?: return@getOrPut null
        val b = android.graphics.Bitmap.createBitmap(iconPx, iconPx, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); d.setBounds(0, 0, iconPx, iconPx); d.draw(c); b
    }
    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val w = mapView.width; val h = mapView.height
        val margin = kotlin.math.max(w, h)
        for (m in markers) {
            if (m.icon !in enabled) continue
            proj.toPixels(GeoPoint(m.lat, m.lng), pt)
            if (pt.x < -margin || pt.y < -margin || pt.x > w + margin || pt.y > h + margin) continue
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            glow.color = m.colorArgb; glow.alpha = 70; canvas.drawCircle(x, y, glowR, glow)
            canvas.drawCircle(x, y, ringR, ring)
            core.color = m.colorArgb; canvas.drawCircle(x, y, coreR, core)
            bmp(m.icon)?.let { canvas.drawBitmap(it, x - iconPx / 2f, y - iconPx / 2f, null) }
        }
    }
    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        var best: MapMarker? = null; var bestD = Float.MAX_VALUE
        for (m in markers) {
            if (m.icon !in enabled) continue
            proj.toPixels(GeoPoint(m.lat, m.lng), pt)
            val dx = e.x - pt.x; val dy = e.y - pt.y; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = m }
        }
        return if (best != null && bestD < tapR * tapR) { onTap(best); true } else false
    }
}

@Composable
fun FindsMapTool(
    accent: Color,
    currentPose: () -> Triple<Double, Double, Float?>?,
    loadPointsIn: suspend (south: Double, north: Double, west: Double, east: Double) -> List<MapPoint>,
    onOpenFull: () -> Unit,
    loadMarkersIn: suspend (south: Double, north: Double, west: Double, east: Double) -> List<MapMarker> = { _, _, _, _ -> emptyList() },
    onMarkerTap: (MapMarker) -> Unit = {},
    onOpenPoint: (String) -> Unit = {},
    seedCenter: suspend () -> Pair<Double, Double>? = { null },

    onBack: (() -> Unit)? = null,
    wigleName: String = "",
    wigleToken: String = ""
) {
    val accentInt = accent.toArgb()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var meRef by remember { mutableStateOf<MeOverlay?>(null) }
    var overlayRef by remember { mutableStateOf<Overlay?>(null) }
    var markersRef by remember { mutableStateOf<MarkersOverlay?>(null) }

    val enabledIcons = MapLayerFilter.enabled
    var filterOpen by remember { mutableStateOf(false) }

    val hasWigle = wigleName.isNotBlank() && wigleToken.isNotBlank()
    var showWigle by remember { mutableStateOf(false) }
    val wigleOv = remember { mutableStateOf<TilesOverlay?>(null) }
    var count by remember { mutableStateOf(-1) }

    var follow by rememberSaveable { mutableStateOf(true) }
    var savedLat by rememberSaveable { mutableStateOf(Double.NaN) }
    var savedLng by rememberSaveable { mutableStateOf(Double.NaN) }
    var savedZoom by rememberSaveable { mutableStateOf(Double.NaN) }
    var rotated by remember { mutableStateOf(false) }
    var reloadJob by remember { mutableStateOf<Job?>(null) }
    var lastReload by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var lastReloadAt by remember { mutableStateOf(0L) }

    val meHolder = remember { arrayOfNulls<Triple<Double, Double, Float?>>(1) }

    fun reload(mv: MapView, gate: Boolean) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(300)
            val bb = mv.boundingBox ?: return@launch
            val cLat = (bb.latNorth + bb.latSouth) / 2.0; val cLon = (bb.lonEast + bb.lonWest) / 2.0
            if (gate) lastReload?.let { lr ->
                val spanLat = kotlin.math.abs(bb.latNorth - bb.latSouth); val spanLon = kotlin.math.abs(bb.lonEast - bb.lonWest)

                val stale = System.currentTimeMillis() - lastReloadAt > 2500L
                if (!stale && kotlin.math.abs(cLat - lr.first) < spanLat * 0.25 && kotlin.math.abs(cLon - lr.second) < spanLon * 0.25) return@launch
            }
            val pts = runCatching { loadPointsIn(bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast) }.getOrNull() ?: return@launch
            lastReloadAt = System.currentTimeMillis()
            lastReload = cLat to cLon
            count = pts.size
            val geos = pts.map { GeoPoint(it.lat, it.lng) }

            val newOv: Overlay = if (follow || geos.size <= 4000) {

                val capped = if (pts.size > 12000) pts.filterIndexed { i, _ -> i % (pts.size / 12000 + 1) == 0 } else pts
                FindsPointsOverlay(accentInt, capped, onOpenPoint)
            } else {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentInt; style = android.graphics.Paint.Style.FILL
                }
                val opts = SimpleFastPointOverlayOptions.getDefaultStyle()
                    .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                    .setRadius(5f).setIsClickable(false).setCellSize(6).setPointStyle(paint)
                SimpleFastPointOverlay(SimplePointTheme(geos, false), opts)
            }
            overlayRef?.let { mv.overlays.remove(it) }
            mv.overlays.add(newOv)
            overlayRef = newOv
            meRef?.let { mv.overlays.remove(it); mv.overlays.add(it) }

            val marks = runCatching { loadMarkersIn(bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast) }.getOrNull() ?: emptyList()
            val mk = markersRef
            if (mk == null) { val ov = MarkersOverlay(mv.context, marks, enabledIcons, onMarkerTap); mv.overlays.add(ov); markersRef = ov }
            else { mk.markers = marks; mv.overlays.remove(mk); mv.overlays.add(mk) }
            mv.invalidate()
        }
    }

    LaunchedEffect(Unit) { while (true) { delay(1000); mapRef?.invalidate() } }

    LaunchedEffect(mapRef) {
        val mv = mapRef ?: return@LaunchedEffect
        if (!savedLat.isNaN()) return@LaunchedEffect
        if (currentPose() != null) return@LaunchedEffect
        val c = runCatching { seedCenter() }.getOrNull() ?: return@LaunchedEffect
        if (currentPose() != null) return@LaunchedEffect
        mv.controller.setCenter(GeoPoint(c.first, c.second))
        reload(mv, gate = false)
    }

    LaunchedEffect(enabledIcons) {
        markersRef?.let { it.enabled = enabledIcons; mapRef?.invalidate() }
    }

    LaunchedEffect(accentInt) {
        val mv = mapRef ?: return@LaunchedEffect
        meRef?.let { mv.overlays.remove(it) }
        val me = MeOverlay(accentInt) { meHolder[0] ?: currentPose() }
        mv.overlays.add(me); meRef = me
        reload(mv, gate = false)
        mv.invalidate()
    }

    LaunchedEffect(follow) {
        if (!follow) { meHolder[0] = null; mapRef?.let { it.setMapOrientation(0f); it.invalidate() }; rotated = false; return@LaunchedEffect }

        mapRef?.let { reload(it, gate = false) }
        var smooth = Float.NaN
        var dLat = Double.NaN; var dLon = Double.NaN
        var frame = 0

        while (true) {
            val mv = mapRef
            val pose = if (mv != null) currentPose() else null
            if (mv != null && pose != null) {
                val (la, ln, bearing) = pose
                var changed = false

                val nLat = if (dLat.isNaN()) la else dLat + (la - dLat) * 0.15
                val nLon = if (dLon.isNaN()) ln else dLon + (ln - dLon) * 0.15
                if (dLat.isNaN() || kotlin.math.abs(nLat - dLat) > 1e-7 || kotlin.math.abs(nLon - dLon) > 1e-7) {
                    dLat = nLat; dLon = nLon
                    mv.controller.setCenter(GeoPoint(dLat, dLon)); changed = true
                } else { dLat = nLat; dLon = nLon }
                meHolder[0] = Triple(dLat, dLon, bearing)

                if (bearing != null) {
                    val prev = smooth
                    smooth = if (smooth.isNaN()) bearing else {
                        var d = bearing - smooth
                        while (d > 180f) d -= 360f; while (d < -180f) d += 360f
                        smooth + d * 0.15f
                    }
                    if (prev.isNaN() || kotlin.math.abs(smooth - prev) > 0.05f) {
                        var s = smooth % 360f; if (s < 0f) s += 360f
                        mv.setMapOrientation(-s); rotated = true; changed = true
                    }
                }
                if (changed) mv.invalidate()
                if (frame % 20 == 0) reload(mv, gate = true)
                frame++
            }
            delay(45)
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().clipToBounds(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    mapRef = this
                    applyBasemap(this, UiFlags.darkMap)
                    setMultiTouchControls(true)
                    setTilesScaledToDpi(true)
                    @Suppress("ClickableViewAccessibility")
                    setOnTouchListener { v, ev ->
                        if (onBack == null) {

                            when (ev.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN ->
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                android.view.MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount >= 2) {
                                    v.parent?.requestDisallowInterceptTouchEvent(true); if (follow) follow = false
                                }
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                            }

                            if (ev.actionMasked == android.view.MotionEvent.ACTION_MOVE && ev.pointerCount < 2) return@setOnTouchListener true
                        } else {

                            if (ev.actionMasked == android.view.MotionEvent.ACTION_MOVE && ev.pointerCount == 1 && follow) follow = false
                        }
                        false
                    }
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                    val here = currentPose()

                    controller.setZoom(if (!savedZoom.isNaN()) savedZoom else 16.0)
                    controller.setCenter(
                        if (!savedLat.isNaN() && !savedLng.isNaN()) GeoPoint(savedLat, savedLng)
                        else GeoPoint(here?.first ?: 39.5, here?.second ?: -98.35)
                    )
                    val me = MeOverlay(accentInt) { meHolder[0] ?: currentPose() }
                    overlays.add(me); meRef = me
                    addMapListener(object : MapListener {

                        override fun onScroll(e: ScrollEvent?): Boolean {
                            if (!follow) reload(this@apply, gate = false)
                            savedLat = mapCenter.latitude; savedLng = mapCenter.longitude; savedZoom = zoomLevelDouble
                            return false
                        }
                        override fun onZoom(e: ZoomEvent?): Boolean {
                            reload(this@apply, gate = false)
                            savedLat = mapCenter.latitude; savedLng = mapCenter.longitude; savedZoom = zoomLevelDouble
                            return false
                        }
                    })
                    onResume()
                    post { reload(this, gate = false) }
                }
            },
            update = { mv -> applyBasemap(mv, UiFlags.darkMap); syncWigleOverlay(mv, showWigle && hasWigle, wigleName, wigleToken, wigleOv) }
        )

        Text(
            when { count < 0 -> "Loading finds…"; count == 0 -> "No finds in view"; else -> "$count in view" },
            color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp,

            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                .background(Palette.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp)
        )

        if (onBack == null) Text(
            "✌ two fingers to move the map",
            color = Palette.muted, fontFamily = Mono, fontSize = 9.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                .background(Palette.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)
        )

        val filterChips = listOf(
            com.rocketgod.warble.R.drawable.ic_marker_camera to setOf(MapMarkerIcon.CAMERA),
            com.rocketgod.warble.R.drawable.ic_marker_drone to setOf(MapMarkerIcon.DRONE),
            com.rocketgod.warble.R.drawable.ic_marker_bolt to setOf(MapMarkerIcon.BOLT),
            com.rocketgod.warble.R.drawable.ic_marker_tag to setOf(MapMarkerIcon.TAG),
            com.rocketgod.warble.R.drawable.ic_marker_key to setOf(MapMarkerIcon.KEY, MapMarkerIcon.LINK)
        )

        val edgeSafe: Modifier = if (onBack != null) Modifier.navigationBarsPadding() else Modifier
        val gestureSafe: Modifier = if (onBack != null) Modifier.systemGestureExclusion() else Modifier
        Column(Modifier.align(Alignment.BottomStart).then(edgeSafe).padding(6.dp).then(gestureSafe), horizontalAlignment = Alignment.Start) {
            if (filterOpen) filterChips.forEach { (res, icons) ->
                val on = enabledIcons.containsAll(icons)
                Box(
                    Modifier.size(30.dp)
                        .background(if (on) accent else Palette.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable { MapLayerFilter.toggle(ctx, icons) },
                    contentAlignment = Alignment.Center
                ) { Icon(painterResource(res), null, tint = if (on) inkOn(accent) else Palette.muted, modifier = Modifier.size(16.dp)) }
                Spacer(Modifier.height(6.dp))
            }
            Box(
                Modifier.size(30.dp)
                    .background(if (filterOpen) accent else Palette.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable { filterOpen = !filterOpen },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Layers, "marker filters", tint = if (filterOpen) inkOn(accent) else accent, modifier = Modifier.size(18.dp)) }
        }

        Column(Modifier.align(Alignment.BottomEnd).then(edgeSafe).padding(6.dp).then(gestureSafe), horizontalAlignment = Alignment.End) {
            Box(
                Modifier.size(30.dp)
                    .background(if (follow) accent else Palette.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable {
                        follow = !follow

                        if (follow) currentPose()?.let { (la, ln, _) -> mapRef?.let { m -> m.controller.animateTo(GeoPoint(la, ln), m.zoomLevelDouble, 700L) } }
                    },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.MyLocation, "follow my location", tint = if (follow) inkOn(accent) else accent, modifier = Modifier.size(18.dp)) }
            if (onBack == null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.size(30.dp)
                        .background(Palette.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable { onOpenFull() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.OpenInFull, "open full map", tint = accent, modifier = Modifier.size(18.dp)) }
            }
        }

        if (onBack != null) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(30.dp)
                    .background(Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, "back", tint = accent, modifier = Modifier.size(18.dp)) }
        }
    }
}
