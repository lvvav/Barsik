package com.barsik.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

// Работает в фоне: плавающий Барсик поверх экрана, голосовые команды и управление другими приложениями
class BarsikService : AccessibilityService() {

    companion object {
        var instance: BarsikService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var label: TextView? = null
    private var overlay: View? = null
    private var pendingAct: JSONObject? = null
    private var pendingNodes: List<AccessibilityNodeInfo> = emptyList()
    private val hideLabel = Runnable { label?.visibility = View.GONE }

    private val screenPrompt = """Ты управляешь телефоном Android по описанию экрана. Каждый ход получаешь список элементов экрана с номерами [n]. Отвечай ТОЛЬКО JSON вида:
{"say":"короткая фраза пользователю","act":{"type":"click|type|scroll_down|scroll_up|back|home|open_app|done","i":0,"text":"","risky":false}}
- click: нажать элемент i. type: ввести text в поле i. open_app: открыть приложение с именем text. back, home, scroll_down, scroll_up: без i.
- done: задача выполнена или невозможна; в say кратко скажи итог.
- risky=true ставь, если действие отправляет сообщение, покупает, платит или удаляет. В этом случае ничего не делай, а в say спроси пользователя.
Используй только номера, которые видишь. Один шаг за ответ."""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun wm() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
        showOverlay()
    }

    override fun onDestroy() {
        instance = null
        overlay?.let { wm().removeView(it) }
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- плавающий Барсик ----------

    private fun showOverlay() {
        if (overlay != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundResource(R.drawable.bg_bubble_bot)
            maxWidth = dp(260)
            visibility = View.GONE
        }
        val av = ImageView(this).apply {
            setImageResource(R.drawable.barsik_avatar)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
            setOnClickListener { listen() }
        }
        box.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        box.addView(av, LinearLayout.LayoutParams(dp(64), dp(64)))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = dp(12)
            y = dp(140)
        }
        wm().addView(box, lp)
        overlay = box
        label = tv
    }

    private fun say(msg: String) {
        main.post {
            label?.apply {
                text = msg
                visibility = View.VISIBLE
            }
            main.removeCallbacks(hideLabel)
            main.postDelayed(hideLabel, 6000)
        }
        tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "barsik")
    }

    // ---------- голос ----------

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) handleVoice(text) else say("Не расслышал")
        }
        override fun onError(error: Int) { say("Не расслышал, попробуй ещё раз") }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Голос на этом телефоне недоступен")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        })
        say("Слушаю…")
    }

    private fun handleVoice(raw: String) {
        val text = raw.trim().replaceFirst(Regex("^барсик[,! ]*", RegexOption.IGNORE_CASE), "").trim()
        worker.execute {
            try {
                val act = pendingAct
                if (act != null) {
                    pendingAct = null
                    if (text.lowercase().startsWith("да")) {
                        perform(act, pendingNodes)
                        say("Готово")
                    } else {
                        say("Ок, отменил")
                    }
                    return@execute
                }
                val h = Brain.loadHistory(this)
                val res = Brain.chat(this, h, text)
                say(res.optString("reply", "Мяу!"))
                runActions(res.optJSONArray("actions"))
            } catch (e: Exception) {
                say("Мяу... ${e.message}")
            }
        }
    }

    private fun runActions(arr: JSONArray?) {
        if (arr == null) return
        for (n in 0 until arr.length()) {
            val a = arr.getJSONObject(n)
            val type = a.optString("type")
            val arg = a.optString("arg")
            if (type == "screen_task") runGoal(arg)
            else main.post { Brain.execute(this, type, arg) }
        }
    }

    // Вызывается из приложения (кнопка в чате)
    fun runGoalAsync(goal: String) {
        worker.execute { runGoal(goal) }
    }

    // ---------- управление экраном ----------

    private fun runGoal(goal: String) {
        val taken = StringBuilder()
        for (step in 0 until 12) {
            val screen = dumpScreen()
            val user = "Уже сделано: ${if (taken.isEmpty()) "ничего" else taken.toString()}\nЭкран:\n$screen"
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", screenPrompt + "\nЗадача: " + goal))
                .put(JSONObject().put("role", "user").put("content", user))
            val r = Brain.parseReply(Brain.post(this, messages))
            val act = r.optJSONObject("act") ?: JSONObject().put("type", "done")
            val phrase = r.optString("say")
            if (phrase.isNotBlank()) say(phrase)

            val type = act.optString("type")
            if (type == "done") return
            if (act.optBoolean("risky")) {
                pendingAct = act
                pendingNodes = ArrayList(nodes)
                say("Подтверди: скажи «да» или «нет»")
                return
            }
            perform(act, nodes)
            taken.append(type).append("(").append(act.optString("text")).append(") ")
            Thread.sleep(1200)
        }
        say("Задача заняла слишком много шагов")
    }

    private fun perform(act: JSONObject, list: List<AccessibilityNodeInfo>): Boolean {
        val node = list.getOrNull(act.optInt("i", -1))
        return when (act.optString("type")) {
            "click" -> node?.let { clickable(it)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
            "type" -> node?.let {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, act.optString("text"))
                }
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } ?: false
            "scroll_down" -> scrollable(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            "scroll_up" -> scrollable(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "open_app" -> Brain.openApp(this, act.optString("text"))
            else -> false
        }
    }

    private fun clickable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n
        while (c != null && !c.isClickable) c = c.parent
        return c
    }

    private fun scrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            scrollable(n.getChild(i))?.let { return it }
        }
        return null
    }

    // Текстовое описание экрана; номера ссылаются на список nodes
    private fun dumpScreen(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "(экран недоступен)"
        val out = StringBuilder()
        walk(root, out)
        return if (out.isEmpty()) "(на экране пусто)" else out.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, out: StringBuilder) {
        if (n == null || out.length > 6000) return
        val label = (n.text ?: n.contentDescription ?: "").toString().trim()
        when {
            n.isEditable -> {
                nodes.add(n)
                out.append("[${nodes.size - 1}] поле: $label\n")
            }
            n.isClickable -> {
                nodes.add(n)
                out.append("[${nodes.size - 1}] кнопка: $label\n")
            }
            label.isNotEmpty() -> out.append("- $label\n")
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }
}
