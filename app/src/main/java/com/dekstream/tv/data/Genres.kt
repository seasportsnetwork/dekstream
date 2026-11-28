package com.dekstream.tv.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The standard genres shown on Home, with TMDB's genre ids for movies and TV. */
object Genres {
    data class Genre(val name: String, val movieId: Int, val tvId: Int?, val words: List<String>)

    val all = listOf(
        Genre("Action", 28, 10759, listOf("action")),
        Genre("Adventure", 12, 10759, listOf("adventure")),
        Genre("Animation", 16, 16, listOf("animation", "animated", "anime", "cartoon")),
        Genre("Comedy", 35, 35, listOf("comedy", "comedies")),
        Genre("Crime", 80, 80, listOf("crime", "gangster")),
        Genre("Documentary", 99, 99, listOf("documentar", "docu")),
        Genre("Drama", 18, 18, listOf("drama")),
        Genre("Family", 10751, 10751, listOf("family", "kids", "children")),
        Genre("Fantasy", 14, 10765, listOf("fantasy")),
        Genre("History", 36, null, listOf("history", "historical")),
        Genre("Horror", 27, null, listOf("horror")),
        Genre("Music", 10402, null, listOf("music", "musical", "concert")),
        Genre("Mystery", 9648, 9648, listOf("mystery")),
        Genre("Romance", 10749, null, listOf("romance", "romantic")),
        Genre("Sci-Fi", 878, 10765, listOf("sci-fi", "scifi", "science fiction", "sci fi")),
        Genre("Thriller", 53, null, listOf("thriller", "suspense")),
        Genre("War", 10752, 10768, listOf("war ", "war&", "military", "war")),
        Genre("Western", 37, 37, listOf("western")),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name }

    data class Result(val movies: List<Vod>, val series: List<Series>, val fromTmdb: Boolean)

    /**
     * Playlist titles in a genre. With a TMDB key: TMDB's most popular titles in that genre, matched against the
     * playlist. Without one: the playlist's own categories whose names mention the genre.
     */
    suspend fun titles(c: Context, repo: Repo, g: Genre): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { coroutineScope {
        if (Stremio.tmdbKey(c).isNotBlank()) {
            val m = async { Stremio.genreTitles(c, "movie", g.movieId) }
            val t = async { g.tvId?.let { Stremio.genreTitles(c, "tv", it) }.orEmpty() }
            val movies = Top10.match(m.await(), repo.allVods(), 60, { it.name }, { it.poster != null })
            val series = Top10.match(t.await(), repo.allSeries(), 40, { it.name }, { it.cover != null })
            if (movies.isNotEmpty() || series.isNotEmpty()) return@coroutineScope Result(movies, series, true)
        }
        fun hit(name: String) = g.words.any { w -> name.lowercase().contains(w.trim()) }
        val movies = repo.vodCategories().filter { hit(it.name) }.take(6).flatMap { runCatching { repo.vods(it.id) }.getOrDefault(emptyList()) }
            .distinctBy { it.streamId }.take(120)
        val series = repo.seriesCategories().filter { hit(it.name) }.take(4).flatMap { runCatching { repo.series(it.id) }.getOrDefault(emptyList()) }
            .distinctBy { it.seriesId }.take(80)
        Result(movies, series, false)
    } }
}
