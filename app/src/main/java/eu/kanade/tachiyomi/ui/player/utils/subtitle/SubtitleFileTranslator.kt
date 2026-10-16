package eu.kanade.tachiyomi.ui.player.utils.subtitle

/**
 * Translates a whole subtitle file (SRT, VTT, ASS/SSA) and returns the same format with only the
 * dialogue text replaced: timings, styles, headers and positioning tags are kept.
 */
object SubtitleFileTranslator {

    private sealed interface Seg
    private class Lit(val s: String) : Seg
    private class Txt(val text: String, val rebuild: (String) -> String) : Seg

    /** File extension to save the result with: "srt", "vtt" or "ass". */
    fun detectExt(name: String, raw: String): String {
        val ext = name.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            ext == "ass" || ext == "ssa" -> "ass"
            ext == "vtt" -> "vtt"
            ext == "srt" -> "srt"
            raw.contains("[Script Info]", ignoreCase = true) -> "ass"
            raw.trimStart().startsWith("WEBVTT") -> "vtt"
            else -> "srt"
        }
    }

    /** False for bitmap or unknown formats (e.g. the text is not subtitle text at all). */
    fun looksLikeText(raw: String): Boolean =
        raw.contains("-->") || raw.contains("[Events]", ignoreCase = true)

    /**
     * @return the translated file, or null when too many lines failed to translate.
     */
    suspend fun translate(
        raw: String,
        ext: String,
        target: String,
        translator: SubtitleTranslator,
        onProgress: (Int, Int) -> Unit,
    ): String? {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val segs = if (ext == "ass") parseAss(text) else parseCues(text)
        val unique = segs.filterIsInstance<Txt>().map { it.text }.distinct()
        if (unique.isEmpty()) return null
        val translated = translator.translateBatch(unique, target, onProgress = onProgress)
        if (translated.count { it == null } > unique.size / 2) return null
        val map = unique.indices.associate { unique[it] to translated[it] }
        return buildString {
            for (seg in segs) {
                when (seg) {
                    is Lit -> append(seg.s)
                    is Txt -> append(seg.rebuild(map[seg.text] ?: seg.text))
                }
            }
        }
    }

    private val htmlTag = Regex("<[^>]+>")
    private val braceTag = Regex("\\{[^}]*\\}")
    private val leadingTags = Regex("^(\\{[^}]*\\})+")

    // SRT and WebVTT: a cue is the line with "-->" followed by text lines up to a blank line.
    private fun parseCues(text: String): List<Seg> {
        val lines = text.split("\n")
        val out = ArrayList<Seg>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("-->")) {
                out += Lit(line + "\n")
                i++
                val buf = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    buf += lines[i]
                    i++
                }
                val plain = buf.joinToString(" ").replace(htmlTag, "").replace(braceTag, "").trim()
                if (plain.isEmpty()) {
                    out += Lit(buf.joinToString("\n") + "\n")
                } else {
                    out += Txt(plain) { it + "\n" }
                }
            } else {
                out += Lit(line + "\n")
                i++
            }
        }
        return out
    }

    // ASS / SSA: only the Text field of Dialogue lines changes; leading override tags ({\an8}...) stay.
    private fun parseAss(text: String): List<Seg> {
        val out = ArrayList<Seg>()
        var inEvents = false
        var textIdx = -1
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t.startsWith("[")) inEvents = t.equals("[Events]", ignoreCase = true)
            if (inEvents && t.startsWith("Format:", ignoreCase = true)) {
                textIdx = t.substringAfter(":").split(",").map { it.trim().lowercase() }.indexOf("text")
                out += Lit(line + "\n")
                continue
            }
            if (inEvents && textIdx >= 0 && t.startsWith("Dialogue:")) {
                val head = line.substringAfter("Dialogue:")
                val parts = head.split(",", limit = textIdx + 1)
                if (parts.size == textIdx + 1) {
                    val body = parts.last()
                    val drawing = body.contains("\\p1") || body.contains("\\p2")
                    val lead = leadingTags.find(body)?.value.orEmpty()
                    val plain = body.removePrefix(lead).replace(braceTag, "")
                        .replace("\\N", " ").replace("\\n", " ").replace("\\h", " ").trim()
                    if (!drawing && plain.isNotEmpty()) {
                        val prefix = "Dialogue:" + parts.dropLast(1).joinToString(",") + ","
                        out += Txt(plain) { prefix + lead + it + "\n" }
                        continue
                    }
                }
            }
            out += Lit(line + "\n")
        }
        return out
    }
}
