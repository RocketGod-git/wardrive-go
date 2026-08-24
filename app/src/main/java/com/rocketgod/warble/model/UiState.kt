package com.rocketgod.warble.model

enum class FeedTone { ACCENT, GOLD, HOT }
enum class FeedKind { MILESTONE, UNLOCK, BEST, RANK, CAPTURE, SPOTTED }

data class FeedEvent(
    val kind: FeedKind,
    val eyebrow: String,
    val title: String,
    val tone: FeedTone,
    val colorArgb: Long? = null
)

enum class SortMode(val label: String) {
    SIGNAL("Signal"), SEEN("Seen"), RECENT("Recent"), NAME("Name")
}

enum class TypeFilter(val label: String, val type: SignalType?) {
    ALL("All", null), WIFI("WiFi", SignalType.WIFI), BLE("Bluetooth", SignalType.BLE), CELL("Cell", SignalType.CELL)
}
