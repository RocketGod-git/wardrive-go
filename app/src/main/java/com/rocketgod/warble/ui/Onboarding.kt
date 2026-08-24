package com.rocketgod.warble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
fun Onboarding(
    onRequestLocation: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onSetupExclusion: () -> Unit = {},
    onFinish: (wardrive: Boolean) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var wardrive by remember { mutableStateOf(true) }
    val accent = Palette.glow
    val last = 6

    Column(
        Modifier.fillMaxSize().background(Palette.paper).padding(24.dp)
    ) {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            for (i in 0..last) {
                Box(
                    Modifier.padding(3.dp).size(if (i == step) 10.dp else 7.dp)
                        .background(if (i <= step) accent else Palette.line, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
        when (step) {
            0 -> StepCard(Icons.Filled.Bluetooth, accent, "Welcome to Wardrive Go",
                "I'm trying to make a badass app but your input will make it better.\n\nUse the ? icon on the main screen to find me if you don't know me yet. I'll be working on this until we get it right. Drive safe! -RocketGod 😎🤘",
                iconRes = com.rocketgod.warble.R.drawable.app_logo)
            1 -> StepCard(Icons.Filled.LocationOn, accent, "Location",
                "Wardrive Go collects your device location to map (geotag) the Bluetooth, WiFi and cell signals it detects — and Android also requires location permission to scan radios at all. Your location and your finds stay on this device unless you choose to export them.",
                "Grant location", onRequestLocation)
            2 -> StepCard(Icons.Filled.Bluetooth, accent, "Bluetooth",
                "Nearby-devices permission lets the radar pick up BLE advertisements. Nothing connects — Wardrive Go only listens.",
                "Grant Bluetooth", onRequestBluetooth)
            3 -> StepCard(Icons.Filled.Wifi, accent, "WiFi scan throttling",
                "Android limits background WiFi scans to a few per two minutes. Turn off \"Wi-Fi scan throttling\" in Developer Options for a live picture. Optional — the radar works either way.",
                "Open developer settings", onOpenDeveloperSettings)
            4 -> StepCard(Icons.Filled.LocationOn, accent, "Mapping your finds",
                "Every contact gets stamped with your GPS location so you can see it on the map. From the field report you can upload straight to WiGLE and to WDGWars (wdgwars.pl) — new networks only, no duplicates — and climb each service's live leaderboard right inside the app. Nothing leaves your device until you upload or export it yourself.")
            5 -> StepCard(Icons.Filled.Usb, accent, "External Wi-Fi Capabilities",
                "Here's the big one: run an external Wi-Fi antenna. Plug a supported USB adapter into the phone over OTG and Wardrive Go runs it in parallel with the phone's own radios — no root — merging into the same radar and field report.\n\nMONITOR-MODE — true promiscuous 802.11: access points, the hidden client stations your phone can't see, plus passive WPA hash capture:\n• Atheros AR9271 — Alfa AWUS036NHA, TP-Link TL-WN722N v1 (2.4 GHz)\n• Ralink RT3070/RT5370 — Superwang, TurboTenna Yagi (2.4 GHz)\n• MediaTek MT7612U — Alfa AWUS036ACM, Panda PAU0D AC1200 (2.4 + 5 GHz)\n• Realtek RTL8821AU — Alfa AWUS036ACS (2.4 + 5 GHz)\n• Realtek RTL8814AU — Alfa AWUS1900 (4-antenna, 2.4 + 5 GHz)\n\nSERIAL SCANNERS — access-point discovery; firmware auto-detected over native-USB or CP210x, dual-band on ESP32-C5:\n• ESP32 Marauder — JustCallMeKoko wardriver\n• Ghost ESP / GhostESP Revival, Rabbit-Labs Poltergeist\n• Cerberus\n\nA bigger screw-on antenna reaches even further.")
            6 -> StepCard(Icons.Filled.Shield, accent, "Keep your home private",
                "One thing to know before you upload: every network you log is public on WiGLE by location — including your own home Wi-Fi, which would pin your address.\n\nAn exclusion zone fixes that: drop a pin on your home and set a radius, and nothing inside it is ever uploaded (it still shows on your radar). You can also blacklist a single device from its detail screen — handy for things that travel with you, like your phone's hotspot or car — so it's never uploaded anywhere.\n\nSet one up now, or skip and do it anytime from Settings › Privacy & uploads.",
                "Set up an exclusion zone", onSetupExclusion)
        }
        }

        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().glassAction(accent)
                .clickable { if (step < last) step++ else onFinish(wardrive) }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(if (step < last) "Continue" else "Enter the radar",
                color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        if (step in 1..3) {
            Spacer(Modifier.height(10.dp))
            Text("Skip for now", color = Palette.muted, fontFamily = Mono, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { step++ }.padding(8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun StepCard(icon: ImageVector, accent: Color, title: String, body: String, action: String? = null, onAction: () -> Unit = {}, iconRes: Int? = null) {
    Column {
        if (iconRes != null) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))
            )
        } else {
            Icon(icon, null, tint = accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.border(1.dp, accent, RoundedCornerShape(12.dp))
                    .clickable { onAction() }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Settings, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(action, color = Palette.ink, fontFamily = Mono, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun WardriveCard(accent: Color, on: Boolean, onToggle: (Boolean) -> Unit) {
    Column {
        Icon(Icons.Filled.LocationOn, null, tint = accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text("Wardrive mode", color = Palette.ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Spacer(Modifier.height(12.dp))
        Text("Off by default. When on, Wardrive Go stamps each contact with GPS so you can export a WiGLE-ready CSV. Turn it on only when you mean to map.",
            color = Palette.muted, fontFamily = Mono, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().border(1.dp, Palette.line, RoundedCornerShape(12.dp))
                .background(Palette.surface, RoundedCornerShape(12.dp))
                .clickable { onToggle(!on) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (on) "Wardrive: ON" else "Wardrive: OFF",
                color = if (on) accent else Palette.muted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            Box(
                Modifier.width(46.dp).height(26.dp)
                    .background(if (on) accent else Palette.line, RoundedCornerShape(13.dp))
                    .padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
            ) { Box(Modifier.size(20.dp).background(Palette.paper, CircleShape)) }
        }
    }
}
