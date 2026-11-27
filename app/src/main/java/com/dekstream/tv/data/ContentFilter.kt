package com.dekstream.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Per-profile content choices that the whole app reads (loaded when a profile opens). */
object ContentFilter {
    /** Adult (XXX) channels, categories, movies and series are hidden unless this is on. Kids profiles: always off. */
    var showAdult by mutableStateOf(false)

    private val adult = Regex(
        "(?i)(\\bxxx\\b|\\bporn|\\berotic|\\bplayboy\\b|\\bhustler\\b|\\bbrazzers\\b|\\bpenthouse\\b|\\bdorcel\\b|" +
            "\\bred ?light\\b|\\b18\\+|\\+18\\b|\\[18]|\\badults?\\b(?!\\s*swim)|\\bsexy?\\b|\\bnsfw\\b|\\bonlyfans\\b|\\bhentai\\b)"
    )

    fun isAdult(name: String?): Boolean = name != null && adult.containsMatchIn(name)
    fun hides(name: String?): Boolean = !showAdult && isAdult(name)
}

/** Which sections Home shows (per profile). */
object HomeLayout {
    /** Section keys and their labels, in the order Home draws them. */
    val sections = listOf(
        "spotlight" to "Spotlight & scores",
        "favorites" to "Your channels",
        "upnext" to "Coming up on your channels",
        "categories" to "On now by category",
        "continue" to "Continue watching (movies & shows)",
        "mylist" to "My list (movies & shows)",
        "moods" to "Genres",
        "movies" to "Movies on Home",
        "series" to "Series on Home",
    )
    var hidden by mutableStateOf(emptySet<String>())

    fun shows(key: String) = key !in hidden

    /** "Live TV only": no movie or series rows, moods or VOD in the highlights. */
    val liveOnlyHidden = setOf("moods", "movies", "series", "continue", "mylist")
    val isLiveOnly get() = hidden.containsAll(liveOnlyHidden)
}
