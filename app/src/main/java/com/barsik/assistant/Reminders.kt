package com.barsik.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

// Напоминания: сохраняются в настройках и ставятся в AlarmManager, переживают перезагрузку телефона
object Reminders {
    private const val CHANNEL = "barsik_reminders"

    private fun load(ctx: Context): JSONArray =
        try { JSONArray(Brain.prefs(ctx).getString("reminders", "[]")) } catch (e: Exception) { JSONArray() }

    private fun save(ctx: Context, a: JSONArray) {
        Brain.prefs(ctx).edit().putString("reminders", a.toString()).apply()
    }

    // delaySec — через сколько секунд напомнить (от секунд до лет)
    fun add(ctx: Context, delaySec: Long, text: String): Long {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val at = System.currentTimeMillis() + delaySec.coerceAtLeast(1) * 1000
        val a = load(ctx)
        a.put(JSONObject().put("id", id).put("at", at).put("text", text))
        save(ctx, a)
        schedule(ctx, id, at, text)
        return at
    }

    private fun schedule(ctx: Context, id: Int, at: Long, text: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingFor(ctx, id, text)
        // Неточный, но надёжный будильник: работает даже в режиме сна и не требует спец-разрешения
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun pendingFor(ctx: Context, id: Int, text: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("id", id).putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, id, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // После перезагрузки ставим все ещё не сработавшие напоминания заново
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val a = load(ctx)
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val at = r.optLong("at")
            if (at > now) schedule(ctx, r.optInt("id"), at, r.optString("text"))
        }
    }

    fun fire(ctx: Context, id: Int, text: String) {
        val a = load(ctx)
        val rest = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optInt("id") != id) rest.put(a.get(i))
        save(ctx, rest)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Барсик напоминает")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.rescheduleAll(ctx)
        } else {
            Reminders.fire(ctx, intent.getIntExtra("id", 0), intent.getStringExtra("text") ?: "")
        }
    }
}
