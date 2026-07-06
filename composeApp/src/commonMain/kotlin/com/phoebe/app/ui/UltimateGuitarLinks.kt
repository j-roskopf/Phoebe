package com.phoebe.app.ui

import com.phoebe.app.domain.Track
import io.ktor.http.URLBuilder

private const val UltimateGuitarSearchUrl = "https://www.ultimate-guitar.com/search.php"

internal fun ultimateGuitarSearchUrl(track: Track): String? {
    val title = track.title.normalizedUltimateGuitarSearchTerm()
    if (title.isBlank()) return null
    val artist = track.artist.normalizedUltimateGuitarSearchTerm()
    val query = listOf(title, artist)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    return URLBuilder(UltimateGuitarSearchUrl).apply {
        parameters.append("search_type", "title")
        parameters.append("value", query)
    }.buildString()
}

private fun String.normalizedUltimateGuitarSearchTerm(): String =
    trim().replace(Regex("\\s+"), " ")
