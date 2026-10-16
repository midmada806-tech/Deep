package eu.kanade.tachiyomi.ui.player.utils.subtitle

import android.util.LruCache
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Translates subtitle lines with the engine picked in settings:
 *  - Google Translate public endpoint (free, line by line)
 *  - PlayTranslate's shared translation waterfall (same engines/keys/offline models as the screen translator)
 *  - Claude or Gemini with the user's own API key (batched, with context, style notes and a
 *    consistent glossary of names). An engine without a key falls back to Google.
 * Results are cached per engine and target language.
 */
class SubtitleTranslator(
    baseClient: OkHttpClient = Injekt.get<NetworkHelper>().client,
) {
    private val client = baseClient.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()
    private val prefs: SubtitlePreferences = Injekt.get()
    private val cache = LruCache<String, String>(2000)

    /** Engine actually used right now (an AI engine without a key means Google). */
    val engine: String
        get() = when (prefs.translateEngine().get()) {
            "claude" -> if (prefs.translateClaudeKey().get().isNotBlank()) "claude" else "google"
            "gemini" -> if (prefs.translateGeminiKey().get().isNotBlank()) "gemini" else "google"
            // PlayTranslate's shared waterfall (ML Kit / Bergamot / DeepL / Gemini / OpenAI / on-device LLMs)
            "playtranslate" -> if (playTranslateReady()) "playtranslate" else "google"
            else -> "google"
        }

    private fun playTranslateReady(): Boolean =
        runCatching { com.playtranslate.translation.TranslationBackendRegistry.orderedBackends().isNotEmpty() }
            .getOrDefault(false)

    /** Source language for PlayTranslate's backends: the one chosen in PlayTranslate (subtitles carry no reliable tag). */
    private fun playTranslateSource(): String = runCatching {
        com.playtranslate.Prefs(Injekt.get<android.app.Application>()).sourceLangId.code
    }.getOrDefault("ja")

    private suspend fun playTranslateMany(lines: List<String>, target: String): List<String?> {
        val source = playTranslateSource()
        val out = arrayOfNulls<String>(lines.size)
        lines.chunked(PT_BATCH).forEachIndexed { c, chunk ->
            val res = runCatching {
                com.playtranslate.translation.TranslationBackendRegistry.translateBatch(chunk, source, target)
            }.getOrNull()
            if (res != null && res.size == chunk.size) {
                chunk.indices.forEach { n -> out[c * PT_BATCH + n] = res[n].text.trim().takeIf { it.isNotEmpty() } }
            } else {
                // waterfall exhausted: this chunk goes through Google rather than being lost
                googleMany(chunk, target).forEachIndexed { n, r -> out[c * PT_BATCH + n] = r }
            }
        }
        return out.toList()
    }

    fun cached(text: String, target: String): String? = cache.get(key(text, target))

    /**
     * One live line. AI engines get the previous lines as [context] so the wording stays consistent.
     */
    suspend fun translate(text: String, target: String, context: List<String> = emptyList()): String? {
        cache.get(key(text, target))?.let { return it }
        return translateBatch(listOf(text), target, context, onProgress = {}).firstOrNull()
    }

    /**
     * Whole file or a batch. Returns one entry per input line, null where a line could not be translated.
     * [onProgress] gets (done, total).
     */
    suspend fun translateBatch(
        lines: List<String>,
        target: String,
        context: List<String> = emptyList(),
        onProgress: (Int, Int) -> Unit,
    ): List<String?> {
        val out = arrayOfNulls<String>(lines.size)
        val todo = ArrayList<Int>()
        lines.forEachIndexed { i, l ->
            val c = cache.get(key(l, target))
            if (c != null) out[i] = c else todo += i
        }
        var done = lines.size - todo.size
        onProgress(done, lines.size)
        if (todo.isEmpty()) return out.toList()

        when (val e = engine) {
            "claude", "gemini" -> {
                var ctx = context
                for (chunk in todo.chunked(AI_BATCH)) {
                    val src = chunk.map { lines[it] }
                    val res = runCatching { ai(e, src, target, ctx) }.getOrNull()
                    if (res != null && res.size == src.size) {
                        chunk.forEachIndexed { n, idx -> out[idx] = res[n] }
                    } else {
                        // wrong count / error: do this chunk through Google instead of losing it
                        googleMany(chunk.map { lines[it] }, target).forEachIndexed { n, r -> out[chunk[n]] = r }
                    }
                    ctx = src.takeLast(3)
                    done += chunk.size
                    onProgress(done, lines.size)
                }
            }
            "playtranslate" -> {
                var finished = 0
                for (chunk in todo.chunked(PT_BATCH)) {
                    val res = playTranslateMany(chunk.map { lines[it] }, target)
                    chunk.forEachIndexed { n, idx -> out[idx] = res[n] }
                    finished += chunk.size
                    onProgress(done + finished, lines.size)
                }
            }
            else -> {
                val results = googleMany(todo.map { lines[it] }, target) { d -> onProgress(done + d, lines.size) }
                todo.forEachIndexed { n, idx -> out[idx] = results[n] }
            }
        }
        lines.forEachIndexed { i, l -> out[i]?.let { cache.put(key(l, target), it) } }
        return out.toList()
    }

    // ------------------------------------------------------------------ Google

    private suspend fun googleMany(lines: List<String>, target: String, onDone: (Int) -> Unit = {}): List<String?> =
        coroutineScope {
            val gate = Semaphore(GOOGLE_PARALLEL)
            var finished = 0
            lines.map { line ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        var r: String? = null
                        for (attempt in 0..2) {
                            r = google(line, target)
                            if (r != null) break
                            delay(400L * (attempt + 1))
                        }
                        synchronized(this@SubtitleTranslator) { finished++; onDone(finished) }
                        r
                    }
                }
            }.awaitAll()
        }

    private fun google(text: String, target: String): String? = runCatching {
        val url = GOOGLE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            // [[["translated","original",...],["next","next original",...]],null,"ja"]
            Json.parseToJsonElement(response.body.string()).jsonArray[0].jsonArray
                .joinToString("") { it.jsonArray[0].jsonPrimitive.contentOrNull.orEmpty() }
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ AI engines

    private fun systemPrompt(target: String): String {
        val lang = "${LANGUAGES[target] ?: target} ($target)"
        val style = prefs.translateStyle().get().trim()
        return buildString {
            append("You translate subtitles of anime and videos into $lang. ")
            append("Write short, natural lines that read well on screen. ")
            append("Keep character names, honorifics and terms consistent across the whole episode. ")
            append("Never merge, split, reorder or drop lines. Do not add notes or explanations. ")
            append("Reply with a JSON array of strings only, no code fences.")
            if (style.isNotEmpty()) append("\nStyle notes from the viewer: $style")
        }
    }

    private fun userPrompt(lines: List<String>, context: List<String>): String = buildString {
        if (context.isNotEmpty()) {
            append("Earlier lines for context only (do NOT translate or return them):\n")
            context.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("Translate these ${lines.size} lines. Return a JSON array with exactly ${lines.size} strings, in order.\n")
        lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
    }

    private fun ai(engine: String, lines: List<String>, target: String, context: List<String>): List<String>? {
        val system = systemPrompt(target)
        val user = userPrompt(lines, context)
        val json = "application/json".toMediaType()
        val request = if (engine == "claude") {
            val body = buildJsonObject {
                put("model", prefs.translateClaudeModel().get().ifBlank { "claude-haiku-5-5" })
                put("max_tokens", 4096)
                put("system", system)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        },
                    )
                }
            }
            Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", prefs.translateClaudeKey().get().trim())
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody(json))
                .build()
        } else {
            val model = prefs.translateGeminiModel().get().ifBlank { "gemini-2.5-flash" }
            val body = buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
                }
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", user) }) }
                        },
                    )
                }
                putJsonObject("generationConfig") {
                    put("temperature", 0.3)
                    put("responseMimeType", "application/json")
                }
            }
            Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", prefs.translateGeminiKey().get().trim())
                .post(body.toString().toRequestBody(json))
                .build()
        }
        val reply = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body.string()
        }
        val root = Json.parseToJsonElement(reply).jsonObject
        val text = if (engine == "claude") {
            root["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        } else {
            root["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!
                .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        }
        return parseArray(text)
    }

    /** The model should answer with a bare JSON array; tolerate fences or a sentence around it. */
    private fun parseArray(text: String): List<String>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val arr: JsonArray = Json.parseToJsonElement(text.substring(start, end + 1)).jsonArray
        return arr.map { it.jsonPrimitive.content.trim() }
    }

    private fun key(text: String, target: String) = "$engine|$target|$text"

    companion object {
        private const val GOOGLE_URL = "https://translate.googleapis.com/translate_a/single"
        private const val GOOGLE_PARALLEL = 4
        private const val AI_BATCH = 40
        private const val PT_BATCH = 20

        /** Target languages offered in settings (Google Translate codes). */
        val LANGUAGES: Map<String, String> = linkedMapOf(
            "th" to "ไทย",
            "en" to "English",
            "ja" to "日本語",
            "ko" to "한국어",
            "zh-CN" to "中文 (简体)",
            "zh-TW" to "中文 (繁體)",
            "id" to "Indonesia",
            "ms" to "Melayu",
            "vi" to "Tiếng Việt",
            "es" to "Español",
            "pt" to "Português",
            "fr" to "Français",
            "de" to "Deutsch",
            "ru" to "Русский",
            "ar" to "العربية",
            "hi" to "हिन्दी",
        )
    }
}
