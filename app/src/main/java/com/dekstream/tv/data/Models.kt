package com.dekstream.tv.data

data class Account(val server: String, val username: String, val password: String) {
    /** Server URL normalised: scheme present, no trailing slash. */
    val base: String
        get() {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }
}

data class AccountInfo(
    val status: String,
    val expiresAtMs: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
    val timezone: String?,
)

data class Category(val id: String, val name: String)

data class Channel(
    val streamId: Int,
    val num: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val epgChannelId: String?,
    val hasArchive: Boolean,
    val archiveDays: Int,
    /** Direct stream URL (M3U) or portal command (Stalker); null for Xtream (built from the id). */
    val url: String? = null,
    /** M3U catch-up: "default", "append", "shift", "flussonic"... */
    val catchupType: String? = null,
    val catchupSource: String? = null,
)

data class Vod(
    val streamId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val ext: String,
    val rating: String?,
    val url: String? = null,
)

data class VodInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val duration: String?,
    val backdrop: String?,
    val rating: String?,
)

data class Series(
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String?,
    val rating: String?,
    val genre: String?,
    val releaseDate: String?,
    val backdrop: String? = null,
)

data class Episode(
    val id: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val ext: String,
    val plot: String?,
    val image: String?,
    val duration: String?,
    val url: String? = null,
)

data class SeriesInfo(
    val plot: String?,
    val backdrop: String?,
    val genre: String?,
    val cast: String?,
    val seasons: Map<Int, List<Episode>>,
)

data class Program(
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val hasArchive: Boolean,
) {
    fun isLive(now: Long = System.currentTimeMillis()) = now in startMs until endMs
}

/** What the player is asked to play. */
data class PlayRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val isLive: Boolean,
    /** Key for resume tracking, e.g. "vod:123" or "ep:456"; null for live. */
    val progressKey: String? = null,
    val poster: String? = null,
    val channel: Channel? = null,
    val startPositionMs: Long = 0,
)

data class ProgressEntry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
