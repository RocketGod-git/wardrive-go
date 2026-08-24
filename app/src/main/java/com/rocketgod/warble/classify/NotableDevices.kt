package com.rocketgod.warble.classify

enum class NotableCategory(val banner: String, val noun: String, val colorArgb: Long) {
    SURVEILLANCE("CAM", "camera", 0xFFFF3B3B),
    BODYCAM("BODY CAM", "body cam", 0xFFFF6A00),
    DRONE("DRONE", "drone", 0xFF16E0FF),
    ACTIONCAM("ACTION CAM", "camera", 0xFF37E36B),
    GLASSES("GLASSES", "glasses", 0xFFB56BFF),
    FLIPPER("", "", 0xFFFF7BE0),
    RFTOOL("RF TOOL", "RF tool", 0xFFFF3B3B),
    HACKTOOL("", "", 0xFFFF3B3B),
    TRACKER("TRACKER", "tracker", 0xFFFFD23B),
    TRAFFIC("ALPR CAM", "ALPR cam", 0xFFFFB300),
}

data class NotableHit(val brand: String, val category: NotableCategory) {

    val isNetworkTracker: Boolean get() = brand == "Apple Find My" || brand == "Google Find My"

    val readable: String get() =
        if (category.noun.isBlank() || brand.contains(category.noun, true)) brand else "$brand ${category.noun}"

    val banner: String get() = (listOf(brand.uppercase()) + category.banner.split(" "))
        .filter { it.isNotBlank() }.distinct().joinToString(" ")
}

enum class ThreatLane(val label: String, val colorArgb: Long) {
    ATTACK("Attack tool", 0xFFFF3B3B),
    SURVEILLANCE("Surveillance", 0xFFFFB300),
    TRACKING("Tracker", 0xFFFFD23B),
}

val NotableCategory.lane: ThreatLane get() = when (this) {
    NotableCategory.FLIPPER -> ThreatLane.ATTACK
    NotableCategory.RFTOOL -> ThreatLane.ATTACK
    NotableCategory.HACKTOOL -> ThreatLane.ATTACK
    NotableCategory.TRACKER -> ThreatLane.TRACKING
    else -> ThreatLane.SURVEILLANCE
}

data class ThreatHit(val label: String, val lane: ThreatLane)

object NotableDevices {

    private val OUI: Map<String, NotableHit> = buildMap {
        fun add(cat: NotableCategory, brand: String, vararg ouis: String) =
            ouis.forEach { put(it.lowercase(), NotableHit(brand, cat)) }

        add(NotableCategory.SURVEILLANCE, "Flock", "b4:1e:52")

        add(NotableCategory.BODYCAM, "Axon", "00:25:df")

        add(NotableCategory.TRAFFIC, "Genetec", "00:bf:15", "0c:bf:15")
        add(NotableCategory.TRAFFIC, "Neology", "00:17:3d")
        add(NotableCategory.TRAFFIC, "Sensys Gatso", "00:22:9f")
        add(NotableCategory.TRAFFIC, "Gatso", "00:18:29")
        add(NotableCategory.TRAFFIC, "Jenoptik", "00:04:4c")
        add(NotableCategory.TRAFFIC, "Redflex", "00:30:7e")
        add(NotableCategory.ACTIONCAM, "GoPro",
            "04:41:69", "04:57:47", "24:74:f7", "ac:04:aa", "d4:32:60", "d4:d9:19", "d8:96:85", "f4:dd:9e")
        add(NotableCategory.ACTIONCAM, "Insta360", "f0:55:82")
        add(NotableCategory.DRONE, "DJI",
            "04:a8:5a", "0c:9a:e6", "34:d2:62", "48:1c:b9", "4c:43:f6", "58:b8:58", "60:60:1f", "88:29:85", "8c:58:23", "e4:7a:2c",
            "20:1f:55", "f8:40:68", "34:91:f0", "9c:5a:8a", "ec:72:f7")
        add(NotableCategory.DRONE, "Autel", "18:d7:93")
        add(NotableCategory.DRONE, "Parrot", "00:12:1c", "00:26:7e")
        add(NotableCategory.DRONE, "Skydio", "38:1d:14")
        add(NotableCategory.BODYCAM, "Digital Ally", "00:23:bd")

        add(NotableCategory.FLIPPER, "Flipper Zero", "0c:fa:22", "80:e1:26", "80:e1:27")
    }

    private val ID: Map<Int, NotableHit> = buildMap {
        fun add(cat: NotableCategory, brand: String, vararg ids: Int) =
            ids.forEach { put(it, NotableHit(brand, cat)) }

        add(NotableCategory.SURVEILLANCE, "Flock", 0x09C8)
        add(NotableCategory.GLASSES, "Meta", 0xFD5F, 0xFEB7, 0xFEB8, 0x01AB, 0x058E)
        add(NotableCategory.GLASSES, "Luxottica", 0x0D53)
        add(NotableCategory.TRACKER, "Tile", 0xFEED, 0xFEEC)
        add(NotableCategory.TRACKER, "Samsung SmartTag", 0xFD5A)
        add(NotableCategory.TRACKER, "Chipolo", 0xFE33)
        add(NotableCategory.TRACKER, "Globalstar", 0x0576)

        add(NotableCategory.DRONE, "DJI", 0x08AA)
        add(NotableCategory.ACTIONCAM, "GoPro", 0x02F2, 0xFEA5, 0xFEA6)
        add(NotableCategory.ACTIONCAM, "Insta360", 0x10D7, 0xFC30)

        add(NotableCategory.FLIPPER, "Flipper Zero", 0x0E29, 0x3081, 0x3082, 0x3083)
    }

