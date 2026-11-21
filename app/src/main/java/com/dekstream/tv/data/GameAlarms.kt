package com.dekstream.tv.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dekstream.tv.R
import com.dekstream.tv.ui.MainActivity

/**
 * "Your team plays in 10 minutes" as a system notification, scheduled with AlarmManager so it arrives
 * even when DekTV is closed. Watch opens DekTV and plays the best channel for the game.
 */
object GameAlarms {
    const val CHANNEL_ID = "games"
    const val LEAD_MS = 10 * 60_000L

    private fun sp(c: Context) = c.getSharedPreferences("game_alarms", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { sp(c).edit().putBoolean("on", on).apply() }
    fun tickerOn(c: Context) = sp(c).getBoolean("ticker", true)
    fun setTicker(c: Context, on: Boolean) { sp(c).edit().putBoolean("ticker", on).apply() }

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Your teams' games", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun intent(c: Context, g: Game) = Intent(c, GameAlarmReceiver::class.java)
        .putExtra("league", g.league.key).putExtra("id", g.id).putExtra("start", g.startMs)
        .putExtra("title", "${g.away.nickname.ifBlank { g.away.abbr }} @ ${g.home.nickname.ifBlank { g.home.abbr }}")
        .putExtra("tv", g.broadcasts.take(2).joinToString(", "))

    /** (Re)schedules alerts for these upcoming games of your teams. */
    fun schedule(c: Context, games: List<Game>) {
        if (!enabled(c)) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        for (g in games) {
            if (g.state != "pre") continue
            val at = g.startMs - LEAD_MS
            if (at <= now + 30_000) continue
            val pi = PendingIntent.getBroadcast(c, g.id.hashCode(), intent(c, g), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, pi)
        }
    }
}

class GameAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GameAlarms.enabled(context)) return
        GameAlarms.ensureChannel(context)
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title") ?: "Game"
        val start = intent.getLongExtra("start", 0L)
        val tv = intent.getStringExtra("tv").orEmpty()
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_GAME_LEAGUE, intent.getStringExtra("league"))
            .putExtra(MainActivity.EXTRA_GAME_ID, id)
            .putExtra(MainActivity.EXTRA_GAME_START, start)
        val pi = PendingIntent.getActivity(context, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mins = ((start - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val n = NotificationCompat.Builder(context, GameAlarms.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText((if (mins > 0) "Starts in $mins min" else "Starting now") + if (tv.isNotBlank()) " · $tv" else "")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Watch", pi)
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), n) }
    }
}
