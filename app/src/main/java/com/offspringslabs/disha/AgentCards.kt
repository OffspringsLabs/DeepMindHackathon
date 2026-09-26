package com.offspringslabs.disha

/**
 * Precompiled UI components the agent can surface. The model never draws UI; it picks a tool,
 * the tool returns facts + one of these typed cards, and the screen renders the matching component.
 * Ask* cards are interactive: the intake agent summons them and suspends until the traveller answers.
 */
sealed class UiCard {
    // ---- fact cards (tool outputs)
    data class Route(val from: String, val to: String, val km: Double, val minutes: Int, val mode: String, val estimated: Boolean) : UiCard()
    data class OpenStatus(val name: String, val isOpen: Boolean, val atLabel: String, val hours: String, val untilLabel: String, val type: String) : UiCard()
    data class Budget(val title: String, val lines: List<Pair<String, Int>>, val total: Int, val note: String, val limit: Int = 0, val perPerson: Int = 0) : UiCard()
    data class Plan(val day: Int, val stops: List<Stop>, val nextIndex: Int, val nowLabel: String, val theme: String = "", val costs: List<Int> = emptyList(), val legs: List<String> = emptyList()) : UiCard()
    data class Emergency(val hospital: String, val police: String, val atm: String, val noSignal: String, val arrival: List<Pair<String, String>>, val numbers: List<Phone> = emptyList(), val phrase: Phrase? = null, val pharmacy: String = "") : UiCard()
    data class PlaceInfo(val name: String, val type: String, val hours: String?, val distance: String, val price: String?, val tip: String, val lat: Double?, val lon: Double?, val mapsUri: String = "") : UiCard()
    data class Inventory(val places: List<Pair<String, String>>, val hotels: List<Pair<String, String>>, val food: List<Pair<String, String>>) : UiCard()

    // ---- v2 local-reality cards
    data class PhraseCard(val intent: String, val english: String, val native: String, val roman: String, val language: String) : UiCard()
    data class FareCard(val from: String, val to: String, val mode: String, val fairInr: Int, val touristQuoteInr: Int, val rule: String) : UiCard()
    data class Checklist(val title: String, val items: List<Pair<String, String>>, val accent: String = "info") : UiCard()   // (label, detail)
    data class Alert(val title: String, val items: List<Pair<String, String>>) : UiCard()                                        // (pattern, counter)
    data class FoodList(val title: String, val items: List<Food>) : UiCard()
    data class Tradeoff(val title: String, val savesInr: Int, val freesMin: Int, val detail: String, val alternatives: List<Alternative>) : UiCard()
    data class MapCard(val base: String, val baseLat: Double, val baseLon: Double, val points: List<MapPoint>, val routeDays: List<List<Int>>, val deadZones: List<String>, val mapsAreaUrl: String) : UiCard()
    data class Decision(val recommendation: String, val why: List<String>, val alternative: String, val delta: String) : UiCard()

    // ---- interactive intake cards (client-side tools)
    data class AskDestination(val prompt: String, val suggestions: List<String>) : UiCard()
    data class AskDays(val prompt: String, val min: Int, val max: Int, val suggested: Int, val reason: String) : UiCard()
    data class AskBudget(val prompt: String, val minInr: Int, val maxInr: Int, val suggestedInr: Int, val perDayHint: String) : UiCard()
    data class AskTravellers(val prompt: String) : UiCard()
    data class Confirm(val prompt: String, val summary: String) : UiCard()
    data class Progress(val stage: String, val stages: List<String>, val done: Int) : UiCard()
}

data class MapPoint(val name: String, val lat: Double, val lon: Double, val kind: String)   // place | stay | stop