    val notableBrands: Set<String> by lazy {
        (OUI.values.map { it.brand } + ID.values.map { it.brand } +
            listOf("PandwaRF", "USBKill", "Pwnagotchi")).toSet()
    }

    fun isNotableMaker(maker: String?): Boolean {
        val m = maker ?: return false
        return notableBrands.any { m == it || m.startsWith("$it (") }
    }

    private val FLOCK_PROBE_OUIS: Set<String> = setOf(
        "b4:1e:52",
        "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "b8:35:32", "14:5a:fc", "74:4c:a1", "08:3a:88",
        "9c:2f:9d", "c0:35:32", "94:08:53", "e4:aa:ea", "f4:6a:dd", "f8:a2:d6", "24:b2:b9", "00:f4:8d",
        "d0:39:57", "e8:d0:fc", "e0:4f:43", "b8:1e:a4", "70:08:94", "58:8e:81", "ec:1b:bd", "3c:71:bf",
        "58:00:e3", "90:35:ea", "5c:93:a2", "64:6e:69", "48:27:ea", "a4:cf:12", "82:6b:f2",
    )

    fun isFlockProbeOui(mac: String): Boolean =
        mac.length >= 8 && mac.substring(0, 8).lowercase() in FLOCK_PROBE_OUIS

    fun isFlockWildcardProbe(txMac: String, probeSsid: String?): Boolean =
        probeSsid != null && probeSsid.isEmpty() && isFlockProbeOui(txMac)

    fun match(name: String?, key: String?, companyId: Int?, serviceUuids: List<String>,
              appleType: Int? = null, fmdn: Boolean = false, flockProbe: Boolean = false): NotableHit? {

        if (flockProbe) return NotableHit("Flock", NotableCategory.SURVEILLANCE)

        if (name != null) {
            val l = name.lowercase()
            val flockName = (l.startsWith("flock") && (l.length == 5 || !l[5].isLetter())) ||
                (l.startsWith("penguin") && (l.length == 7 || !l[7].isLetter())) ||
                l.contains("pigvision") || l.contains("fs ext battery")
            if (flockName) return NotableHit("Flock", NotableCategory.SURVEILLANCE)

            if (l.contains("pandwarf") || l.contains("rogue pro")) return NotableHit("PandwaRF", NotableCategory.RFTOOL)

            if (l.contains("usbkill") || l.contains("usb killer")) return NotableHit("USBKill", NotableCategory.HACKTOOL)

            if (l.contains("pwnagotchi")) return NotableHit("Pwnagotchi", NotableCategory.HACKTOOL)

            if (l.startsWith("quest") || l.contains("meta quest")) return null
        }
        if (key != null && key.length >= 8) OUI[key.substring(0, 8).lowercase()]?.let { return it }
        if (companyId != null) ID[companyId]?.let { return it }
        for (u in serviceUuids) shortUuid(u)?.let { v -> ID[v]?.let { return it } }

        if (appleType == 0x12) return NotableHit("Apple Find My", NotableCategory.TRACKER)
        if (fmdn) return NotableHit("Google Find My", NotableCategory.TRACKER)
        return null
    }

    fun flipperColor(serviceUuids: List<String>): String? {
        for (u in serviceUuids) when (shortUuid(u)) {
            0x3081 -> return "Black"
            0x3082 -> return "White"
            0x3083 -> return "Transparent"
        }
        return null
    }

    private fun shortUuid(u: String): Int? {
        val s = u.trim().lowercase()
        return when {
            s.endsWith("-0000-1000-8000-00805f9b34fb") && s.length >= 8 -> s.substring(4, 8).toIntOrNull(16)
            s.length <= 6 -> s.removePrefix("0x").toIntOrNull(16)
            else -> null
        }
    }

    fun threat(name: String?, key: String?, companyId: Int?, stampedCategory: String?): ThreatHit? {

        if (name != null) {
            val l = name.lowercase()

            if (l.startsWith("quest") || l.contains("meta quest")) return null
            when {
                l.contains("pineapple") -> return ThreatHit("Wi-Fi Pineapple", ThreatLane.ATTACK)
                l.contains("deauth") || l == "pwned" -> return ThreatHit("Deauther / rogue AP", ThreatLane.ATTACK)
            }
        }

        match(name, key, companyId, emptyList())?.let { return ThreatHit(it.readable, it.category.lane) }

        if (stampedCategory != null) {
            val c = stampedCategory.lowercase()
            if (c.contains("flipper")) return ThreatHit(stampedCategory, ThreatLane.ATTACK)

            if (c.contains("flock") || c.contains("penguin") || c.contains("pigvision"))
                return ThreatHit(NotableHit("Flock", NotableCategory.SURVEILLANCE).readable, ThreatLane.SURVEILLANCE)
            for (cat in NotableCategory.values()) {
                if (cat.noun.isNotBlank() && c.contains(cat.noun.lowercase())) return ThreatHit(stampedCategory, cat.lane)
            }
        }
        return null
    }

    fun displayName(name: String?, key: String?, companyId: Int?, category: String?, maker: String?): String? {
        val t = threat(name, key, companyId, category) ?: return name?.ifBlank { null }
        val label = t.label

        val m = maker?.ifBlank { null }
        return if (m != null && m.contains(label, true) && m.length >= label.length) m else label
    }
}
