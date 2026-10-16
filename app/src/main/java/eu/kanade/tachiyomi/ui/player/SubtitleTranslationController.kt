package eu.kanade.tachiyomi.ui.player

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.utils.subtitle.SubtitleFileTranslator
import eu.kanade.tachiyomi.ui.player.utils.subtitle.SubtitleTranslator
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Subtitle translation in the player. Two paths share the same settings:
 *
 *  1. Whole file (external text subtitle tracks, if enabled): the file is downloaded, translated once
 *     (batched, with the chosen engine), saved to the cache and added to mpv as a normal subtitle
 *     track. "Replace" mode selects it; "both" mode puts it in mpv's secondary subtitle slot.
 *     Styling of ASS files is kept and nothing depends on the network while watching.
 *  2. Live line by line (embedded tracks, or while the file is still being translated): mpv reports the
 *     current line through `sub-text`, it is translated (cached) and drawn in [view] over the video.
 */
class SubtitleTranslationController(
    private val activity: Activity,
    private val view: TextView,
) {

    private val prefs: SubtitlePreferences by injectLazy()
    private val translator = SubtitleTranslator()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var liveJob: Job? = null
    private var fileJob: Job? = null
    private var debounceJob: Job? = null
    private var fileKey: String? = null
    private val recentLines = ArrayDeque<String>()

    private val dir: File by lazy { File(activity.cacheDir, "translated_subs").also { it.mkdirs() } }

    /** A translated track is on screen (selected or secondary): the live overlay stays hidden. */
    private var translatedReady = false
    private var secondaryByUs = false

    private val _enabled = MutableStateFlow(prefs.translateEnabled().get())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val replace: Boolean get() = prefs.translateMode().get() == 0

    /** Call when the player is ready and whenever a setting may have changed. */
    fun applySettings() {
        _enabled.value = prefs.translateEnabled().get()
        val on = _enabled.value
        updateVisibility()
        val lp = view.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = dp(if (replace) 28 else 72)
        view.layoutParams = lp
        if (!on) {
            liveJob?.cancel()
            fileJob?.cancel()
            fileKey = null
            translatedReady = false
            view.visibility = View.GONE
            if (secondaryByUs) {
                MPVLib.setPropertyString("secondary-sid", "no")
                secondaryByUs = false
            }
        } else {
            onSubText(MPVLib.getPropertyString("sub-text").orEmpty())
            scheduleFileCheck()
        }
    }

    fun setEnabled(on: Boolean) {
        prefs.translateEnabled().set(on)
        applySettings()
    }

    fun toggle() = setEnabled(!_enabled.value)

    /** mpv's own subtitle is hidden only in replace mode while the overlay is what carries the translation. */
    private fun updateVisibility() {
        val hideMpv = _enabled.value && replace && !translatedReady
        MPVLib.setPropertyString("sub-visibility", if (hideMpv) "no" else "yes")
    }

    // ------------------------------------------------------------------ live line by line

    fun onSubText(raw: String) {
        liveJob?.cancel()
        if (!_enabled.value || translatedReady) {
            view.visibility = View.GONE
            return
        }
        // one line of context reads better to the translator than a hard-wrapped pair
        val text = raw.replace(Regex("\\s*\\r?\\n\\s*"), " ").trim()
        if (text.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        val target = prefs.translateTarget().get()
        translator.cached(text, target)?.let { remember(text); show(it); return }
        // while the request runs: replace mode shows the original so the screen is never empty
        if (replace) show(text) else view.visibility = View.GONE
        val context = recentLines.toList()
        liveJob = scope.launch {
            val translated = translator.translate(text, target, context)
            remember(text)
            if (translated != null) show(translated) else if (replace) show(text)
        }
    }

    private fun remember(line: String) {
        if (recentLines.lastOrNull() == line) return
        recentLines.addLast(line)
        while (recentLines.size > 3) recentLines.removeFirst()
    }

    // ------------------------------------------------------------------ whole file

    /** The selected subtitle track changed (called from the `sid` observer). */
    fun onTrackChanged() = scheduleFileCheck()

    private fun scheduleFileCheck() {
        debounceJob?.cancel()
        if (!_enabled.value || !prefs.translateWholeFile().get()) return
        debounceJob = scope.launch {
            delay(400)
            checkTrack()
        }
    }

    private fun checkTrack() {
        val src = MPVLib.getPropertyString("current-tracks/sub/external-filename")
        if (src.isNullOrBlank()) {
            // embedded track: nothing to download, the live overlay handles it
            translatedReady = false
            fileKey = null
            fileJob?.cancel()
            updateVisibility()
            return
        }
        if (src.startsWith(dir.path)) {
            // the track on screen is one of ours
            translatedReady = true
            view.visibility = View.GONE
            updateVisibility()
            return
        }
        val target = prefs.translateTarget().get()
        val key = "${translator.engine}|$target|$src"
        if (key == fileKey) return
        fileKey = key
        translatedReady = false
        updateVisibility()
        fileJob?.cancel()
        fileJob = scope.launch { translateTrack(src, target) }
    }

    private suspend fun translateTrack(src: String, target: String) {
        val raw = withContext(Dispatchers.IO) { load(src) }
        if (raw == null || !SubtitleFileTranslator.looksLikeText(raw)) return // bitmap/unknown: live overlay stays
        val ext = SubtitleFileTranslator.detectExt(src, raw)
        val out = File(dir, "${key(src, target)}.$ext")
        if (!out.exists()) {
            toast("กำลังแปลซับทั้งไฟล์…")
            val translated = SubtitleFileTranslator.translate(raw, ext, target, translator) { _, _ -> }
            if (translated == null) {
                toast("แปลซับทั้งไฟล์ไม่สำเร็จ · ใช้การแปลทีละบรรทัดแทน")
                return
            }
            withContext(Dispatchers.IO) { out.writeText(translated) }
        }
        addTrack(out, target)
        toast("แปลซับเสร็จแล้ว")
    }

    private fun addTrack(file: File, target: String) {
        val path = file.path
        var id = findTrackId(path)
        if (id == null) {
            MPVLib.command(arrayOf("sub-add", path, "auto", "Translated ($target)", target))
            id = findTrackId(path)
        }
        if (id == null) return
        translatedReady = true
        view.visibility = View.GONE
        if (replace) {
            MPVLib.setPropertyInt("sid", id)
        } else {
            MPVLib.setPropertyInt("secondary-sid", id)
            MPVLib.setPropertyInt("secondary-sub-pos", 82)
            secondaryByUs = true
        }
        updateVisibility()
    }

    private fun findTrackId(path: String): Int? {
        val count = (MPVLib.getPropertyInt("track-list/count") as Int?) ?: return null
        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
            if (MPVLib.getPropertyString("track-list/$i/external-filename") == path) {
                return MPVLib.getPropertyInt("track-list/$i/id") as Int?
            }
        }
        return null
    }

    private fun load(src: String): String? = runCatching {
        if (src.startsWith("http://") || src.startsWith("https://")) {
            Injekt.get<NetworkHelper>().client.newCall(Request.Builder().url(src).build()).execute().use {
                if (it.isSuccessful) it.body.string() else null
            }
        } else {
            File(src.removePrefix("file://")).takeIf { it.exists() }?.readText()
        }
    }.getOrNull()

    private fun key(src: String, target: String): String =
        "${translator.engine}-$target-${src.hashCode().toUInt().toString(16)}"

    // ------------------------------------------------------------------ misc

    fun release() {
        scope.cancel()
    }

    private fun show(text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        view.resources.displayMetrics,
    ).toInt()
}
