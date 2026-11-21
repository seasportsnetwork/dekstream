package com.dekstream.tv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.dekstream.tv.R
import com.dekstream.tv.ui.MainActivity

/**
 * A "DekTV" row on the Android TV / Shield home screen with your favorite channels and what's on.
 * Selecting one opens DekTV straight into that channel.
 */
object TvHomeRow {
    private var lastSignature = ""
    private var lastAt = 0L

    private fun sp(c: Context) = c.getSharedPreferences("tv_home_row", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("on", on).apply()
        if (!on) clear(c)
        lastSignature = ""
    }

    private fun isTv(c: Context) = c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Call from a background thread. Cheap when nothing changed. */
    fun update(c: Context, channels: List<Channel>, nowTitle: (Channel) -> String?) {
        if (!isTv(c) || !enabled(c)) return
        val list = channels.take(20)
        val sig = list.joinToString(",") { it.streamId.toString() + ":" + (nowTitle(it) ?: "") }
        if (sig == lastSignature && System.currentTimeMillis() - lastAt < 30 * 60_000) return
        runCatching {
            val helper = PreviewChannelHelper(c)
            var id = sp(c).getLong("channel", -1L)
            if (id < 0 || helper.getPreviewChannel(id) == null) {
                val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val logo = ContextCompat.getDrawable(c, R.mipmap.ic_launcher)!!.toBitmap(160, 160)
                val ch = PreviewChannel.Builder()
                    .setDisplayName("DekTV · Your channels")
                    .setDescription("Your favorite channels and what's on")
                    .setAppLinkIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
                    .setLogo(logo)
                    .build()
                id = helper.publishDefaultChannel(ch)
                sp(c).edit().putLong("channel", id).apply()
            }
            c.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
            list.forEachIndexed { i, ch ->
                val play = Intent(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_PLAY_STREAM, ch.streamId)
                    .setData(Uri.parse("dektv://channel/${ch.streamId}"))
                val b = PreviewProgram.Builder()
                    .setChannelId(id)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                    .setTitle(ch.name)
                    .setDescription(nowTitle(ch) ?: "Live")
                    .setLive(true)
                    .setWeight(1000 - i)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setIntentUri(Uri.parse(play.toUri(Intent.URI_INTENT_SCHEME)))
                ch.icon?.let { b.setPosterArtUri(Uri.parse(it)) }
                helper.publishPreviewProgram(b.build())
            }
            lastSignature = sig; lastAt = System.currentTimeMillis()
            android.util.Log.i("DekTV", "Home row updated: ${list.size} channels (row $id)")
        }.onFailure { android.util.Log.w("DekTV", "Home row failed", it) }
    }

    private fun clear(c: Context) {
        runCatching {
            val id = sp(c).getLong("channel", -1L)
            if (id >= 0) PreviewChannelHelper(c).deletePreviewChannel(id)
            sp(c).edit().remove("channel").apply()
        }
    }
}
