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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val profileId: String,
    val streamId: Int,
    val channelName: String,
    val title: String,
    val startMs: Long,
) {
    val requestCode get() = (streamId.toLong() * 31 + startMs / 60_000).toInt()
}

/** "Remind me" for upcoming programmes: an alarm a couple of minutes before, then a notification. */
class Reminders(private val context: Context) {
    private val sp = context.getSharedPreferences("dekstream_reminders", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Reminder>> = _items

    fun add(profileId: String, c: Channel, p: Program) {
        val r = Reminder(profileId, c.streamId, c.name, p.title, p.startMs)
        _items.value = (_items.value.filter { !(it.streamId == r.streamId && it.startMs == r.startMs) } + r)
            .filter { it.startMs > System.currentTimeMillis() - 3600_000 }
        save()
        schedule(r)
    }

    fun cancel(streamId: Int, startMs: Long) {
        val r = _items.value.firstOrNull { it.streamId == streamId && it.startMs == startMs } ?: return
        _items.value = _items.value - r
        save()
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(r))
    }

    fun forProfile(id: String) = _items.value.filter { it.profileId == id }

    private fun pending(r: Reminder): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .putExtra("stream", r.streamId).putExtra("channel", r.channelName)
            .putExtra("title", r.title).putExtra("start", r.startMs)
        return PendingIntent.getBroadcast(context, r.requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = (r.startMs - 2 * 60_000).coerceAtLeast(System.currentTimeMillis() + 1_000)
        // A small window keeps it battery-friendly without needing the exact-alarm permission.
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, pending(r))
    }

    private fun load(): List<Reminder> = runCatching {
        val a = JSONArray(sp.getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Reminder(it.optString("p"), it.getInt("s"), it.optString("c"), it.optString("t"), it.getLong("at"))
        }.filter { it.startMs > System.currentTimeMillis() - 3600_000 }
    }.getOrDefault(emptyList())

    private fun save() {
        val a = JSONArray()
        _items.value.forEach { r ->
            a.put(JSONObject().apply { put("p", r.profileId); put("s", r.streamId); put("c", r.channelName); put("t", r.title); put("at", r.startMs) })
        }
        sp.edit().putString("items", a.toString()).apply()
    }

    companion object {
        const val CHANNEL_ID = "reminders"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Programme reminders", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.ensureChannel(context)
        val stream = intent.getIntExtra("stream", -1)
        val title = intent.getStringExtra("title") ?: "Programme"
        val channel = intent.getStringExtra("channel") ?: ""
        val start = intent.getLongExtra("start", 0L)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_PLAY_STREAM, stream)
        val pi = PendingIntent.getActivity(context, stream, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mins = ((start - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (mins > 0) "Starts in $mins min on $channel" else "Starting now on $channel")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Watch", pi)
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(stream, n) }
    }
}
