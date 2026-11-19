package com.dekstream.tv.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Profile-wide settings that don't depend on the playlist: appearance, kids rules, viewing totals.
 * Shares the "dsp_<profile>" file with the sports settings in [Prefs].
 */
class ProfileSettings(context: Context, val profileId: String) {
    private val pp = context.getSharedPreferences("dsp_$profileId", Context.MODE_PRIVATE)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun today() = dayFmt.format(Date())

    // ---- Appearance ----
    var accentIdx: Int
        get() = pp.getInt("accent", 0)
        set(v) = pp.edit().putInt("accent", v).apply()
    var paletteIdx: Int
        get() = pp.getInt("palette", 0)
        set(v) = pp.edit().putInt("palette", v).apply()
    var selectionIdx: Int
        get() = pp.getInt("selection", 0)
        set(v) = pp.edit().putInt("selection", v).apply()
    var guideBgIdx: Int
        get() = pp.getInt("guideBg", 0)
        set(v) = pp.edit().putInt("guideBg", v).apply()
    var panelAlpha: Float
        get() = pp.getFloat("alpha", 1f)
        set(v) = pp.edit().putFloat("alpha", v).apply()

    // ---- Kids rules ----
    /** Minutes per day; 0 = no limit. */
    var dailyLimitMin: Int
        get() = pp.getInt("limit", 0)
        set(v) = pp.edit().putInt("limit", v).apply()
    /** Bedtime as minutes after midnight; -1 = off. */
    var bedtimeStart: Int
        get() = pp.getInt("bedStart", -1)
        set(v) = pp.edit().putInt("bedStart", v).apply()
    var bedtimeEnd: Int
        get() = pp.getInt("bedEnd", 7 * 60)
        set(v) = pp.edit().putInt("bedEnd", v).apply()
    /** "Pause now" from a parent. */
    var paused: Boolean
        get() = pp.getBoolean("paused", false)
        set(v) = pp.edit().putBoolean("paused", v).apply()

    /** Extra minutes a parent granted today ("Ask a parent"). */
    var bonusToday: Int
        get() = if (pp.getString("bonusDay", "") == today()) pp.getInt("bonus", 0) else 0
        set(v) = pp.edit().putString("bonusDay", today()).putInt("bonus", v).apply()

    /** Category ids a kids profile may open, per playlist ("l:", "v:", "s:" + id). Empty = everything. */
    fun allowed(sourceId: String): Set<String> =
        pp.getString("allow_$sourceId", "")!!.split('|').filter { it.isNotBlank() }.toSet()
    fun setAllowed(sourceId: String, ids: Set<String>) = pp.edit().putString("allow_$sourceId", ids.joinToString("|")).apply()

    // ---- Viewing time ----
    fun addWatchMinute() {
        val key = "w_" + today()
        pp.edit().putInt(key, pp.getInt(key, 0) + 1).apply()
    }

    fun minutesToday() = pp.getInt("w_" + today(), 0)

    /** Last 7 days, oldest first: (label, minutes). */
    fun last7Days(): List<Pair<String, Int>> {
        val c = Calendar.getInstance()
        val label = SimpleDateFormat("EEE", Locale.getDefault())
        return (6 downTo 0).map { back ->
            c.time = Date(); c.add(Calendar.DAY_OF_YEAR, -back)
            label.format(c.time) to pp.getInt("w_" + dayFmt.format(c.time), 0)
        }
    }

    // ---- Lock state for kids profiles ----
    sealed interface Lock {
        data object Paused : Lock
        data object Bedtime : Lock
        data object TimeUp : Lock
    }

    fun lockReason(): Lock? {
        if (paused) return Lock.Paused
        if (bedtimeStart >= 0) {
            val c = Calendar.getInstance()
            val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val inBed = if (bedtimeStart < bedtimeEnd) now in bedtimeStart until bedtimeEnd
            else now >= bedtimeStart || now < bedtimeEnd
            if (inBed && bonusToday == 0) return Lock.Bedtime
        }
        if (dailyLimitMin > 0 && minutesToday() >= dailyLimitMin + bonusToday) return Lock.TimeUp
        return null
    }
}
