package com.rocketgod.warble.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class Adapter(
    val name: String,
    val vid: Int,
    val pid: Int,
    val chipsetName: String,
    val isAr9271: Boolean,
    val supported: Boolean
) {
    val idHex: String get() = String.format("%04x:%04x", vid, pid)
}

sealed interface BeastState {

    data object Internal : BeastState

    data class Awaiting(val adapter: Adapter) : BeastState

    data class Engaged(val adapter: Adapter) : BeastState
}

object UsbBeast {
    const val ACTION_PERMISSION = "com.rocketgod.wardrive.USB_PERMISSION"

    enum class Chipset { AR9271, RTL8187, RTL8821AU, RTL8814AU, MT7612U, RT3070, KOKO, GHOST, FREEWILI, OTHER }

    fun chipset(d: UsbDevice): Chipset = when {
        d.vendorId == 0x0cf3 && (d.productId == 0x9271 || d.productId == 0x1006) -> Chipset.AR9271
        d.vendorId == 0x0bda && d.productId == 0x8187 -> Chipset.RTL8187

        d.vendorId == 0x0bda && d.productId == 0x0811 -> Chipset.RTL8821AU

        d.vendorId == 0x0bda && d.productId == 0x8813 -> Chipset.RTL8814AU
        d.vendorId == 0x0e8d && (d.productId == 0x7612 || d.productId == 0x7610) -> Chipset.MT7612U

        d.vendorId == 0x148f && (d.productId == 0x3070 || d.productId == 0x5370 || d.productId == 0x5372) -> Chipset.RT3070

        d.vendorId == 0x10c4 && d.productId == 0xea60 -> Chipset.KOKO

        d.vendorId == 0x303a && d.productId == 0x1001 -> Chipset.GHOST

        d.vendorId == 0x093c && d.productId == 0x205a -> Chipset.FREEWILI
        else -> Chipset.OTHER
    }

    private val SUPPORTED: Map<Int, Set<Int>> = mapOf(
        0x0cf3 to setOf(0x9271, 0x1006),
        0x0bda to setOf(0x8187, 0x0811, 0x8812, 0x881a, 0x8813),
        0x148f to setOf(0x5370, 0x3070, 0x5372),
        0x0e8d to setOf(0x7612, 0x7610),
        0x10c4 to setOf(0xea60),
        0x303a to setOf(0x1001),
        0x093c to setOf(0x205a)
    )

    fun isSupported(d: UsbDevice): Boolean = SUPPORTED[d.vendorId]?.contains(d.productId) == true

    @Volatile var lastFrameMs = 0L

    @Volatile var campBusy = true
    @Volatile var activeChannels: Set<Int> = emptySet()

    @Volatile var lockChannel: Int? = null
    @Volatile var dwellMs: Int = 0

    @Volatile var seenWindowMs: Long = 0

    @Volatile var defaultDwellMs: Int = 150

    @Volatile var currentAdapterKey: String? = null
    @Volatile var settingsPersister: ((dwell: Int, lock: Int) -> Unit)? = null

    fun setDwell(v: Int) { dwellMs = v; settingsPersister?.invoke(dwellMs, lockChannel ?: -1) }

    fun setLock(ch: Int?) { lockChannel = ch; settingsPersister?.invoke(dwellMs, lockChannel ?: -1) }

    fun dwell(default: Long): Long = dwellMs.toLong().takeIf { it > 0 } ?: default

    fun effectiveSweepMs(hop: IntArray, defaultDwell: Long): Long {
        if (hop.isEmpty()) return defaultDwell
        val d = dwell(defaultDwell)
        val visited = when {
            lockChannel?.let { hop.contains(it) } == true -> 1
            campBusy && activeChannels.isNotEmpty() -> hop.count { it in activeChannels }.coerceAtLeast(1)
            else -> hop.size
        }
        return visited * d
    }

    fun nextHop(cur: Int, hop: IntArray): Int {
        if (hop.isEmpty()) return cur
        lockChannel?.let { lc -> val i = hop.indexOf(lc); if (i >= 0) return i }
        if (!campBusy || activeChannels.isEmpty()) return (cur + 1) % hop.size
        for (step in 1..hop.size) {
            val i = (cur + step) % hop.size
            if (hop[i] in activeChannels) return i
        }
        return (cur + 1) % hop.size
    }

