package com.rocketgod.warble.model

enum class SignalType(val wigle: String, val label: String) {
    BLE("BLE", "Bluetooth"),
    WIFI("WIFI", "WiFi"),
    CELL("CELL", "Cell")
}

enum class Proximity { HOT, WARM, COLD }

fun wifiStandardLabel(standard: Int, frequencyMhz: Int?): String? = when (standard) {
    1 -> "Wi-Fi (legacy)"
    4 -> "Wi-Fi 4"
    5 -> "Wi-Fi 5"
    6 -> if (frequencyMhz != null && frequencyMhz in 5955..7125) "Wi-Fi 6E" else "Wi-Fi 6"
    7 -> "WiGig (60 GHz)"
    8 -> "Wi-Fi 7"
    else -> null
}

data class Contact(
    val key: String,
    val type: SignalType,
    val name: String?,
    val maker: String?,
    val category: String,
    val icon: String,
    val rssi: Int,

    val smoothRssi: Double = rssi.toDouble(),
    val bestRssi: Int,
    val timesSeen: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val companyId: Int?,
    val serviceUuids: List<String> = emptyList(),
    val channel: Int?,
    val frequency: Int?,
    val capabilities: String?,
    val connectable: Boolean,
    val lat: Double?,
    val lng: Double?,
    val altitude: Double?,
    val accuracy: Double?,
    val newThisRun: Boolean,
    val inWigle: Boolean,
    val viaMonitor: Boolean = false,

    val seenByPhone: Boolean = false,
    val seenByMonitor: Boolean = false,

    val lastSeenByMonitor: Long = 0L,
    val wifiStandard: Int = 0,

    val channelWidthMhz: Int = 0,

    val centerFreqMhz: Int = 0,

    val distanceMm: Int? = null,

    val liveByPhone: Boolean = false,
    val liveByMonitor: Boolean = false
) {

    val strength: Float
        get() {
            val (lo, hi) = when (type) {
                SignalType.BLE -> -95.0 to -50.0
                SignalType.WIFI -> -90.0 to -35.0
                SignalType.CELL -> -120.0 to -60.0
            }
            val clamped = smoothRssi.coerceIn(lo, hi)
            return ((clamped - lo) / (hi - lo)).toFloat()
        }

    val proximity: Proximity
        get() = when {
            strength > 0.66f -> Proximity.HOT
            strength > 0.40f -> Proximity.WARM
            else -> Proximity.COLD
        }

    val identified: Boolean get() = !name.isNullOrBlank() || category != UNKNOWN_CATEGORY

    val angle: Float
        get() {
            var h = 1125899906842597L
            for (c in key) h = 31 * h + c.code
            val a = ((h % 3600) + 3600) % 3600
            return a / 10f
        }

    companion object { const val UNKNOWN_CATEGORY = "Unidentified" }
}

enum class RadarPaint { ACCENT, RAINBOW, SIGNAL }

enum class Skin(
    val display: String,
    val accentHex: Long,
    val deepHex: Long,
    val paperHex: Long? = null,
    val surfaceHex: Long? = null,
    val panelHex: Long? = null,
    val inkHex: Long? = null,
    val lineHex: Long? = null,
    val mutedHex: Long? = null,
    val goldHex: Long? = null,
    val wifiHex: Long? = null,
    val bleHex: Long? = null,
    val cellHex: Long? = null,
    val radarPaint: RadarPaint = RadarPaint.ACCENT,
) {
    TEAL("Teal", 0xFF37ECCB, 0xFF1F7E70),
    AMBER("Amber", 0xFFE0A53C, 0xFF8A5A1C),
    SONAR_BLUE("Sonar Blue", 0xFF2E9DFF, 0xFF1C5C9E),
    CRIMSON("Crimson", 0xFFFF3B57, 0xFFA12438),
    TOXIC("Toxic", 0xFF5CF55C, 0xFF2C8A2C),
    PLASMA("Plasma", 0xFFB266FF, 0xFF6B3FB0),

    WIGLE(
        "WiGLE", 0xFFFFFFFF, 0xFF9A9A9A,
        paperHex = 0xFF0D0D0D, surfaceHex = 0xFF1A1A1A, panelHex = 0xFF262626,
        inkHex = 0xFFECECEC, lineHex = 0xFF383838, mutedHex = 0xFF9A9A9A, goldHex = 0xFFFFC400,
        wifiHex = 0xFF76FF03, bleHex = 0xFF2196F3, cellHex = 0xFFFF6B6B,
        radarPaint = RadarPaint.SIGNAL
    ),

    WIGHOUL(
        "WiGhoul", 0xFFFF9900, 0xFFB36B00,
        paperHex = 0xFF0D0D0D, surfaceHex = 0xFF1A1A1A, panelHex = 0xFF262626,
        inkHex = 0xFFECECEC, lineHex = 0xFF383838, mutedHex = 0xFF9A9A9A, goldHex = 0xFFFFC400,
        wifiHex = 0xFF76FF03, bleHex = 0xFF2196F3, cellHex = 0xFFFF6B6B,
        radarPaint = RadarPaint.SIGNAL
    ),

    BBS(
        "BBS", 0xFF00E5E5, 0xFF008B8B,
        paperHex = 0xFF000000, surfaceHex = 0xFF0A0A1E, panelHex = 0xFF12123A,
        inkHex = 0xFFE0E0E0, lineHex = 0xFF2A2A5A, mutedHex = 0xFF8A8AB0, goldHex = 0xFFFFF000
    ),

    PRO(
        "Pro", 0xFF8AA0FF, 0xFF4A5CC0,
        paperHex = 0xFF08080C, surfaceHex = 0xFF121218, panelHex = 0xFF1A1A24,
        inkHex = 0xFFF0F0F5, lineHex = 0xFF33333F, mutedHex = 0xFF9090A0, goldHex = 0xFFFFD24A,
        radarPaint = RadarPaint.RAINBOW
    ),

    SLATE("Slate", 0xFF93A0A0, 0xFF47504F),
    CLARITY("Clarity", 0xFFFFFFFF, 0xFF9AA4A0),
}

data class TypeStat(
    val type: SignalType,
    val totalObservations: Long,
    val unique: Long,
    val newThisRun: Long,
    val inWigle: Long
)

data class Stats(
    val thisRun: Long,
    val lifetime: Long,
    val bestRun: Long,
    val runs: Long,
    val unidentified: Long,
    val smartHome: Long,
    val nearby: Int,
    val perType: List<TypeStat>,

    val wifiLifetime: Long = 0,
    val wifiThisRun: Long = 0,
    val wifiBestRun: Long = 0
)
