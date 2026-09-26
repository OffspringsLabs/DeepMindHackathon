package com.offspringslabs.disha

/**
 * Precompiled UI components the agent can surface. The model never draws UI; it picks a tool,
 * the tool returns facts + one of these typed cards, and the screen renders the matching component.
 */
sealed class UiCard {
    data class Route(
        val from: String, val to: String, val km: Double, val minutes: Int, val mode: String, val estimated: Boolean,
    ) : UiCard()

    data class OpenStatus(
        val name: String, val isOpen: Boolean, val atLabel: String, val hours: String, val untilLabel: String, val type: String,
    ) : UiCard()

    data class Budget(
        val title: String, val lines: List<Pair<String, Int>>, val total: Int, val note: String,
    ) : UiCard()

    data class Plan(
        val day: Int, val stops: List<Stop>, val nextIndex: Int, val nowLabel: String,
    ) : UiCard()

    data class Emergency(
        val hospital: String, val police: String, val atm: String, val noSignal: String, val arrival: List<Pair<String, String>>,
    ) : UiCard()

    data class PlaceInfo(
        val name: String, val type: String, val hours: String?, val distance: String, val price: String?, val tip: String, val lat: Double?, val lon: Double?,
    ) : UiCard()

    data class Inventory(
        val places: List<Pair<String, String>>, val hotels: List<Pair<String, String>>, val food: List<Pair<String, String>>,
    ) : UiCard()
}
