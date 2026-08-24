package com.rocketgod.warble.usb

object MonitorLive {

    const val FRESH_MS = 10_000L

    fun status(chip: String, ap: Int, client: Int, channel: Int): String =
        "$chip · live · $ap AP · $client client · ch $channel"

    fun push(onStatus: (String) -> Unit, chip: String, ap: Int, client: Int, channel: Int) {
        onStatus(status(chip, ap, client, channel))
    }

    fun statusApOnly(chip: String, ap: Int, channel: Int): String = "$chip · live · $ap AP · ch $channel"

    fun pushApOnly(onStatus: (String) -> Unit, chip: String, ap: Int, channel: Int) {
        onStatus(statusApOnly(chip, ap, channel))
    }

    fun <T> countFresh(items: Collection<T>, now: Long, isAp: (T) -> Boolean, lastSeen: (T) -> Long): Pair<Int, Int> {
        var ap = 0; var client = 0
        for (it in items) {
            if (now - lastSeen(it) >= FRESH_MS) continue
            if (isAp(it)) ap++ else client++
        }
        return ap to client
    }
}
