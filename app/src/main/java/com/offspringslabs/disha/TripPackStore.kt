package com.offspringslabs.disha

import android.content.Context
import java.io.File

data class Region(val id: String, val label: String)

/** Packs live as JSON assets (pre-generated) and can be overridden by a refreshed copy in app storage. */
class TripPackStore(private val context: Context) {
    val regions: List<Region> = listOf(
        Region("tirupati", "Tirupati & Tirumala, AP"),
        Region("araku", "Araku Valley, AP"),
        Region("hampi", "Hampi, Karnataka"),
    )

    private fun file(id: String) = File(context.filesDir, "packs/$id.json")

    fun isRefreshed(id: String) = file(id).exists()

    fun loadRaw(id: String): String {
        val f = file(id)
        if (f.exists()) runCatching { return f.readText() }
        return context.assets.open("packs/$id.json").bufferedReader().use { it.readText() }
    }

    fun load(id: String): TripPack = TripPack.parse(loadRaw(id))

    fun save(id: String, json: String) {
        file(id).apply { parentFile?.mkdirs(); writeText(json) }
    }
}
