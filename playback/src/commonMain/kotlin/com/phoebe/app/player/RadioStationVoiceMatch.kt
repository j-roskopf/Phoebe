package com.phoebe.app.player

import com.phoebe.app.domain.RadioStation

/**
 * Strong voice match for Android Auto radio: exact or normalized call-sign equality only.
 * Substring / fuzzy catalog ranking must not promote radio over tracks.
 */
fun findStrongRadioStationMatch(
    query: String,
    savedStations: List<RadioStation>,
    recommendedStations: List<RadioStation>,
): RadioStation? {
    val normalizedQuery = query.normalizedRadioCallSign()
    if (normalizedQuery.isBlank()) return null
    savedStations.firstOrNull { it.matchesStrongRadioVoiceQuery(normalizedQuery) }?.let { return it }
    return recommendedStations.firstOrNull { it.matchesStrongRadioVoiceQuery(normalizedQuery) }
}

fun RadioStation.matchesStrongRadioVoiceQuery(normalizedQuery: String): Boolean {
    if (normalizedQuery.isBlank()) return false
    return normalizedQuery in radioStationVoiceKeys()
}

fun RadioStation.radioStationVoiceKeys(): Set<String> =
    name.radioStationVoiceKeys()

fun String.radioStationVoiceKeys(): Set<String> {
    val full = normalizedRadioCallSign()
    if (full.isBlank()) return emptySet()
    val keys = linkedSetOf(full)
    val firstToken = full.split(' ').firstOrNull().orEmpty()
    // Leading call-sign / brand token ("KEXP" from "KEXP 90.3", "BBC" from "BBC Radio 6 Music").
    if (firstToken.length >= 3 && firstToken.any { it.isLetter() }) {
        keys += firstToken
    }
    return keys
}

fun String.normalizedRadioCallSign(): String =
    lowercase()
        .map { ch -> if (ch.isLetterOrDigit() || ch.isWhitespace()) ch else ' ' }
        .joinToString("")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ")
