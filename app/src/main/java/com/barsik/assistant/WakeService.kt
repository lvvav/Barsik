package com.barsik.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

// Фоновое прослушивание слова «Барсик» офлайн (движок Vosk, модель русского языка)
class WakeService : Service() {

    companion object {
        const val CHANNEL = "barsik_wake"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recorder: AudioRecord? = null
    @Volatile private var alive = false
    private var awaiting = false
    private var awaitingSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Барсик слушает", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Барсик")
            .setContentText("Слушаю, скажи «Барсик»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!alive) {
            val lang = Brain.prefs(this).getString("lang", "ru") ?: "ru"
            // Модель скачивается в меню «Голосовое дополнение», здесь её только загружаем
            if (!ModelService.installed(filesDir, lang)) {
                toast("Сначала скачай голосовое дополнение в меню 🔑")
                stopSelf()
                return START_NOT_STICKY
            }
            alive = true
            Thread {
                try {
                    model = Model(File(filesDir, "models/$lang").absolutePath)
                    startListening()
                } catch (e: Exception) {
                    toast("Не загрузилась речевая модель")
                    main.post { stopSelf() }
                }
            }.start()
        }
        return START_STICKY
    }

    private fun startListening() {
        val rate = 16000
        val buf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
        recorder = rec
        rec.startRecording()
        val rcg = Recognizer(model, rate.toFloat())
        Thread {
            val data = ByteArray(4000)
            while (alive) {
                val n = rec.read(data, 0, data.size)
                if (n > 0 && rcg.acceptWaveForm(data, n)) {
                    handle(JSONObject(rcg.result).optString("text"))
                }
            }
            rec.stop()
            rec.release()
            rcg.close()
        }.start()
    }

    private fun handle(text: String) {
        if (text.isBlank()) return
        val svc = BarsikService.instance
        val now = System.currentTimeMillis()

        // Если Барсик ждёт «да/нет» или мы уже услышали «Барсик» и ждём команду
        val waitingCommand = awaiting && now - awaitingSince < 8000
        if ((svc?.hasPending() == true) || waitingCommand) {
            awaiting = false
            dispatch(text)
            return
        }

        // Слово-пробуждение: «Барсик» по-русски и «barsik» по-английски
        val (idx, word) = listOf("барсик", "barsik")
            .map { w -> text.indexOf(w, ignoreCase = true) to w }
            .filter { it.first >= 0 }
            .minByOrNull { it.first } ?: return
        val cmd = text.substring(idx + word.length).trim(' ', ',', '.', '!', '?')
        if (cmd.isBlank()) {
            awaiting = true
            awaitingSince = now
            toast("Мяу? Слушаю")
        } else {
            dispatch(cmd)
        }
    }

    private fun dispatch(text: String) {
        val svc = BarsikService.instance
        if (svc == null) {
            toast("Включи управление экраном: 🔑 → «Включить управление экраном»")
        } else {
            svc.handleText(text)
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        alive = false
        running = false
        super.onDestroy()
    }
}
