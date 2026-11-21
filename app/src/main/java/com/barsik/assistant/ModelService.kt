package com.barsik.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// Скачивает голосовые модели в фоне: можно выйти из приложения и продолжать пользоваться
class ModelService : Service() {

    companion object {
        const val RU_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        const val EN_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val CHANNEL = "barsik_models"

        @Volatile var active = false
        @Volatile var percent = 0
        @Volatile var status = ""
        @Volatile var lang = ""

        fun installed(filesDir: File, lang: String) = File(filesDir, "models/$lang/am").exists()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active) return START_NOT_STICKY
        val l = intent?.getStringExtra("lang") ?: "ru"
        lang = l
        active = true
        percent = 0
        status = "Начинаю загрузку…"

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Загрузка голоса", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(2, notif(0))

        Thread {
            try {
                download(if (l == "en") EN_URL else RU_URL, l)
                percent = 100
                status = "Готово"
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                active = false
                stopForeground(true)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun notif(pct: Int): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle("Барсик: голос ($lang)")
            .setContentText("$pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .build()
    }

    private fun download(url: String, l: String) {
        val zip = File(cacheDir, "$l.zip")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        val total = c.contentLengthLong
        var done = 0L
        var lastNotif = 0L
        status = "Скачано 0%"
        c.inputStream.use { inp ->
            zip.outputStream().use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        percent = (done * 100 / total).toInt().coerceAtMost(98)
                        status = "Скачано $percent%"
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 1000) {
                            lastNotif = now
                            getSystemService(NotificationManager::class.java).notify(2, notif(percent))
                        }
                    }
                }
            }
        }
        c.disconnect()

        status = "Распаковываю…"
        percent = 99
        val target = File(filesDir, "models/$l")
        target.deleteRecursively()
        ZipInputStream(zip.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                // В архиве всё лежит в папке vosk-model-...; убираем её, чтобы получить models/ru/am/...
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val f = File(target, rel)
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zin.copyTo(it) }
                    }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        zip.delete()
    }

    override fun onDestroy() {
        active = false
        super.onDestroy()
    }
}
