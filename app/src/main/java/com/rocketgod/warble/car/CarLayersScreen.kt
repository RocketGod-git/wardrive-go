package com.rocketgod.warble.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import com.rocketgod.warble.ui.MapLayerFilter
import com.rocketgod.warble.ui.MapMarkerIcon

class CarLayersScreen(carContext: CarContext) : Screen(carContext) {

    private val rows = listOf(
        MapMarkerIcon.CAMERA to "Cameras (Flock / ALPR / body)",
        MapMarkerIcon.DRONE to "Drones",
        MapMarkerIcon.BOLT to "Attack tools (Flipper / RF)",
        MapMarkerIcon.TAG to "Trackers",
        MapMarkerIcon.KEY to "PMKID captures",
        MapMarkerIcon.LINK to "Handshake captures",
    )

    override fun onGetTemplate(): Template {
        val on = MapLayerFilter.enabled
        val list = ItemList.Builder()
        for ((icon, label) in rows) {
            list.addItem(
                Row.Builder()
                    .setTitle(label)
                    .setToggle(
                        Toggle.Builder { checked ->
                            MapLayerFilter.set(
                                carContext,
                                if (checked) MapLayerFilter.enabled + icon else MapLayerFilter.enabled - icon
                            )
                            invalidate()
                        }.setChecked(icon in on).build()
                    )
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Map layers")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
