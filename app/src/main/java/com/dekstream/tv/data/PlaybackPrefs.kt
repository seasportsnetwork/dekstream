package com.dekstream.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Device-wide playback and screen options (Settings > Playback & screen). */
object PlaybackPrefs {
    private var ctx: Context? = null
    private val sp get() = ctx!!.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var matchFrameRate by mutableStateOf(true); private set
    /** Up/Down in the player: "guide" = browse what's on, "zap" = change channel. */
    var upDown by mutableStateOf("guide"); private set
    var captionSize by mutableStateOf(1); private set      // 0 small, 1 normal, 2 large, 3 extra large
    var captionBg by mutableStateOf(1); private set        // 0 none, 1 shaded, 2 solid
    var captionHigh by mutableStateOf(false); private set  // raise captions above the bottom
    /** "home", "guide" or "last" (last channel). */
    var startOn by mutableStateOf("home"); private set
    var bestVersion by mutableStateOf(true); private set
    var burnIn by mutableStateOf(true); private set

    fun load(c: Context) {
        ctx = c.applicationContext
        matchFrameRate = sp.getBoolean("afr", true)
        upDown = sp.getString("upDown", "guide")!!
        captionSize = sp.getInt("capSize", 1)
        captionBg = sp.getInt("capBg", 1)
        captionHigh = sp.getBoolean("capHigh", false)
        startOn = sp.getString("startOn", "home")!!
        bestVersion = sp.getBoolean("best", true)
        burnIn = sp.getBoolean("burnIn", true)
    }

    fun changeMatchFrameRate(v: Boolean) { matchFrameRate = v; sp.edit().putBoolean("afr", v).apply() }
    fun changeUpDown(v: String) { upDown = v; sp.edit().putString("upDown", v).apply() }
    fun changeCaptionSize(v: Int) { captionSize = v; sp.edit().putInt("capSize", v).apply() }
    fun changeCaptionBg(v: Int) { captionBg = v; sp.edit().putInt("capBg", v).apply() }
    fun changeCaptionHigh(v: Boolean) { captionHigh = v; sp.edit().putBoolean("capHigh", v).apply() }
    fun changeStartOn(v: String) { startOn = v; sp.edit().putString("startOn", v).apply() }
    fun changeBestVersion(v: Boolean) { bestVersion = v; sp.edit().putBoolean("best", v).apply() }
    fun changeBurnIn(v: Boolean) { burnIn = v; sp.edit().putBoolean("burnIn", v).apply() }

    // ---- Best version of a channel: the copy that played best, per channel name ----
    fun best(group: String): Pair<Int, Int>? =
        sp.getString("b:$group", null)?.split(':')?.let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }

    /** Remembers [streamId] as the best copy of [group] if it plays at a higher resolution than the one on record. */
    fun recordGood(group: String, streamId: Int, height: Int) {
        val cur = best(group)
        if (cur == null || height > cur.second || cur.first == streamId) sp.edit().putString("b:$group", "$streamId:$height").apply()
    }

    /** That copy failed: forget it. */
    fun forgetBest(group: String, streamId: Int) {
        if (best(group)?.first == streamId) sp.edit().remove("b:$group").apply()
    }
}
