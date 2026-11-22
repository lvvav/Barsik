package com.barsik.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Всё, что связано с ИИ и действиями: память, запрос к сервису, команды телефона
object Brain {
    private const val MAX_HISTORY = 40
    const val DEFAULT_URL = "https://text.pollinations.ai/openai"
    const val DEFAULT_MODEL = "openai"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("barsik", Context.MODE_PRIVATE)

    // Язык системного распознавания берётся из настроек телефона: отдельный выбор не нужен
    fun voiceLocale(ctx: Context) = java.util.Locale.getDefault().toLanguageTag()

    // Один раз сбрасываем сохранённый адрес, модель и ключ, которые выбирались вручную через 🔑:
    // иначе они перекрывают встроенную нейросеть
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("migrated_builtin", false)) {
            p.edit().remove("url").remove("model").remove("key").putBoolean("pill_on", true).putBoolean("migrated_builtin", true).apply()
        }
    }

    fun apiKey(ctx: Context): String {
        val saved = prefs(ctx).getString("key", "") ?: ""
        return if (saved.isNotBlank()) saved else BuildConfig.AI_KEY
    }

    // Если в сборке задан адрес встроенной нейросети, используем её, иначе бесплатный Pollinations
    private val embeddedOpenRouter get() = BuildConfig.AI_URL.isNotBlank()

    fun aiUrl(ctx: Context) = prefs(ctx).getString("url", null)?.takeIf { it.isNotBlank() }
        ?: if (embeddedOpenRouter) BuildConfig.AI_URL else DEFAULT_URL

    fun aiModel(ctx: Context) = prefs(ctx).getString("model", null)?.takeIf { it.isNotBlank() }
        ?: if (embeddedOpenRouter) BuildConfig.AI_MODEL else DEFAULT_MODEL

    // ---------- память (сохраняется между запусками) ----------

    fun loadHistory(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString("history", "[]")) } catch (e: Exception) { JSONArray() }

    fun saveHistory(ctx: Context, h: JSONArray) {
        while (h.length() > MAX_HISTORY) h.remove(0)
        prefs(ctx).edit().putString("history", h.toString()).apply()
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove("history").apply()
    }

    // ---------- несколько чатов: текущий и архивные ----------

    private fun chatsArr(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString("chats", "[]")) } catch (e: Exception) { JSONArray() }

    // Заголовок чата — первые слова первого вопроса пользователя
    private fun titleOf(h: JSONArray): String {
        for (i in 0 until h.length()) {
            val m = h.getJSONObject(i)
            if (m.optString("role") == "user") return m.optString("content").take(40)
        }
        return "Пустой чат"
    }

    // Архивные чаты: индекс и заголовок
    fun savedChats(ctx: Context): List<Pair<Int, String>> {
        val a = chatsArr(ctx)
        return (0 until a.length()).map { it to a.getJSONObject(it).optString("title") }
    }

    // Текущий чат уходит в архив, открывается пустой
    fun newChat(ctx: Context): JSONArray {
        archiveCurrent(ctx)
        clearHistory(ctx)
        return JSONArray()
    }

    // Открыть архивный чат: текущий уходит в архив, выбранный становится текущим
    fun openChat(ctx: Context, index: Int): JSONArray {
        val a = chatsArr(ctx)
        val chosen = JSONArray(a.getJSONObject(index).optString("history", "[]"))
        val rest = JSONArray()
        for (i in 0 until a.length()) if (i != index) rest.put(a.get(i))
        prefs(ctx).edit().putString("chats", rest.toString()).apply()
        archiveCurrent(ctx)
        prefs(ctx).edit().putString("history", chosen.toString()).apply()
        return chosen
    }

    private fun archiveCurrent(ctx: Context) {
        val cur = loadHistory(ctx)
        if (cur.length() == 0) return
        val a = chatsArr(ctx)
        a.put(JSONObject().put("title", titleOf(cur)).put("history", cur.toString()))
        while (a.length() > 30) a.remove(0)
        prefs(ctx).edit().putString("chats", a.toString()).apply()
    }

    // ---------- разговор ----------

    fun chat(ctx: Context, h: JSONArray, userText: String): JSONObject {
        h.put(JSONObject().put("role", "user").put("content", userText))
        try {
            var res = parseReply(post(ctx, buildMessages(ctx, h, null)))
            val sources = JSONArray()

            // Модель сама решила искать: делаем поиск и отдаём результаты ей же на финальный ответ
            val query = res.optJSONArray("actions")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "search" }?.optString("arg")
            }
            if (!query.isNullOrBlank() && BuildConfig.AI_URL.isNotBlank()) {
                val found = webSearch(query)
                val lines = StringBuilder()
                for (i in 0 until found.length()) {
                    val r = found.getJSONObject(i)
                    sources.put(JSONObject().put("title", r.optString("title")).put("url", r.optString("url")))
                    lines.append("[${i + 1}] ${r.optString("title")} (${r.optString("url")}): ${r.optString("content")}\n")
                }
                val note = if (found.length() == 0) "По запросу «$query» ничего не найдено."
                else "Результаты поиска по запросу «$query»:\n$lines"
                res = parseReply(post(ctx, buildMessages(ctx, h,
                    note + "\nОтветь на последний вопрос пользователя, опираясь на результаты. " +
                        "Если в результатах нет ответа, так и скажи. Поле actions оставь пустым. Ссылки в тексте не перечисляй.")))
            }

            val reply = res.optString("reply", "Мяу?")
            // Сохраняем ответ вместе с источниками, чтобы они показывались и после перезапуска
            val stored = JSONObject().put("reply", reply).put("sources", sources)
            h.put(JSONObject().put("role", "assistant").put("content", stored.toString()))
            saveHistory(ctx, h)
            return JSONObject()
                .put("reply", reply)
                .put("actions", res.optJSONArray("actions") ?: JSONArray())
                .put("sources", sources)
        } catch (e: Exception) {
            h.remove(h.length() - 1)
            throw e
        }
    }

    // Системное сообщение + история; extra — временное сообщение, в историю не попадает
    private fun buildMessages(ctx: Context, h: JSONArray, extra: String?): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(ctx)))
        for (i in 0 until h.length()) messages.put(h.getJSONObject(i))
        if (extra != null) messages.put(JSONObject().put("role", "user").put("content", extra))
        return messages
    }

    // Поиск через прокси Supabase (Tavily): возвращает массив {title, url, content}
    private fun webSearch(query: String): JSONArray {
        return try {
            val c = URL(BuildConfig.AI_URL).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("action", "search").put("query", query).toString().toByteArray()) }
            if (c.responseCode !in 200..299) JSONArray()
            else JSONObject(readBody(c)).optJSONArray("results") ?: JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
    }

    // Один запрос к сервису в формате OpenAI (chat/completions)
    private fun readBody(c: HttpURLConnection): String {
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return s?.bufferedReader()?.use { it.readText() } ?: ""
    }

    // ---------- GigaChat (Сбер): токен живёт 30 минут, кэшируем его ----------

    private var gigaToken: String? = null
    private var gigaExpires = 0L

    private fun gigaAccessToken(ctx: Context): String {
        val now = System.currentTimeMillis()
        val cached = gigaToken
        if (cached != null && now < gigaExpires - 60_000) return cached
        val c = URL("https://ngw.devices.sberbank.ru:9443/api/v2/oauth").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("RqUID", java.util.UUID.randomUUID().toString())
        c.setRequestProperty("Authorization", "Basic " + apiKey(ctx))
        c.outputStream.use { it.write("scope=GIGACHAT_API_PERS".toByteArray()) }
        val code = c.responseCode
        val raw = readBody(c)
        if (code !in 200..299) throw RuntimeException("GigaChat: ключ не подошёл (нажми 🔑)")
        val j = JSONObject(raw)
        gigaToken = j.getString("access_token")
        gigaExpires = j.optLong("expires_at", now + 25 * 60_000)
        return gigaToken!!
    }

    private fun gigaPost(ctx: Context, messages: JSONArray): String {
        val token = gigaAccessToken(ctx)
        val body = JSONObject().put("model", "GigaChat").put("messages", messages)
        val c = URL("https://gigachat.devices.sberbank.ru/api/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val raw = readBody(c)
        if (code !in 200..299) throw RuntimeException(if (code == 401) "токен не принят, попробуй ещё раз" else "GigaChat HTTP $code")
        return JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    }

    fun post(ctx: Context, messages: JSONArray): String {
        if (aiUrl(ctx) == "gigachat") return gigaPost(ctx, messages)
        val body = JSONObject().put("model", aiModel(ctx)).put("messages", messages)
        val c = URL(aiUrl(ctx)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (apiKey(ctx).isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + apiKey(ctx))
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw RuntimeException(
                when (code) {
                    401, 403 -> "ключ не подошёл (нажми 🔑)"
                    400, 404 -> "адрес или модель не подошли (нажми 🔑)"
                    402 -> "на сервисе нужны кредиты"
                    429 -> "лимит запросов, подожди минуту"
                    else -> "HTTP $code"
                }
            )
        }
        return JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    }

    // Модель может вернуть JSON или просто текст
    fun parseReply(t: String): JSONObject {
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try { return JSONObject(t.substring(s, e + 1)) } catch (_: Exception) {}
        }
        return JSONObject().put("reply", t.trim().ifEmpty { "Мяу?" })
    }

    // Стиль ответов: "cat" (Кот-пацан) или "jarvis" (Джарвис)
    fun persona(ctx: Context) = prefs(ctx).getString("persona", "cat") ?: "cat"

    fun systemPrompt(ctx: Context): String {
        val apps = installedApps(ctx).joinToString(", ") { it.first }
        // Модель не знает текущую дату сама, поэтому сообщаем её при каждом запросе
        val today = java.text.SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", java.util.Locale("ru")).format(java.util.Date())
        val about = """Сейчас: $today (местное время). Опирайся на эту дату, а не на свои обучающие данные, когда речь о текущем годе, дне и событиях.
О тебе:
- Тебя зовут Барсик, ты ИИ-ассистент приложения «Барсик».
- Языковая модель, на которой ты работаешь: ${aiModel(ctx)}.
- Тебя создал и тобой владеет Данила Зотова (GitHub: lvvav).
- Если спрашивают, кто ты, кто тебя сделал или на какой модели ты работаешь, отвечай этими данными.
- Если пользователь пишет по-английски, отвечай по-английски.
- Ты умеешь: открывать приложения и сайты, искать в интернете, ставить музыку и видео на паузу, включать фонарик, ставить таймер и будильник, управлять экраном в других приложениях, ставить напоминания на любое время (секунды, минуты, часы, дни, годы)."""
        val style = if (persona(ctx) == "jarvis")
            "Ты — Джарвис, спокойный, вежливый и точный ИИ-ассистент на телефоне. Отвечай по-русски, без 'мяу' и без кошачьих шуток."
        else
            "Ты — Барсик, дружелюбный серый кот-пацан и голосовой помощник на телефоне. Отвечай по-русски, коротко, с лёгким юмором (иногда \"мяу\")."
        return """
$style
$about
Ответ ВСЕГДА строго JSON вида:
{"reply":"текст пользователю","actions":[{"type":"...","arg":"..."}]}
Доступные type:
- "search": arg = поисковый запрос. Используй ТОЛЬКО когда нужны свежие сведения (новости, курсы, погода, события, кто сейчас занимает пост) или факты, которых ты не знаешь. На обычные вопросы не ищи. Результаты придут следующим сообщением, а ты ответишь по ним и actions оставишь пустым.
- "open_url": arg = полный URL
- "reminder": arg = "секунды|текст напоминания". Используй, когда просят напомнить что-то через время (от секунд до лет). Секунды считай сам от текущего момента: 1 минута = 60, 1 час = 3600, 1 день = 86400, 1 год = 31536000. Пример: "напомни через 2 минуты купить хлеб" → arg "120|купить хлеб".
- "open_app": arg = точное название приложения из списка установленных
- "media_pause", "media_play", "media_stop", "media_next", "media_prev": управление музыкой и видео (arg пустой)
- "volume_up", "volume_down": громкость
- "timer": arg = число секунд
- "alarm": arg = время в формате ЧЧ:ММ (будильник)
- "flashlight_on", "flashlight_off": фонарик
- "settings": открыть настройки телефона
- "music": arg = название песни и/или исполнителя. Используй ВСЕГДА, когда просят включить песню или музыку (не используй search для музыки). Приложение откроет Я.Музыку или Яндекс Музыку, найдёт и включит песню.
- "screen_task": arg = задача для управления экраном в других приложениях (нажать, ввести текст, открыть приложение и т.п.). Используй, когда пользователь просит что-то сделать внутри другого приложения.
Если действий не нужно — "actions":[]. Не выдумывай приложения, которых нет в списке.
Установленные приложения: $apps
""".trimIndent()
    }

    fun installedApps(ctx: Context): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = ctx.packageManager
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
    }

    // ---------- действия на телефоне ----------

    fun execute(ctx: Context, type: String, arg: String) {
        try {
            when (type) {
                "open_url" -> open(ctx, arg)
                "reminder" -> {
                    // arg = "секунды|текст": модель считает секунды от текущего момента
                    val secs = arg.substringBefore('|').trim().toDoubleOrNull()?.toLong() ?: 60L
                    Reminders.add(ctx, secs, arg.substringAfter('|', "").trim().ifEmpty { "Напоминание" })
                }
                "open_app" -> openApp(ctx, arg)
                "media_pause" -> media(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
                "media_play" -> media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
                "media_stop" -> media(ctx, KeyEvent.KEYCODE_MEDIA_STOP)
                "media_next" -> media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
                "media_prev" -> media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "volume_up" -> volume(ctx, AudioManager.ADJUST_RAISE)
                "volume_down" -> volume(ctx, AudioManager.ADJUST_LOWER)
                "flashlight_on" -> torch(ctx, true)
                "flashlight_off" -> torch(ctx, false)
                "alarm" -> {
                    val (h, m) = arg.split(":").let {
                        (it.getOrNull(0)?.toIntOrNull() ?: 7) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
                    }
                    ctx.startActivity(
                        Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, h)
                            .putExtra(AlarmClock.EXTRA_MINUTES, m)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                "settings" -> ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "timer" -> ctx.startActivity(
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, arg.toDoubleOrNull()?.toInt() ?: 60)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Exception) {
        }
    }

    // Задача для агента экрана: найти и включить песню в Я.Музыке
    fun musicGoal(query: String) =
        "Открой приложение Я.Музыка (или Яндекс Музыка), нажми поиск, введи «$query», выбери подходящий трек и нажми play. Если музыка уже играет по этому запросу — ничего не делай."

    fun openApp(ctx: Context, name: String): Boolean {
        val apps = installedApps(ctx)
        val hit = apps.firstOrNull { it.first.equals(name, true) }
            ?: apps.firstOrNull { it.first.contains(name, true) }
            ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(hit.second) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun open(ctx: Context, url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun media(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun torch(ctx: Context, on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        for (id in cm.cameraIdList) {
            val flash = cm.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (flash) { cm.setTorchMode(id, on); return }
        }
    }

    private fun volume(ctx: Context, dir: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }
}
