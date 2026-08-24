package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rocketgod.warble.WarbleViewModel
import com.rocketgod.warble.data.PrivacyZone
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

private class ZoneOverlay(
    colorInt: Int,
    private val getRadiusM: () -> Double,
    private val getZones: () -> List<Triple<Double, Double, Double>>
) : Overlay() {
    private val fillActive = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; alpha = 46 }
    private val edgeActive = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt; style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f
    }

    private val fillSaved = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; alpha = 18 }
    private val edgeSaved = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt; alpha = 150; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
    private val dotHi = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 220 }

    private fun drawCircle(
        canvas: android.graphics.Canvas, mv: MapView, lat: Double, lng: Double, radiusM: Double,
        fill: android.graphics.Paint, edge: android.graphics.Paint, centreDot: Boolean
    ) {
        val proj = mv.projection
        val c = android.graphics.Point(); val n = android.graphics.Point()
        proj.toPixels(GeoPoint(lat, lng), c)
        proj.toPixels(GeoPoint(lat + radiusM / 111_320.0, lng), n)
        val rpx = Math.hypot((n.x - c.x).toDouble(), (n.y - c.y).toDouble()).toFloat()
        canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), rpx, fill)
        canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), rpx, edge)
        if (centreDot) {
            canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), 10f, dot)
            canvas.drawCircle(c.x.toFloat() - 2f, c.y.toFloat() - 2f, 3.5f, dotHi)
        }
    }

    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        for ((lat, lng, r) in getZones()) drawCircle(canvas, mapView, lat, lng, r, fillSaved, edgeSaved, centreDot = false)

        val centre = mapView.mapCenter
        drawCircle(canvas, mapView, centre.latitude, centre.longitude, getRadiusM(), fillActive, edgeActive, centreDot = true)
    }
}

