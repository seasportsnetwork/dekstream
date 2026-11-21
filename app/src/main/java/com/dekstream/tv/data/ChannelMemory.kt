package com.dekstream.tv.data

import android.content.Context

/** Per-channel playback choices: audio track and picture size. */
class ChannelMemory(context: Context) {
    private val sp = context.getSharedPreferences("channel_memory", Context.MODE_PRIVATE)

    /** Remembered audio track: language code and label. */
    fun audio(streamId: Int): Pair<String?, String>? =
        sp.getString("a$streamId", null)?.split('\u0001')?.let { (it.getOrNull(0)?.ifBlank { null }) to (it.getOrNull(1) ?: "") }

    fun setAudio(streamId: Int, language: String?, label: String) =
        sp.edit().putString("a$streamId", (language ?: "") + "\u0001" + label).apply()

    /** Picture size index (Fit / Fill / Stretch); falls back to the last one used anywhere. */
    fun resize(streamId: Int?): Int = streamId?.let { sp.getInt("z$it", -1) }?.takeIf { it >= 0 } ?: sp.getInt("zLast", 0)

    fun setResize(streamId: Int?, idx: Int) {
        sp.edit().apply { if (streamId != null) putInt("z$streamId", idx); putInt("zLast", idx) }.apply()
    }
}
