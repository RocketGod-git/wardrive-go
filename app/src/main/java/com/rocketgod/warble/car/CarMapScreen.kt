package com.rocketgod.warble.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rocketgod.warble.R
import com.rocketgod.warble.core.Repository
import com.rocketgod.warble.model.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CarMapScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = Repository(carContext, scope)
    private val prefs = carContext.getSharedPreferences("warble", Context.MODE_PRIVATE)

    private val renderer = CarMapRenderer(
        carContext = carContext,
        repo = repo,
        accentArgb = { skinAccentArgb() },
        isDark = { prefs.getBoolean("dark_map", false) },
    )

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        com.rocketgod.warble.ui.MapLayerFilter.seed(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        if (hasLocationPermission()) runCatching { repo.location.start(1000L) }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        runCatching { repo.location.stop() }
        renderer.stop()
        runCatching { scope.cancel() }
    }

    override fun onGetTemplate(): Template {

        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder().setIcon(carIcon(R.drawable.ic_car_zoom_in))
                    .setOnClickListener { renderer.zoomIn() }.build())
            .addAction(
                Action.Builder().setIcon(carIcon(R.drawable.ic_car_zoom_out))
                    .setOnClickListener { renderer.zoomOut() }.build())
            .addAction(
                Action.Builder().setIcon(carIcon(R.drawable.ic_car_my_location))
                    .setOnClickListener { renderer.recenter() }.build())
            .build()

        val topStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setIcon(carIcon(R.drawable.ic_car_layers))
                    .setOnClickListener { screenManager.push(CarLayersScreen(carContext)) }.build())
            .addAction(
                Action.Builder().setIcon(carIcon(R.drawable.ic_car_theme))
                    .setOnClickListener {
                        prefs.edit().putBoolean("dark_map", !prefs.getBoolean("dark_map", false)).apply()
                        invalidate()
                    }.build())
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(topStrip)
            .setMapActionStrip(mapStrip)

            .setPanModeListener { }
            .build()
    }

    private fun carIcon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun skinAccentArgb(): Int {
        val name = prefs.getString("skin", Skin.TEAL.name) ?: Skin.TEAL.name
        val skin = runCatching { Skin.valueOf(name) }.getOrDefault(Skin.TEAL)
        return skin.accentHex.toInt()
    }
}
