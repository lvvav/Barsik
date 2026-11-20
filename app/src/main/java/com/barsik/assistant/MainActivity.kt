package com.barsik.assistant

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        // Бесплатная модель Gemini. Если Google её переименует — поменяй здесь.
        const val MODEL = "gemini-2.5-flash"
    }

    private lateinit var msgs: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView

    private var typingView: View? = null
    private var typingAnim: ObjectAnimator? = null

    private val history = JSONArray()
    private val prefs by lazy { getSharedPreferences("barsik", MODE_PRIVATE) }

    private val voice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) send(text)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Во весь экран, прозрачные системные панели
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)

        val content = findViewById<View>(R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(dp(14) + i.left, dp(10) + i.top, dp(14) + i.right, dp(10) + i.bottom)
            insets
        }

        msgs = findViewById(R.id.msgs)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        status = findViewById(R.id.status)

        val sendBtn = findViewById<View>(R.id.sendBtn)
        val micBtn = findViewById<View>(R.id.micBtn)
        val keyBtn = findViewById<View>(R.id.keyBtn)
        sendBtn.setOnClickListener { sendFromInput() }
        micBtn.setOnClickListener { listen() }
        keyBtn.setOnClickListener { askKey() }
        listOf(sendBtn, micBtn, keyBtn).forEach { it.pressable() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false
        }

        // Круглый аватар
        val avatar = findViewById<View>(R.id.avatar)
        avatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatar.clipToOutline = true

        // Аватар «дышит»
        ObjectAnimator.ofPropertyValuesHolder(
            findViewById<View>(R.id.avatarRing),
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f)
        ).apply {
            duration = 2400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Появление шапки и поля ввода
        findViewById<View>(R.id.header).apply {
            alpha = 0f; translationY = -dp(40).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(600)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        }
        findViewById<View>(R.id.inputBar).apply {
            alpha = 0f; translationY = dp(60).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(600).setStartDelay(150)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        }

        msgs.postDelayed({
            addBubble(
                "Мяу! Я Барсик 🐾 Могу искать в браузере, открывать приложения, " +
                        "ставить музыку и видео на паузу, заводить таймер и просто болтать.",
                false
            )
        }, 500)

        if (apiKey().isBlank()) askKey()
    }

    // ---------- Анимации и пузыри ----------

    private fun View.pressable() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(110)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(380)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private fun addBubble(text: String, user: Boolean): View {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundResource(if (user) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(5); bottomMargin = dp(5)
        }
        msgs.addView(tv, lp)

        tv.alpha = 0f
        tv.translationY = dp(26).toFloat()
        tv.scaleX = 0.92f; tv.scaleY = 0.92f
        tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(420).setInterpolator(OvershootInterpolator(1.2f)).start()
        scrollDown()
        return tv
    }

    private fun showTyping() {
        hideTyping()
        status.text = "печатает…"
        val v = addBubble("●  ●  ●", false)
        typingView = v
        typingAnim = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.35f).apply {
            duration = 650
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            startDelay = 450
            start()
        }
    }

    private fun hideTyping() {
        typingAnim?.cancel(); typingAnim = null
        typingView?.let { msgs.removeView(it) }
        typingView = null
        status.text = "онлайн"
    }

    private fun scrollDown() {
        scroll.postDelayed({ scroll.smoothScrollTo(0, msgs.bottom) }, 80)
    }

    // ---------- Ключ и ввод ----------

    // Сначала ключ, введённый вручную; если его нет — ключ, вшитый при сборке
    private fun apiKey(): String {
        val saved = prefs.getString("key", "") ?: ""
        return if (saved.isNotBlank()) saved else BuildConfig.GEMINI_KEY
    }

    private fun askKey() {
        val et = EditText(this).apply {
            hint = "Ключ Gemini API"
            setText(prefs.getString("key", ""))
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
        addBubble(text, true)
        showTyping()
        Thread {
            try {
                val res = askGemini(text)
                runOnUiThread { hideTyping(); handle(res) }
            } catch (e: Exception) {
                runOnUiThread { hideTyping(); addBubble("Мяу... ошибка: ${e.message}", false) }
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
            throw RuntimeException(
                when (code) {
                    429 -> "лимит бесплатных запросов, подожди минуту"
                    400, 403 -> "ключ не подошёл (нажми 🔑)"
                    else -> "HTTP $code"
                }
            )
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
        addBubble(res.optString("reply", "Мяу!"), false)
        val acts = res.optJSONArray("actions") ?: return
        for (n in 0 until acts.length()) {
            val a = acts.getJSONObject(n)
            try { run(a.optString("type"), a.optString("arg")) }
            catch (e: Exception) { addBubble("Не получилось выполнить: ${a.optString("type")}", false) }
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
}
