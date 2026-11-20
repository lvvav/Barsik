package com.barsik.assistant

import android.app.AlertDialog
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // Бесплатная модель Gemini. Если Google её переименует — поменяй здесь.
        const val MODEL = "gemini-2.5-flash"
    }

    private lateinit var chat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private val history = JSONArray()
    private val prefs by lazy { getSharedPreferences("barsik", MODE_PRIVATE) }

    private val voice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) send(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)

        findViewById<Button>(R.id.sendBtn).setOnClickListener { sendFromInput() }
        findViewById<Button>(R.id.keyBtn).setOnClickListener { askKey() }
        findViewById<Button>(R.id.micBtn).setOnClickListener { listen() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false
        }

        addLine("Барсик: Мяу! Я Барсик. Могу искать в браузере, открывать приложения, " +
                "ставить музыку и видео на паузу, заводить таймер и просто болтать.")
        if (apiKey().isBlank()) askKey()
    }

    private fun apiKey() = prefs.getString("key", "") ?: ""

    private fun askKey() {
        val et = EditText(this).apply {
            hint = "Ключ Gemini API"
            setText(apiKey())
        }
        AlertDialog.Builder(this)
            .setTitle("Ключ Gemini (бесплатный)")
            .setMessage("Получи его бесплатно на aistudio.google.com/apikey и вставь сюда.")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString("key", et.text.toString().trim()).apply()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говори с Барсиком")
        }
        try { voice.launch(i) } catch (e: Exception) {
            Toast.makeText(this, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFromInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        send(t)
    }

    private fun send(text: String) {
        if (apiKey().isBlank()) { askKey(); return }
        addLine("Ты: $text")
        Thread {
            try {
                val res = askGemini(text)
                runOnUiThread { handle(res) }
            } catch (e: Exception) {
                runOnUiThread { addLine("Барсик: Мяу... ошибка: ${e.message}") }
            }
        }.start()
    }

    // ---------- Gemini ----------

    private fun installedApps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
    }

    private fun systemPrompt(): String {
        val apps = installedApps().joinToString(", ") { it.first }
        return """
Ты — Барсик, дружелюбный серый кот и голосовой помощник на телефоне. Отвечай по-русски, коротко, чуть игриво (иногда "мяу").
Ты умеешь выполнять действия на телефоне. Ответ ВСЕГДА строго JSON вида:
{"reply":"текст пользователю","actions":[{"type":"...","arg":"..."}]}
Доступные type:
- "search": arg = поисковый запрос (открыть поиск в браузере)
- "open_url": arg = полный URL
- "open_app": arg = точное название приложения из списка установленных
- "media_pause", "media_play", "media_stop", "media_next", "media_prev": остановить/продолжить/следующий/предыдущий трек или видео (arg пустой)
- "volume_up", "volume_down": громкость
- "timer": arg = число секунд
Если действий не нужно (просто разговор) — "actions":[]. Не выдумывай приложения, которых нет в списке.
Установленные приложения: $apps
""".trimIndent()
    }

    private fun askGemini(userText: String): JSONObject {
        history.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText))))

        val body = JSONObject()
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", history)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey())
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            history.remove(history.length() - 1)
            throw RuntimeException(if (code == 429) "лимит бесплатных запросов, подожди минуту" else "HTTP $code")
        }

        val text = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")

        history.put(JSONObject().put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.length() > 12) history.remove(0)

        return JSONObject(text)
    }

    // ---------- Действия ----------

    private fun handle(res: JSONObject) {
        addLine("Барсик: " + res.optString("reply", "Мяу!"))
        val acts = res.optJSONArray("actions") ?: return
        for (n in 0 until acts.length()) {
            val a = acts.getJSONObject(n)
            try { run(a.optString("type"), a.optString("arg")) }
            catch (e: Exception) { addLine("Барсик: не получилось (${a.optString("type")})") }
        }
    }

    private fun run(type: String, arg: String) {
        when (type) {
            "search" -> openUri("https://www.google.com/search?q=" + URLEncoder.encode(arg, "UTF-8"))
            "open_url" -> openUri(arg)
            "open_app" -> openApp(arg)
            "media_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "media_stop" -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
            "media_play" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            "media_next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "media_prev" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> volume(AudioManager.ADJUST_RAISE)
            "volume_down" -> volume(AudioManager.ADJUST_LOWER)
            "timer" -> startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, arg.toDoubleOrNull()?.toInt() ?: 60)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
    }

    private fun openUri(u: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))

    private fun openApp(name: String) {
        val apps = installedApps()
        val hit = apps.firstOrNull { it.first.equals(name, true) }
            ?: apps.firstOrNull { it.first.contains(name, true) }
            ?: throw RuntimeException("нет приложения")
        startActivity(packageManager.getLaunchIntentForPackage(hit.second)!!)
    }

    private fun mediaKey(code: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun volume(dir: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun addLine(s: String) {
        chat.append(s + "\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
