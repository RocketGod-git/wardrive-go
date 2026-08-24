package com.rocketgod.warble.usb

import java.util.Collections

object BeastDiag {
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile private var appVer = "?"

    fun begin(versionName: String?, versionCode: Long) {
        synchronized(lines) { lines.clear() }
        appVer = "${versionName ?: "?"}${if (versionCode >= 0) " (vc $versionCode)" else ""}"
    }

    private fun header(): String =
        "=== Wardrive Go · diagnostic logs ===\n" +
            "app: $appVer\n" +
            "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE}/${android.os.Build.PRODUCT})\n" +
            "android: ${android.os.Build.VERSION.RELEASE} · SDK ${android.os.Build.VERSION.SDK_INT}\n" +
            "hardware: ${android.os.Build.HARDWARE} · board ${android.os.Build.BOARD} · ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}\n" +
            "fingerprint: ${android.os.Build.FINGERPRINT}"

    fun clear() = lines.clear()

    @Volatile var quiet = false

    private const val CAP = 12_000
    private const val KEEP_HEAD = 1_500
    private const val EVICT_BATCH = 2_000

    fun log(s: String) {
        if (quiet && (s.startsWith("REG_READ") || s.startsWith("REG_WRITE"))) {
            android.util.Log.i("WardriveBeast", s); return
        }
        android.util.Log.i("WardriveBeast", s)
        synchronized(lines) {

            if (lines.size >= CAP) {
                var evicted = 0
                while (evicted < EVICT_BATCH && lines.size > KEEP_HEAD) { lines.removeAt(KEEP_HEAD); evicted++ }
                if (evicted > 0) lines.add(KEEP_HEAD,
                    "… ($evicted older lines evicted — full log in logcat tag WardriveBeast) …")
            }
            lines.add(s)
        }
    }

    fun hex(b: ByteArray?, max: Int = 128): String =
        if (b == null) "(none)" else b.take(max).joinToString(" ") { String.format("%02x", it) } +
            if (b.size > max) " …(${b.size}B)" else ""

    fun dump(): String {
        val snap: List<String> = synchronized(lines) { ArrayList(lines) }

        return header() + if (snap.isEmpty()) "" else "\n" + snap.joinToString("\n")
    }

    fun dumpForClipboard(maxChars: Int = 120_000): String {
        val full = dump()
        if (full.length <= maxChars) return full
        val snap: List<String> = synchronized(lines) { ArrayList(lines) }
        val head = snap.take(60)
        val tail = snap.takeLast(220)
        val omitted = snap.size - head.size - tail.size
        return header() + " (trimmed for clipboard)\n" +
            head.joinToString("\n") +
            "\n… [$omitted lines omitted from the middle — full log in logcat tag WardriveBeast] …\n" +
            tail.joinToString("\n")
    }
}
