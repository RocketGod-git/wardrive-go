package com.rocketgod.warble.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.util.LruCache
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import com.rocketgod.warble.R
import com.rocketgod.warble.core.Repository
import com.rocketgod.warble.ui.MapMarker
import com.rocketgod.warble.ui.MapMarkerIcon
import com.rocketgod.warble.ui.MapMarkerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

class CarMapRenderer(
    private val carContext: CarContext,
    private val repo: Repository,
    private val accentArgb: () -> Int,
    private val isDark: () -> Boolean,
) : SurfaceCallback {

    private val renderThread = HandlerThread("car-map-render").apply { start() }
    private val ui = Handler(renderThread.looper)
    private val io = Executors.newFixedThreadPool(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val diskDir = java.io.File(carContext.cacheDir, "cartotiles").apply { runCatching { mkdirs() } }

    init {

        io.execute {
            runCatching {
                val files = diskDir.listFiles() ?: return@runCatching
                var total = files.sumOf { it.length() }
                val cap = 128L * 1024 * 1024
                if (total > cap) files.sortedBy { it.lastModified() }.forEach {
                    if (total <= cap) return@forEach
                    total -= it.length(); it.delete()
                }
            }
        }
    }

    @Volatile private var surface: Surface? = null
    @Volatile private var surfW = 0
    @Volatile private var surfH = 0
    @Volatile private var visible: Rect? = null

    @Volatile private var centerLat = Double.NaN
    @Volatile private var centerLon = Double.NaN
    @Volatile private var zoomD = 16.0
    @Volatile private var follow = true
    @Volatile private var heading: Float? = null

    @Volatile private var points: List<CovPt> = emptyList()
    @Volatile private var markers: List<MapMarker> = emptyList()
    private var lastReloadLat = Double.NaN
    private var lastReloadLon = Double.NaN
    private var lastReloadZ = -1
    private var lastReloadAt = 0L
    private var lastFilter: Set<MapMarkerIcon> = emptySet()

    private var projZoom = -1
    private var covX = FloatArray(0); private var covY = FloatArray(0)
    private var mkX = FloatArray(0); private var mkY = FloatArray(0)

    private class CovPt(val lat: Double, val lng: Double)

    private val tileCache = object : LruCache<String, Bitmap>(160 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun configFor(layer: String): Bitmap.Config =
        if (layer == "dark_only_labels") Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
    private val inflight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val baseFilterDark = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        1.35f, 0f, 0f, 0f, 48f,
        0f, 1.35f, 0f, 0f, 48f,
        0f, 0f, 1.35f, 0f, 48f,
        0f, 0f, 0f, 1f, 0f
    )))
    private val labelFilterDark = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        1.3f, 0f, 0f, 0f, 10f,
        0f, 1.3f, 0f, 0f, 10f,
        0f, 0f, 1.3f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f
    )))

    private val tick = object : Runnable {
        override fun run() { step(); ui.postDelayed(this, 1000L) }
    }

    private var drawQueued = false
    private val drawRunnable = Runnable { drawQueued = false; draw() }
    private fun requestDraw() { if (!drawQueued) { drawQueued = true; ui.post(drawRunnable) } }

    private val reloadRunnable = Runnable { reloadIfMoved() }
    private fun scheduleReload() { ui.removeCallbacks(reloadRunnable); ui.postDelayed(reloadRunnable, 250L) }

    private val effTile: Float = TILE * 1.6f

    @Volatile private var panning = false
    private val panEnd = Runnable { panning = false; requestDraw() }

    private var displayRot = 0f

    @Volatile private var targetLat = Double.NaN
    @Volatile private var targetLon = Double.NaN
    private var animFramePending = false
    private val animFrame = Runnable { animFramePending = false; draw() }
    private fun scheduleAnimFrame() { if (!animFramePending) { animFramePending = true; ui.postDelayed(animFrame, 16L) } }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface
        surfW = container.width
        surfH = container.height
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onVisibleAreaChanged(area: Rect) { visible = Rect(area); requestDraw() }
    override fun onStableAreaChanged(area: Rect) { visible = Rect(area); requestDraw() }
    override fun onSurfaceDestroyed(container: SurfaceContainer) { surface = null }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (centerLat.isNaN()) return
        val z = zoomD.roundToInt().coerceIn(MIN_Z, MAX_Z)
        follow = false
        panning = true; ui.removeCallbacks(panEnd); ui.postDelayed(panEnd, 180L)
        var cxf = lon2xf(centerLon, z) + distanceX / effTile
        var cyf = lat2yf(centerLat, z) + distanceY / effTile
        val n = (1 shl z).toDouble()
        cxf = cxf.coerceIn(0.0, n); cyf = cyf.coerceIn(0.0, n)
        centerLon = xf2lon(cxf, z); centerLat = yf2lat(cyf, z)
        requestDraw()
        scheduleReload()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {  }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        zoomD = (zoomD + ln(scaleFactor.toDouble()) / LN2).coerceIn(MIN_Z.toDouble(), MAX_Z.toDouble())
        requestDraw(); scheduleReload()
    }

    fun zoomIn() { zoomD = (zoomD + 1).coerceAtMost(MAX_Z.toDouble()); requestDraw(); scheduleReload() }
    fun zoomOut() { zoomD = (zoomD - 1).coerceAtLeast(MIN_Z.toDouble()); requestDraw(); scheduleReload() }
    fun recenter() { follow = true; ui.post { step() } }

    fun stop() {
        ui.removeCallbacks(tick); ui.removeCallbacks(reloadRunnable); ui.removeCallbacks(drawRunnable); ui.removeCallbacks(panEnd); ui.removeCallbacks(animFrame)
        surface = null
        runCatching { scope.cancel() }
        runCatching { io.shutdownNow() }
        runCatching { renderThread.quitSafely() }
    }

    private fun step() {

        val f = com.rocketgod.warble.ui.MapLayerFilter.enabled
        if (f != lastFilter) { lastFilter = f; lastReloadZ = -1 }
        if (follow) {
            val loc = repo.location.last
            if (loc != null) {

                targetLat = loc.latitude; targetLon = loc.longitude
                if (centerLat.isNaN()) { centerLat = loc.latitude; centerLon = loc.longitude }
                heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.5f) loc.bearing else null
            }
        }
        if (centerLat.isNaN()) {
            scope.launch {
                val seed = runCatching { repo.recentLocatedLatLng() }.getOrNull() ?: return@launch
                if (centerLat.isNaN()) { centerLat = seed.first; centerLon = seed.second; ui.post { reloadIfMoved(); requestDraw() } }
            }
        }
        reloadIfMoved()
        requestDraw()
    }

    private fun reloadIfMoved() {
        val cLat = centerLat
        if (cLat.isNaN()) return
        val z = zoomD.roundToInt().coerceIn(MIN_Z, MAX_Z)
        val now = System.currentTimeMillis()
        val movedFar = lastReloadZ != z ||
            kotlin.math.abs(cLat - lastReloadLat) > spanLat(z) * 0.35 ||
            kotlin.math.abs(centerLon - lastReloadLon) > spanLon(z) * 0.35

        val stale = now - lastReloadAt > LIVE_RELOAD_MS
        if (!movedFar && !stale) return
        lastReloadAt = now
        lastReloadLat = cLat; lastReloadLon = centerLon; lastReloadZ = z
        val south = yf2lat(lat2yf(cLat, z) + (surfH / 2f) / effTile, z)
        val north = yf2lat(lat2yf(cLat, z) - (surfH / 2f) / effTile, z)
        val west = xf2lon(lon2xf(centerLon, z) - (surfW / 2f) / effTile, z)
        val east = xf2lon(lon2xf(centerLon, z) + (surfW / 2f) / effTile, z)
        scope.launch {
            val blocked = runCatching { repo.blockedKeysNow() }.getOrNull() ?: emptySet()

            val p = runCatching { repo.mapPointsIn(south, north, west, east) }.getOrNull()
                ?.asSequence()?.filter { it.type == "WIFI" && it.key.lowercase() !in blocked }
                ?.map { CovPt(it.lat, it.lng) }?.toList() ?: emptyList()

            val enabled = enabledMarkerIcons()
            val m = runCatching { com.rocketgod.warble.ui.buildMapMarkersIn(repo, south, north, west, east) }
                .getOrNull()?.filter { it.icon in enabled } ?: emptyList()
            val capped = if (p.size > 4000) p.filterIndexed { i, _ -> i % (p.size / 4000 + 1) == 0 } else p

            ui.post { points = capped; markers = m; projZoom = -1; requestDraw() }
        }
    }

    private fun ensureProjection(z: Int) {
        if (projZoom == z && covX.size == points.size && mkX.size == markers.size) return
        projZoom = z
        val pts = points; val mks = markers
        covX = FloatArray(pts.size); covY = FloatArray(pts.size)
        for (i in pts.indices) { covX[i] = (lon2xf(pts[i].lng, z) * effTile).toFloat(); covY[i] = (lat2yf(pts[i].lat, z) * effTile).toFloat() }
        mkX = FloatArray(mks.size); mkY = FloatArray(mks.size)
        for (i in mks.indices) { mkX[i] = (lon2xf(mks[i].lng, z) * effTile).toFloat(); mkY[i] = (lat2yf(mks[i].lat, z) * effTile).toFloat() }
    }

    @Synchronized
    private fun draw() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas: Canvas = runCatching { s.lockCanvas(null) }.getOrNull() ?: return
        try {
            val dark = isDark()
            canvas.drawColor(if (dark) 0xFF0E1216.toInt() else 0xFFEAE7DF.toInt())

            val area = visible ?: Rect(0, 0, surfW, surfH)
            val focusX = area.exactCenterX(); val focusY = area.exactCenterY()
            if (centerLat.isNaN()) { drawWaiting(canvas, area, dark); return }

            val z = zoomD.roundToInt().coerceIn(MIN_Z, MAX_Z)
            val n = 1 shl z

            if (follow && !targetLat.isNaN()) {
                val dLat = targetLat - centerLat; val dLon = targetLon - centerLon
                if (kotlin.math.abs(dLat) > spanLat(z) * 2 || kotlin.math.abs(dLon) > spanLon(z) * 2) {
                    centerLat = targetLat; centerLon = targetLon
                } else if (kotlin.math.abs(dLat) > 1e-6 || kotlin.math.abs(dLon) > 1e-6) {
                    centerLat += dLat * POS_EASE; centerLon += dLon * POS_EASE; scheduleAnimFrame()
                } else { centerLat = targetLat; centerLon = targetLon }
            }

            val cxf = lon2xf(centerLon, z); val cyf = lat2yf(centerLat, z)

            val hd = heading
            val headingUp = follow && hd != null

            if (headingUp) {
                val d = (((hd!! - displayRot) % 360f) + 540f) % 360f - 180f
                if (kotlin.math.abs(d) > 0.4f) { displayRot = (displayRot + d * ROT_EASE + 360f) % 360f; scheduleAnimFrame() }
                else displayRot = hd
            } else displayRot = 0f
            val rot = if (headingUp) displayRot else 0f

            val rPx = maxOf(
                kotlin.math.hypot(focusX.toDouble(), focusY.toDouble()),
                kotlin.math.hypot((surfW - focusX).toDouble(), focusY.toDouble()),
                kotlin.math.hypot(focusX.toDouble(), (surfH - focusY).toDouble()),
                kotlin.math.hypot((surfW - focusX).toDouble(), (surfH - focusY).toDouble())
            ).toFloat() + effTile
            val tilesR = kotlin.math.ceil(rPx / effTile).toInt()

            canvas.save()
            if (rot != 0f) canvas.rotate(-rot, focusX, focusY)

            val cTx = floor(cxf).toInt(); val cTy = floor(cyf).toInt()
            val baseLayer = if (dark) "dark_nolabels" else "rastertiles/voyager"
            paintTile.colorFilter = if (dark) baseFilterDark else null
            for (tx in (cTx - tilesR)..(cTx + tilesR)) for (ty in (cTy - tilesR)..(cTy + tilesR)) {
                if (ty < 0 || ty >= n) continue
                val px = focusX + (tx - cxf).toFloat() * effTile; val py = focusY + (ty - cyf).toFloat() * effTile
                drawTile(canvas, baseLayer, z, ((tx % n) + n) % n, ty, px, py, paintTile)
            }
            if (dark) {
                paintLabel.colorFilter = labelFilterDark
                for (tx in (cTx - tilesR)..(cTx + tilesR)) for (ty in (cTy - tilesR)..(cTy + tilesR)) {
                    if (ty < 0 || ty >= n) continue
                    val px = focusX + (tx - cxf).toFloat() * effTile; val py = focusY + (ty - cyf).toFloat() * effTile
                    drawTile(canvas, "dark_only_labels", z, ((tx % n) + n) % n, ty, px, py, paintLabel)
                }
            }

            ensureProjection(z)
            val originX = (cxf * effTile - focusX).toFloat()
            val originY = (cyf * effTile - focusY).toFloat()
            val cullSq = rPx * rPx

            paintDot.color = accentArgb()
            val stride = if (panning && covX.size > 1200) covX.size / 1200 else 1
            var ci = 0
            while (ci < covX.size) {
                val px = covX[ci] - originX; val py = covY[ci] - originY
                val dx = px - focusX; val dy = py - focusY
                if (dx * dx + dy * dy <= cullSq) canvas.drawCircle(px, py, 4.5f, paintDot)
                ci += stride
            }

            for (pass in 0..1) {
                for (i in mkX.indices) {
                    val m = markers[i]
                    val offensive = m.kind == MapMarkerKind.OFFENSIVE
                    if (offensive != (pass == 1)) continue
                    val px = mkX[i] - originX; val py = mkY[i] - originY
                    val dx = px - focusX; val dy = py - focusY
                    if (dx * dx + dy * dy > cullSq) continue
                    val coreR = if (offensive) 13f else 10f
                    paintGlow.color = m.colorArgb; paintGlow.alpha = 70
                    canvas.drawCircle(px, py, coreR * 1.6f, paintGlow)
                    canvas.drawCircle(px, py, coreR * 1.28f, paintDarkRim)
                    paintMarker.color = m.colorArgb
                    canvas.drawCircle(px, py, coreR, paintMarker)
                    iconBmp(m.icon)?.let {

                        val s = coreR * 1.55f
                        if (rot != 0f) {
                            canvas.save(); canvas.rotate(rot, px, py)
                            canvas.drawBitmap(it, null, RectF(px - s / 2f, py - s / 2f, px + s / 2f, py + s / 2f), paintIcon)
                            canvas.restore()
                        } else {
                            canvas.drawBitmap(it, null, RectF(px - s / 2f, py - s / 2f, px + s / 2f, py + s / 2f), paintIcon)
                        }
                    }
                }
            }

            canvas.restore()
            drawMe(canvas, focusX, focusY, accentArgb(), headingUp)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawMe(canvas: Canvas, cx: Float, cy: Float, accent: Int, headingUp: Boolean) {
        paintMe.color = accent
        if (!headingUp) {
            canvas.drawCircle(cx, cy, 9f, paintMe); canvas.drawCircle(cx, cy, 9f, paintRing); return
        }
        val path = android.graphics.Path().apply {
            moveTo(cx, cy - 16f); lineTo(cx - 10f, cy + 11f); lineTo(cx, cy + 5f); lineTo(cx + 10f, cy + 11f); close()
        }
        canvas.drawPath(path, paintMe); canvas.drawPath(path, paintRing)
    }

    private fun drawWaiting(canvas: Canvas, area: Rect, dark: Boolean) {
        paintText.color = if (dark) 0xFFB9C2CC.toInt() else 0xFF3A3A3A.toInt()
        canvas.drawText("Waiting for GPS…", area.exactCenterX(), area.exactCenterY(), paintText)
    }

    private fun tile(layer: String, z: Int, x: Int, y: Int): Bitmap? {
        val key = "$layer/$z/$x/$y"
        tileCache.get(key)?.let { return it }
        if (inflight.add(key)) {
            io.execute {

                var bmp = readDisk(layer, z, x, y)
                if (bmp == null) { bmp = fetch(layer, z, x, y); if (bmp != null) writeDisk(layer, z, x, y, bmp) }
                if (bmp != null) { tileCache.put(key, bmp); ui.post { requestDraw() } }
                inflight.remove(key)
            }
        }
        return null
    }

    private fun drawTile(canvas: Canvas, layer: String, z: Int, x: Int, y: Int, dstL: Float, dstT: Float, paint: Paint) {
        tileDst.set(dstL, dstT, dstL + effTile, dstT + effTile)
        val exact = tile(layer, z, x, y)
        if (exact != null) { canvas.drawBitmap(exact, null, tileDst, paint); return }
        var k = 1
        while (k <= 4 && z - k >= MIN_Z) {
            val pz = z - k; val px = x shr k; val py = y shr k
            val parent = tileCache.get("$layer/$pz/$px/$py")
            if (parent != null) {
                val div = 1 shl k
                val subW = parent.width / div; val subH = parent.height / div
                val sx = (x - (px shl k)) * subW; val sy = (y - (py shl k)) * subH
                srcRect.set(sx, sy, sx + subW, sy + subH)
                canvas.drawBitmap(parent, srcRect, tileDst, paint)
                return
            }
            k++
        }
    }

    private fun diskFile(layer: String, z: Int, x: Int, y: Int) =
        java.io.File(diskDir, "${layer.replace('/', '_')}_${z}_${x}_$y.png")
    private fun readDisk(layer: String, z: Int, x: Int, y: Int): Bitmap? {
        val f = diskFile(layer, z, x, y)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = configFor(layer) }
        return runCatching { BitmapFactory.decodeFile(f.path, opts) }.getOrNull()
    }
    private fun writeDisk(layer: String, z: Int, x: Int, y: Int, bmp: Bitmap) {
        runCatching { diskFile(layer, z, x, y).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun enabledMarkerIcons(): Set<MapMarkerIcon> = com.rocketgod.warble.ui.MapLayerFilter.enabled

    private val iconCache = HashMap<MapMarkerIcon, Bitmap?>()
    private fun iconRes(icon: MapMarkerIcon) = when (icon) {
        MapMarkerIcon.CAMERA -> R.drawable.ic_marker_camera
        MapMarkerIcon.DRONE -> R.drawable.ic_marker_drone
        MapMarkerIcon.BOLT -> R.drawable.ic_marker_bolt
        MapMarkerIcon.TAG -> R.drawable.ic_marker_tag
        MapMarkerIcon.KEY -> R.drawable.ic_marker_key
        MapMarkerIcon.LINK -> R.drawable.ic_marker_link
    }

    private fun iconBmp(icon: MapMarkerIcon): Bitmap? = iconCache.getOrPut(icon) {
        val d = ContextCompat.getDrawable(carContext, iconRes(icon)) ?: return@getOrPut null
        val px = 48
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px); d.draw(Canvas(b)); b
    }

    private fun fetch(layer: String, z: Int, x: Int, y: Int): Bitmap? {
        val sub = "abcd"[((x + y) % 4 + 4) % 4]

        val url = "https://$sub.basemaps.cartocdn.com/$layer/$z/$x/$y@2x.png"
        return runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", carContext.packageName)
            }
            val opts = BitmapFactory.Options().apply { inPreferredConfig = configFor(layer) }
            c.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    private fun lon2xf(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
    private fun lat2yf(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat); return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
    }
    private fun xf2lon(xf: Double, z: Int): Double = xf / (1 shl z) * 360.0 - 180.0
    private fun yf2lat(yf: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI - 2.0 * PI * yf / (1 shl z))))
    private fun spanLat(z: Int): Double = kotlin.math.abs(yf2lat(lat2yf(centerLat, z) + surfH / 2.0 / effTile, z) - centerLat)
    private fun spanLon(z: Int): Double = kotlin.math.abs(xf2lon(lon2xf(centerLon, z) + surfW / 2.0 / effTile, z) - centerLon)

    private val paintTile = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintLabel = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintDarkRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF0E1618.toInt() }
    private val paintIcon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paintMe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.WHITE }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 34f }
    private val tileDst = RectF()
    private val srcRect = Rect()

    companion object {
        private const val TILE = 256
        private const val MIN_Z = 4
        private const val MAX_Z = 20
        private const val ROT_EASE = 0.18f
        private const val POS_EASE = 0.16f
        private const val LIVE_RELOAD_MS = 2500L
        private val LN2 = ln(2.0)
    }
}