@Composable
fun PrivacyScreen(
    vm: WarbleViewModel, accent: Color,
    startLat: Double?, startLng: Double?,
    onBack: () -> Unit
) {
    val accentInt = accent.toArgb()
    val zones by vm.privacyZones.collectAsState()
    val blocked by vm.blockedDevices.collectAsState()
    val uri = LocalUriHandler.current

    val mapRef = remember { mutableStateOf<MapView?>(null) }
    var radiusM by remember { mutableStateOf(300f) }
    var label by remember { mutableStateOf("Home") }
    var excluded by remember { mutableStateOf(-1) }

    LaunchedEffect(zones, blocked) { excluded = vm.repo.excludedCount() }

    Column(Modifier.fillMaxSize().background(Palette.paper)) {

        Row(
            Modifier.fillMaxWidth().zIndex(1f).background(Palette.paper).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).background(Palette.surface, CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.6f), CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, "back", tint = accent, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Privacy & uploads", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Pan your home under the centre pin", color = accent, fontFamily = Mono, fontSize = 13.sp)
            }
            Text("Done", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.clickable { onBack() }.padding(horizontal = 8.dp, vertical = 6.dp))
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).clipToBounds()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().clipToBounds().border(1.dp, Palette.line, RoundedCornerShape(12.dp)),
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        mapRef.value = this
                        applyBasemap(this, UiFlags.darkMap)
                        setMultiTouchControls(true)
                        setTilesScaledToDpi(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                        val cLat = startLat ?: 39.5; val cLng = startLng ?: -98.35
                        controller.setZoom(if (startLat != null) 16.0 else 4.0)
                        controller.setCenter(GeoPoint(cLat, cLng))

                        if (startLat != null) {
                            val m = 300.0 * 1.7
                            val dLat = m / 111_320.0
                            val dLng = m / (111_320.0 * Math.cos(Math.toRadians(cLat)))
                            post { runCatching { zoomToBoundingBox(BoundingBox(cLat + dLat, cLng + dLng, cLat - dLat, cLng - dLng), false, 40) } }
                        }

                        overlays.add(ZoneOverlay(
                            accentInt,
                            getRadiusM = { radiusM.toDouble() },
                            getZones = { zones.filter { it.enabled }.map { Triple(it.lat, it.lng, it.radiusM) } }
                        ))
                        onResume()
                    }
                },
                update = { mv ->

                    @Suppress("UNUSED_EXPRESSION") radiusM
                    @Suppress("UNUSED_EXPRESSION") zones
                    applyBasemap(mv, UiFlags.darkMap)
                    mv.invalidate()
                }
            )
            Text(
                "Drag to pan · pinch to zoom",
                color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                    .background(Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Column(
            Modifier.fillMaxWidth().background(Palette.paper).heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ZONE RADIUS", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(if (radiusM >= 1000f) "%.1f km".format(radiusM / 1000f) else "${radiusM.toInt()} m",
                    color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Slider(
                value = radiusM, onValueChange = { radiusM = it }, valueRange = 100f..2000f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(150 to "150 m", 300 to "300 m", 500 to "500 m", 1000 to "1 km").forEach { (m, lbl) ->
                    val sel = radiusM.toInt() == m
                    Box(
                        Modifier.weight(1f)
                            .background(if (sel) accent.copy(alpha = 0.22f) else Palette.surface, RoundedCornerShape(9.dp))
                            .border(1.dp, if (sel) accent else Palette.line, RoundedCornerShape(9.dp))
                            .clickable { radiusM = m.toFloat() }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(lbl, color = if (sel) accent else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = label, onValueChange = { label = it.take(24) },
                label = { Text("Zone name", fontFamily = Mono, fontSize = 14.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.surface, unfocusedContainerColor = Palette.surface,
                    focusedTextColor = Palette.ink, unfocusedTextColor = Palette.ink,
                    focusedIndicatorColor = accent, cursorColor = accent, focusedLabelColor = accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            val canSave = label.isNotBlank()
            Box(
                Modifier.fillMaxWidth().glassAction(if (canSave) accent else Palette.muted)
                    .clickable(enabled = canSave) {
                        val c = mapRef.value?.mapCenter
                        if (c != null) {
                            vm.addPrivacyZone(c.latitude, c.longitude, radiusM.toDouble(), label.trim())
                            label = "Home"; radiusM = 300f
                        }
                    }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (canSave) "SAVE THIS ZONE" else "NAME THE ZONE FIRST",
                    color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(18.dp))

            Text("EXCLUSION ZONES (${zones.size})", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (zones.isEmpty()) {
                Text("No zones yet — pan your home under the centre pin and save.", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                zones.forEach { z -> ZoneRow(z, accent, onToggle = { vm.setZoneEnabled(z.id, it) }, onDelete = { vm.deletePrivacyZone(z.id) }) }
            }
            Spacer(Modifier.height(16.dp))

            Text("BLOCKED DEVICES (${blocked.size})", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Added from a device's detail screen (the “Exclude from uploads” shield). Blocks it everywhere it's seen — e.g. your phone hotspot or car.",
                color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
            if (blocked.isEmpty()) {
                Text("No blocked devices.", color = Palette.muted, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                blocked.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.label?.ifBlank { b.bssid } ?: b.bssid, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text(b.bssid, color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
                        }
                        Icon(Icons.Filled.Delete, "unblock", tint = Palette.muted,
                            modifier = Modifier.size(20.dp).clickable { vm.unblockDevice(b.bssid) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (excluded >= 0) {
                Text("$excluded stored records currently withheld from uploads.",
                    color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
            }
            Text("Note: this only prevents FUTURE uploads. Anything already sent to WiGLE is public and must be removed through WiGLE's own process.",
                color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("Open WiGLE removal info ›", color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clickable { runCatching { uri.openUri("https://wigle.net/") } })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ZoneRow(z: PrivacyZone, accent: Color, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(z.label, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text("${z.radiusM.toInt()} m radius", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp)
        }
        Switch(checked = z.enabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = accent, checkedTrackColor = accent.copy(alpha = 0.5f)))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Delete, "delete zone", tint = Palette.muted,
            modifier = Modifier.size(20.dp).clickable { onDelete() })
    }
}