    fun lockableChannels(dualBand: Boolean): List<Int> {
        val g24 = (1..13).toList()
        val g5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
        return if (dualBand) g24 + g5 else g24
    }

    fun manager(ctx: Context): UsbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun adapterPriority(d: UsbDevice): Int = when (chipset(d)) {
        Chipset.MT7612U -> 100
        Chipset.AR9271 -> 90
        Chipset.RTL8814AU -> 80
        Chipset.RT3070 -> 70
        Chipset.GHOST -> 60
        Chipset.KOKO -> 55
        Chipset.FREEWILI -> 50
        Chipset.RTL8187 -> 40
        Chipset.RTL8821AU -> 10
        else -> -1
    }

    fun findAdapter(ctx: Context): UsbDevice? = runCatching {
        manager(ctx).deviceList.values.filter { isSupported(it) }
            .maxWithOrNull(compareBy({ adapterPriority(it) }, { it.deviceId }))
    }.getOrNull()

    fun hasPermission(ctx: Context, d: UsbDevice): Boolean =
        runCatching { manager(ctx).hasPermission(d) }.getOrDefault(false)

    fun scanLog(ctx: Context): String {
        val list = runCatching { manager(ctx).deviceList.values.toList() }.getOrElse { emptyList() }
        if (list.isEmpty()) return "USB scan: no devices enumerated (check OTG cable / adapter power)"
        return "USB scan: " + list.joinToString("; ") { d ->
            val cls = d.deviceClass
            val tag = when {
                isSupported(d) -> "SUPPORTED[${chipsetName(d)}]"
                cls == 8 -> "MASS-STORAGE(needs mode-switch?)"
                else -> "unrecognized cls=%02x".format(cls)
            }
            "%04x:%04x %s".format(d.vendorId, d.productId, tag)
        }
    }

    fun requestPermission(ctx: Context, d: UsbDevice) {
        val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_MUTABLE)
        runCatching { manager(ctx).requestPermission(d, pi) }
    }

    fun label(d: UsbDevice): String = d.productName ?: d.deviceName ?: "USB adapter"

    fun chipsetName(d: UsbDevice): String = when {
        d.vendorId == 0x0cf3 && (d.productId == 0x9271 || d.productId == 0x1006) -> "Atheros AR9271 · ath9k_htc"
        d.vendorId == 0x0bda && d.productId == 0x8187 -> "Realtek RTL8187"
        d.vendorId == 0x0bda && d.productId == 0x0811 -> "Realtek RTL8821AU · 88xxau"
        d.vendorId == 0x0bda && d.productId == 0x8813 -> "Realtek RTL8814AU · AWUS1900"
        d.vendorId == 0x0bda && (d.productId == 0x8812 || d.productId == 0x881a) -> "Realtek RTL8812AU"
        d.vendorId == 0x148f && d.productId == 0x5370 -> "Ralink RT5370"
        d.vendorId == 0x148f && d.productId == 0x3070 -> "Ralink RT3070"
        d.vendorId == 0x148f && d.productId == 0x5372 -> "Ralink RT5372"
        d.vendorId == 0x0e8d && d.productId == 0x7612 -> "MediaTek MT7612U · mt76x2u"
        d.vendorId == 0x0e8d && d.productId == 0x7610 -> "MediaTek MT7610U · mt76x0u"

        d.vendorId == 0x10c4 && d.productId == 0xea60 -> "USB serial · CP210x"

        d.vendorId == 0x303a && d.productId == 0x1001 -> "USB serial · ESP32 native"
        d.vendorId == 0x093c && d.productId == 0x205a -> "FREE-WILi 2 console · ESP32-C5 Wi-Fi"
        else -> "Unknown chipset"
    }

    fun describe(d: UsbDevice): Adapter =
        Adapter(label(d), d.vendorId, d.productId, chipsetName(d), chipset(d) == Chipset.AR9271, isSupported(d))
}
