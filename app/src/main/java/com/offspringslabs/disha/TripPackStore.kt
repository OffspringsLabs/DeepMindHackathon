package com.offspringslabs.disha

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Region(val id: String, val label: String, val saved: Boolean = false)

/** Bundled demo packs (assets) plus trips the intake agent built (files/trips). A refreshed copy overrides an asset. */
class TripPackStore(private val context: Context) {
    private val bundled = listOf(
        Region("tirupati", "Tirupati & Tirumala, AP"),
        Region("araku", "Araku Valley, AP"),
        Region("hampi", "Hampi, Karnataka"),
    )
    private val tripsDir get() = File(context.filesDir, "trips").apply { mkdirs() }
    private val indexFile get() = File(tripsDir, "index.json")

    val regions: List<Region> get() = savedTrips() + bundled.filter { b -> savedTrips().none { it.id == b.id } }

    fun savedTrips(): List<Region> {
        val idx = runCatching { JSONObject(indexFile.readText()) }.getOrNull() ?: return emptyList()
        return idx.keys().asSequence().map { Region(it, idx.optString(it), saved = true) }.toList().sortedBy { it.label }
    }

    private fun file(id: String) = File(tripsDir, "$id.json")
    fun isRefreshed(id: String) = file(id).exists()

    fun loadRaw(id: String): String {
        val f = file(id)
        if (f.exists()) runCatching { return f.readText() }
        return context.assets.open("packs/$id.json").bufferedReader().use { it.readText() }
    }

    fun load(id: String): TripPack = TripPack.parse(loadRaw(id))

    fun saveTrip(id: String, label: String, json: String) {
        file(id).writeText(json)
        val idx = runCatching { JSONObject(indexFile.readText()) }.getOrNull() ?: JSONObject()
        idx.put(id, label)
        indexFile.writeText(idx.toString())
    }

    fun save(id: String, json: String) = saveTrip(id, regions.firstOrNull { it.id == id }?.label ?: id, json)
}
