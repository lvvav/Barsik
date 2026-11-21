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
import java.net.URLEncoder

// Всё, что связано с ИИ и действиями: память, запрос к сервису, команды телефона
object Brain {
    private const val MAX_HISTORY = 40
    const val DEFAULT_URL = "https://text.pollinations.ai/openai"
    const val DEFAULT_MODEL = "openai"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("barsik", Context.MODE_PRIVATE)

    // Язык системного распознавания берётся из настроек телефона: отдельный выбор не нужен
    fun voiceLocale(ctx: Context) = java.util.Locale.getDefault().toLanguageTag()

    fun apiKey(ctx: Context): String {
        val saved = prefs(ctx).getString("key", "") ?: ""
        return if (saved.isNotBlank()) saved else BuildConfig.AI_KEY
    }

    // Если ключ OpenRouter вшит в сборку, по умолчанию используем его, иначе бесплатный Pollinations
    private val embeddedOpenRouter get() = BuildConfig.AI_KEY.isNotBlank()

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

    // ---------- разговор ----------

    fun chat(ctx: Context, h: JSONArray, userText: String): JSONObject {
        h.put(JSONObject().put("role", "user").put("content", userText))
        try {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(ctx)))
            for (i in 0 until h.length()) messages.put(h.getJSONObject(i))
            val text = post(ctx, messages)
            h.put(JSONObject().put("role", "assistant").put("content", text))
            saveHistory(ctx, h)
            return parseReply(text)
        } catch (e: Exception) {
            h.remove(h.length() - 1)
            throw e
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
        val about = """О тебе:
- Тебя зовут Барсик, ты ИИ-ассистент приложения «Барсик».
- Языковая модель, на которой ты работаешь: ${aiModel(ctx)}.
- Тебя создал и тобой владеет Данила Зотова (GitHub: lvvav).
- Если спрашивают, кто ты, кто тебя сделал или на какой модели ты работаешь, отвечай этими данными.
- Если пользователь пишет по-английски, отвечай по-английски.
- Ты умеешь: открывать приложения и сайты, искать в интернете, ставить музыку и видео на паузу, включать фонарик, ставить таймер и будильник, управлять экраном в других приложениях."""
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
- "search": arg = поисковый запрос
- "open_url": arg = полный URL
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
                "search" -> open(ctx, "https://www.google.com/search?q=" + URLEncoder.encode(arg, "UTF-8"))
                "open_url" -> open(ctx, arg)
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
