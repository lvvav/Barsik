package com.barsik.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private val greeting = "Мяу! Я Барсик 🐾 Могу искать в браузере, открывать приложения, " +
            "ставить музыку на паузу, заводить таймер. Включи управление экраном в 🔑, " +
            "и я смогу нажимать кнопки в других приложениях."

    private lateinit var msgs: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var typingView: View? = null
    private var typingAnim: ObjectAnimator? = null
    private var history = JSONArray()

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) send(text)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.content)) { v, insets ->
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
        keyBtn.setOnClickListener { showMenu() }
        listOf(sendBtn, micBtn, keyBtn).forEach { it.pressable() }

        // Быстрые кнопки: отправляют готовую команду
        mapOf(
            R.id.chipFlash to "включи фонарик",
            R.id.chipAlarm to "поставь будильник на 7 утра",
            R.id.chipMusic to "поставь музыку на паузу",
            R.id.chipTimer to "поставь таймер на 5 минут",
            R.id.chipSettings to "открой настройки"
        ).forEach { (id, cmd) ->
            findViewById<View>(id).apply {
                setOnClickListener { send(cmd) }
                pressable()
            }
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false
        }

        val avatar = findViewById<View>(R.id.avatar)
        avatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatar.clipToOutline = true

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

        // Память: показываем прошлый разговор, он сохраняется между запусками
        history = Brain.loadHistory(this)
        if (history.length() == 0) {
            msgs.postDelayed({ addBubble(greeting, false) }, 400)
        } else {
            for (i in 0 until history.length()) {
                val m = history.getJSONObject(i)
                val isUser = m.optString("role") == "user"
                val text = if (isUser) m.optString("content")
                else Brain.parseReply(m.optString("content")).optString("reply", m.optString("content"))
                addBubble(text, isUser, animate = false)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ---------- анимации и пузыри ----------

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

    private fun addBubble(text: String, user: Boolean, animate: Boolean = true): View {
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
        if (animate) {
            tv.alpha = 0f
            tv.translationY = dp(26).toFloat()
            tv.scaleX = 0.92f; tv.scaleY = 0.92f
            tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(OvershootInterpolator(1.2f)).start()
        }
        scroll.postDelayed({ scroll.smoothScrollTo(0, msgs.bottom) }, 80)
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

    // ---------- ввод и ответы ----------

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
        addBubble(text, true)
        showTyping()
        Thread {
            try {
                val res = Brain.chat(this, history, text)
                runOnUiThread {
                    hideTyping()
                    addBubble(res.optString("reply", "Мяу!"), false)
                    runActions(res.optJSONArray("actions"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideTyping()
                    addBubble("Мяу... ошибка: ${e.message}", false)
                }
            }
        }.start()
    }

    private fun runActions(arr: JSONArray?) {
        if (arr == null) return
        for (n in 0 until arr.length()) {
            val a = arr.getJSONObject(n)
            val type = a.optString("type")
            val arg = a.optString("arg")
            if (type == "screen_task") {
                val svc = BarsikService.instance
                if (svc == null) addBubble("Чтобы я управлял экраном, включи меня: 🔑 → «Включить управление экраном».", false)
                else svc.runGoalAsync(arg)
            } else {
                Brain.execute(this, type, arg)
            }
        }
    }

    // ---------- меню и настройки ----------

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle("Барсик")
            .setItems(arrayOf(
                "Сервис ИИ и ключ",
                "Включить управление экраном",
                if (WakeService.running) "Выключить слушание «Барсик»" else "Слушать «Барсик» в фоне",
                "Голос и стиль",
                "Очистить память"
            )) { _, i ->
                when (i) {
                    0 -> pickProvider()
                    2 -> {
                        if (WakeService.running) {
                            stopService(Intent(this, WakeService::class.java))
                            Toast.makeText(this, "Слушание выключено", Toast.LENGTH_SHORT).show()
                        } else {
                            startForegroundService(Intent(this, WakeService::class.java))
                            Toast.makeText(this, "Скажи «Барсик» — я услышу. Первый запуск распаковывает речевую модель", Toast.LENGTH_LONG).show()
                        }
                    }
                    1 -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        Toast.makeText(this, "Найди «Барсик» в списке и включи", Toast.LENGTH_LONG).show()
                    }
                    3 -> showVoiceSettings()
                    4 -> {
                        Brain.clearHistory(this)
                        while (history.length() > 0) history.remove(0)
                        msgs.removeAllViews()
                        addBubble("Память очищена. Мяу!", false)
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showVoiceSettings() {
        val p = Brain.prefs(this)
        val speeds = arrayOf("Медленно" to 0.85f, "Норма" to 1.0f, "Быстро" to 1.2f)
        AlertDialog.Builder(this)
            .setTitle("Голос и стиль")
            .setItems(arrayOf(
                "Стиль: " + (if (Brain.persona(this) == "jarvis") "Джарвис (сменить на кота)" else "Кот-пацан (сменить на Джарвиса)"),
                "Озвучка ответов: " + (if (p.getBoolean("voice_on", true)) "вкл (выключить)" else "выкл (включить)"),
                "Скорость: " + speeds.first { it.second == p.getFloat("speed", 1.0f) }.first,
                "Скорость: медленно / норма / быстро"
            )) { _, i ->
                when (i) {
                    0 -> p.edit().putString("persona", if (Brain.persona(this) == "jarvis") "cat" else "jarvis").apply()
                    1 -> p.edit().putBoolean("voice_on", !p.getBoolean("voice_on", true)).apply()
                    2, 3 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Скорость голоса")
                            .setItems(speeds.map { it.first }.toTypedArray()) { _, j ->
                                p.edit().putFloat("speed", speeds[j].second).apply()
                            }
                            .show()
                    }
                }
                BarsikService.instance?.applyVoiceSettings()
                if (i == 0) Toast.makeText(this, "Стиль изменён", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private val presets = listOf(
        Triple("Pollinations (без ключа)", Brain.DEFAULT_URL, Brain.DEFAULT_MODEL),
        Triple("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest"),
        Triple("Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
        Triple("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
        Triple("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free"),
        Triple("Свой вариант", "", "")
    )

    private fun pickProvider() {
        AlertDialog.Builder(this)
            .setTitle("Какой ИИ использовать?")
            .setItems(presets.map { it.first }.toTypedArray()) { _, i ->
                val p = presets[i]
                showSettings(
                    p.second.ifEmpty { Brain.aiUrl(this) },
                    p.third.ifEmpty { Brain.aiModel(this) }
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSettings(url: String, model: String) {
        fun field(h: String, v: String) = EditText(this).apply {
            hint = h; setText(v); setSingleLine(true)
        }
        val fUrl = field("Адрес API", url)
        val fModel = field("Модель", model)
        val fKey = field("Ключ (если нужен)", Brain.prefs(this).getString("key", "") ?: "")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(fUrl); addView(fModel); addView(fKey)
        }
        AlertDialog.Builder(this)
            .setTitle("Настройки ИИ")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                Brain.prefs(this).edit()
                    .putString("url", fUrl.text.toString().trim())
                    .putString("model", fModel.text.toString().trim())
                    .putString("key", fKey.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
