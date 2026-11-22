package com.barsik.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private const val VERSION_URL = "https://github.com/lvvav/Barsik/releases/download/latest/version.txt"

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
    private lateinit var dlBar: FrameLayout
    private lateinit var dlFill: View
    private lateinit var dlText: TextView
    private val ui = Handler(Looper.getMainLooper())

    // Обновляет полосу загрузки голоса, пока ModelService качает файлы
    private val tick = object : Runnable {
        override fun run() {
            dlBar.visibility = View.VISIBLE
            val w = dlBar.width
            dlFill.layoutParams = FrameLayout.LayoutParams(w * ModelService.percent / 100, FrameLayout.LayoutParams.MATCH_PARENT)
            dlText.text = ModelService.status
            if (ModelService.active) {
                ui.postDelayed(this, 300)
            } else {
                // Загрузка закончилась: показываем итог и прячем полосу
                ui.postDelayed({ dlBar.visibility = View.GONE }, 2500)
            }
        }
    }

    private fun startTick() {
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) send(text)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Напоминания показываются уведомлениями: на Android 13+ нужно разрешение
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

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
        dlBar = findViewById(R.id.dlBar)
        dlFill = findViewById(R.id.dlFill)
        dlText = findViewById(R.id.dlText)
        Ui.apply(findViewById(R.id.content), Ui.font(this))

        val sendBtn = findViewById<View>(R.id.sendBtn)
        val micBtn = findViewById<View>(R.id.micBtn)
        val keyBtn = findViewById<View>(R.id.keyBtn)
        sendBtn.setOnClickListener { sendFromInput() }
        micBtn.setOnClickListener { listen() }
        keyBtn.setOnClickListener { showMenu() }
        listOf(sendBtn, micBtn, keyBtn).forEach { it.pressable() }

        // Чип с текущей моделью: тап открывает выбор нейросети
        findViewById<TextView>(R.id.modelChip).apply {
            setOnClickListener { pickProvider() }
            pressable()
        }
        refreshModelChip()

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
        Brain.migrate(this)
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

    override fun onStart() {
        super.onStart()
        // Проверяем версию при каждом открытии приложения
        checkUpdate(false)
        if (ModelService.active) startTick()
    }

    // ---------- обновления и голосовое дополнение ----------

    private fun checkUpdate(manual: Boolean) {
        Thread {
            try {
                val c = URL(VERSION_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val latest = c.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() ?: 0 }
                c.disconnect()
                runOnUiThread {
                    if (latest > BuildConfig.VERSION_CODE) {
                        Glass.show(this, "Вышло обновление", listOf(
                            Glass.Row(R.drawable.ic_download, "Обновить", "Скачать и установить новую версию") { AppUpdate.start(this) },
                            Glass.Row(R.drawable.ic_refresh, "Позже", null) {}
                        ))
                    } else if (manual) {
                        Banner.show(this, "У тебя последняя версия")
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread {
                    Banner.show(this, "Не удалось проверить обновление")
                }
            }
        }.start()
    }

    private fun showVoicePack() {
        val ruOk = ModelService.installed(filesDir, "ru")
        val enOk = ModelService.installed(filesDir, "en")
        // Скачанные языки работают сразу, отдельного переключения не нужно
        Glass.show(this, "Голосовое дополнение", listOf(
            Glass.Row(R.drawable.ic_download, if (ruOk) "Русский: установлен" else "Скачать русский",
                if (ruOk) "Нажми, чтобы скачать заново" else "Слово «Барсик»") { startDownload("ru") },
            Glass.Row(R.drawable.ic_download, if (enOk) "English: установлен" else "Скачать English",
                if (enOk) "Нажми, чтобы скачать заново" else "Слово «barsik»") { startDownload("en") }
        ))
    }

    private fun startDownload(l: String) {
        if (ModelService.active) {
            Banner.show(this, "Загрузка уже идёт")
        } else {
            ContextCompat.startForegroundService(this, Intent(this, ModelService::class.java).putExtra("lang", l))
        }
        startTick()
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
            setTextColor(if (user) Color.parseColor("#1A120C") else Color.parseColor("#F5EBE0"))
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundResource(if (user) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            typeface = Ui.font(this@MainActivity)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Brain.voiceLocale(this@MainActivity))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говори с Барсиком")
        }
        try { voice.launch(i) } catch (e: Exception) {
            Banner.show(this, "Голосовой ввод недоступен")
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
                    addSources(res.optJSONArray("sources"))
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
            if (type == "screen_task" || type == "music") {
                val svc = BarsikService.instance
                if (svc == null) addBubble("Чтобы я управлял экраном, включи меня: 🔑 → «Включить управление экраном».", false)
                else svc.runGoalAsync(if (type == "music") Brain.musicGoal(arg) else arg)
            } else {
                Brain.execute(this, type, arg)
            }
        }
    }

    // ---------- меню и настройки ----------

    // Перерисовывает чат из history (после открытия или создания чата)
    private fun showChat() {
        msgs.removeAllViews()
        if (history.length() == 0) {
            addBubble(greeting, false)
            return
        }
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            val isUser = m.optString("role") == "user"
            val parsed = if (isUser) null else Brain.parseReply(m.optString("content"))
            val text = if (isUser) m.optString("content")
            else parsed!!.optString("reply", m.optString("content"))
            if (parsed != null) addSources(parsed.optJSONArray("sources"), animate = false)
            addBubble(text, isUser, animate = false)
        }
    }

    // Значки источников: до трёх мелких кружков с логотипами сайтов над ответом; тап открывает список
    private fun addSources(arr: JSONArray?, animate: Boolean = true) {
        if (arr == null || arr.length() == 0) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), 0, dp(2))
            setOnClickListener { showSources(arr) }
        }
        for (i in 0 until minOf(arr.length(), 3)) {
            val host = hostOf(arr.getJSONObject(i).optString("url"))
            val letter = TextView(this).apply {
                text = host.firstOrNull()?.uppercase() ?: "?"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1A120C"))
                textSize = 9f
                typeface = Ui.font(this@MainActivity)
            }
            val logo = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            val circle = android.widget.FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F5EBE0"))
                }
                clipToOutline = true
                addView(letter, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(logo, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            row.addView(circle, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) })
            // Логотип сайта грузим в фоне; если не вышло, остаётся буква
            Thread {
                try {
                    val c = URL("https://www.google.com/s2/favicons?sz=64&domain=$host").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    val bmp = c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    if (bmp != null) runOnUiThread {
                        logo.setImageBitmap(bmp)
                        logo.visibility = View.VISIBLE
                        letter.visibility = View.GONE
                    }
                } catch (_: Exception) {}
            }.start()
        }
        msgs.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.START; topMargin = dp(4); bottomMargin = dp(1) })
        if (animate) {
            row.alpha = 0f
            row.animate().alpha(1f).setDuration(350).start()
        }
    }

    private fun hostOf(url: String): String =
        try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }

    private fun showSources(arr: JSONArray) {
        val rows = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Glass.Row(R.drawable.ic_folder, s.optString("title").take(70).ifBlank { "Страница" },
                hostOf(s.optString("url"))) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.optString("url"))))
            }
        }
        Glass.show(this, "Источники", rows)
    }

    private fun showChatList() {
        val chats = Brain.savedChats(this)
        if (chats.isEmpty()) {
            Banner.show(this, "Сохранённых чатов пока нет")
            return
        }
        Glass.show(this, "Мои чаты", chats.map { (i, title) ->
            Glass.Row(R.drawable.ic_folder, title, null) {
                history = Brain.openChat(this, i)
                showChat()
            }
        })
    }

    private fun showMenu() {
        val pillOn = Brain.prefs(this).getBoolean("pill_on", true)
        Glass.showSections(this, "Барсик", listOf(
            "Чаты" to listOf(
                Glass.Row(R.drawable.ic_plus, "Новый чат", "Текущий сохранится в «Мои чаты»") {
                    history = Brain.newChat(this)
                    showChat()
                },
                Glass.Row(R.drawable.ic_folder, "Мои чаты", "Сохранённые разговоры") { showChatList() }
            ),
            "ИИ" to listOf(
                Glass.Row(R.drawable.ic_key, "Сервис ИИ и ключ", "Выбрать нейросеть") { pickProvider() }
            ),
            "Голос и экран" to listOf(
                Glass.Row(R.drawable.ic_touch, "Управление экраном", "Включить в специальных возможностях") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Banner.show(this, "Найди «Барсик» в списке и включи")
                },
                Glass.Row(R.drawable.ic_mic, "Слушать «Барсик»",
                    if (WakeService.running) "Сейчас включено" else "Выключено") {
                    if (WakeService.running) {
                        stopService(Intent(this, WakeService::class.java))
                        Banner.show(this, "Слушание выключено")
                    } else {
                        startForegroundService(Intent(this, WakeService::class.java))
                        Banner.show(this, "Скажи «Барсик» — я услышу")
                    }
                },
                Glass.Row(R.drawable.ic_volume, "Голос и стиль", "Стиль, озвучка, скорость") { showVoiceSettings() },
                Glass.Row(R.drawable.ic_download, "Голосовое дополнение", "Языки распознавания речи") { showVoicePack() }
            ),
            "Прочее" to listOf(
                Glass.Row(if (pillOn) R.drawable.ic_visibility_off else R.drawable.ic_star,
                    if (pillOn) "Скрыть Барсика" else "Показать Барсика",
                    "Плавающая кнопка на экране") { toggleOverlay() },
                Glass.Row(R.drawable.ic_trash, "Очистить память", "Забыть прошлый разговор") {
                    Brain.clearHistory(this)
                    while (history.length() > 0) history.remove(0)
                    msgs.removeAllViews()
                    addBubble("Память очищена. Мяу!", false)
                },
                Glass.Row(R.drawable.ic_refresh, "Проверить обновления", "Скачать новую версию") { checkUpdate(true) }
            )
        ))
    }

    private fun showVoiceSettings() {
        val p = Brain.prefs(this)
        val speeds = arrayOf("Медленно" to 0.85f, "Норма" to 1.0f, "Быстро" to 1.2f)
        val jarvis = Brain.persona(this) == "jarvis"
        val voiceOn = p.getBoolean("voice_on", true)
        val speedName = speeds.firstOrNull { it.second == p.getFloat("speed", 1.0f) }?.first ?: "Норма"
        Glass.showSections(this, "Голос и стиль", listOf(
            "Голос" to listOf(
                Glass.Row(R.drawable.ic_star, "Стиль", if (jarvis) "Джарвис" else "Кот-пацан") {
                    p.edit().putString("persona", if (jarvis) "cat" else "jarvis").apply()
                    BarsikService.instance?.applyVoiceSettings()
                    Banner.show(this, "Стиль изменён")
                },
                Glass.Row(R.drawable.ic_volume, "Озвучка ответов", if (voiceOn) "Включена" else "Выключена") {
                    p.edit().putBoolean("voice_on", !voiceOn).apply()
                    BarsikService.instance?.applyVoiceSettings()
                },
                Glass.Row(R.drawable.ic_refresh, "Скорость голоса", speedName) {
                    Glass.show(this, "Скорость голоса", speeds.map { s ->
                        Glass.Row(R.drawable.ic_refresh, s.first, null) {
                            p.edit().putFloat("speed", s.second).apply()
                            BarsikService.instance?.applyVoiceSettings()
                        }
                    })
                }
            )
        ))
    }

    private fun toggleOverlay() {
        val p = Brain.prefs(this)
        val on = !p.getBoolean("pill_on", true)
        p.edit().putBoolean("pill_on", on).apply()
        val svc = BarsikService.instance
        if (svc != null) {
            svc.setPillVisible(on)
        } else {
            Banner.show(this, "Сначала включи управление экраном")
        }
    }

    private val presets = listOf(
        Triple("Pollinations (без ключа)", Brain.DEFAULT_URL, Brain.DEFAULT_MODEL),
        Triple("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest"),
        Triple("Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
        Triple("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
        Triple("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free"),
        Triple("GigaChat (Сбер, из России без VPN)", "gigachat", "GigaChat"),
        Triple("Свой вариант", "", "")
    )

    private fun pickProvider() {
        Glass.show(this, "Какой ИИ использовать?", presets.map { p ->
            Glass.Row(R.drawable.ic_key, p.first, null) {
                showSettings(
                    p.second.ifEmpty { Brain.aiUrl(this) },
                    p.third.ifEmpty { Brain.aiModel(this) }
                )
            }
        })
    }

    // Показывает короткое имя текущей модели в нижней панели (например, «llama-3.3-70b»)
    private fun refreshModelChip() {
        val id = Brain.aiModel(this).substringAfterLast('/')
        // Короткое понятное имя вместо длинного идентификатора модели
        val label = when {
            id.contains("llama-3.3", true) -> "Llama 3.3"
            id.contains("llama", true) -> "Llama"
            else -> id.substringBefore('-').take(12)
        }
        findViewById<TextView>(R.id.modelChip).text = label.ifBlank { "Модель" }
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
                refreshModelChip()
                Banner.show(this, "Сохранено")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
