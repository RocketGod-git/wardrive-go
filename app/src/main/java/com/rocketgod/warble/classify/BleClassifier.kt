package com.rocketgod.warble.classify

import android.content.Context
import org.json.JSONObject

data class Inference(val name: String?, val maker: String?, val category: String, val icon: String)

class BleClassifier private constructor(
    private val full: Map<Int, String>,
    private val makerCat: Map<String, Pair<String, String>>,
    private val serviceCat: Map<String, Pair<String, String>>,
    private val serviceMaker: Map<String, String>
) {
    fun companyName(id: Int?): String? = id?.let { full[it] }

    private val strongService = mapOf(
        "1826" to ("Gym" to "figure.run"),
        "FD6F" to ("Phone" to "iphone"),
        "FE2C" to ("Earbuds" to "airpodspro"),
        "FEED" to ("Tracker" to "mappin"),
        "FEEC" to ("Tracker" to "mappin"),
        "1812" to ("Keyboard" to "keyboard")
    )

    fun infer(
        name: String?,
        companyId: Int?,
        serviceUuids: List<String>
    ): Inference {
        val svc = serviceUuids.map { it.uppercase() }

        for (u in svc) {
            val short = shorten(u)
            strongService[short]?.let { (cat, icon) ->
                return Inference(name, companyName(companyId), cat, icon)
            }
        }

        val n = name?.trim()
        if (companyId == 0x004C || (n != null && appleName(n))) {
            val cat = when {
                n == null -> "Phone"
                n.contains("watch", true) -> "Watch"
                n.contains("airpod", true) || n.contains("buds", true) -> "Audio"
                n.contains("macbook", true) || n.contains("imac", true) -> "Computer"
                n.contains("ipad", true) -> "Tablet"
                n.contains("tv", true) -> "TV"
                else -> "Phone"
            }
            return Inference(name, "Apple", cat, iconFor(cat))
        }

        if (n != null) nameMatch(n)?.let { return it.copy(name = name) }

        if (companyId != null) {
            val hex = String.format("%04X", companyId)
            makerCat[hex]?.let { (cat, icon) ->
                return Inference(name, companyName(companyId), cat, icon)
            }
        }

        for (u in svc) {
            val short = shorten(u)
            serviceCat[short]?.let { (cat, icon) ->
                return Inference(name, serviceMaker[short] ?: companyName(companyId), cat, icon)
            }
        }
        for (u in svc) {
            val short = shorten(u)
            serviceMaker[short]?.let { mk ->
                return Inference(name, mk, "Accessory", "dot.radiowaves.left.and.right")
            }
        }

        val maker = companyName(companyId)
        return if (maker != null)
            Inference(name, maker, "Accessory", "dot.radiowaves.left.and.right")
        else
            Inference(name, null, "Unidentified", "questionmark")
    }

    private fun shorten(uuid: String): String {

        if (uuid.length >= 8 && uuid.contains("-")) {
            val head = uuid.substringBefore("-")
            if (head.length == 8) return head.substring(4).uppercase()
        }
        return uuid.replace("0X", "").takeLast(4).uppercase()
    }

    private fun appleName(n: String): Boolean {
        val l = n.lowercase()
        return l.startsWith("iphone") || l.startsWith("ipad") || l.startsWith("macbook") ||
            l.contains("airpod") || l.contains("apple watch") || l.startsWith("imac")
    }

    private fun nameMatch(n: String): Inference? {
        val l = n.lowercase()
        val table: List<Triple<List<String>, Pair<String?, String>, String>> = listOf(
            Triple(listOf("govee"), "Govee" to "Smart home", "house.fill"),
            Triple(listOf("tile"), "Tile" to "Tracker", "mappin"),
            Triple(listOf("galaxy", "samsung"), "Samsung" to "Phone", "iphone"),
            Triple(listOf("pixel"), "Google" to "Phone", "iphone"),
            Triple(listOf("bose"), "Bose" to "Audio", "headphones"),
            Triple(listOf("jbl"), "JBL" to "Audio", "headphones"),
            Triple(listOf("mi ", "xiaomi", "redmi"), "Xiaomi" to "Phone", "iphone"),
            Triple(listOf("fitbit"), "Fitbit" to "Watch", "applewatch"),
            Triple(listOf("garmin"), "Garmin" to "Watch", "applewatch"),
            Triple(listOf("tv"), null to "TV", "tv"),
            Triple(listOf("nest"), "Google Nest" to "Smart home", "house.fill"),
            Triple(listOf("printer"), null to "Printer", "printer.fill")
        )
        for ((keys, catPair, icon) in table) {
            if (keys.any { l.contains(it) }) {
                val (maker, cat) = catPair
                return Inference(n, maker, cat, icon)
            }
        }
        return null
    }

    private fun iconFor(cat: String): String = when (cat) {
        "Watch" -> "applewatch"
        "Audio" -> "headphones"
        "Computer" -> "laptopcomputer"
        "Tablet" -> "ipad"
        "TV" -> "tv"
        else -> "iphone"
    }

    companion object {
        @Volatile private var INSTANCE: BleClassifier? = null

        fun get(context: Context): BleClassifier =
            INSTANCE ?: synchronized(this) { INSTANCE ?: load(context).also { INSTANCE = it } }

        private fun load(context: Context): BleClassifier {
            val txt = context.assets.open("ble_vendor_data.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(txt)
            val full = HashMap<Int, String>(4200)
            root.getJSONObject("full").let { o ->
                val it = o.keys()
                while (it.hasNext()) { val k = it.next(); full[k.toInt()] = o.getString(k) }
            }
            fun tupleMap(name: String): Map<String, Pair<String, String>> {
                val o = root.getJSONObject(name); val m = HashMap<String, Pair<String, String>>()
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next(); val arr = o.getJSONArray(k)
                    m[k.uppercase()] = arr.getString(0) to arr.getString(1)
                }
                return m
            }
            val serviceMaker = HashMap<String, String>()
            root.getJSONObject("serviceMaker").let { o ->
                val it = o.keys()
                while (it.hasNext()) { val k = it.next(); serviceMaker[k.uppercase()] = o.getString(k) }
            }
            return BleClassifier(full, tupleMap("makerCat"), tupleMap("serviceCat"), serviceMaker)
        }
    }
}
