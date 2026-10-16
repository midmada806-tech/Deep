package com.playtranslate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps ML Kit's on-device translator.
 *
 * Language model files are downloaded once and then cached on-device.
 * Each [TranslationManager] instance owns one translator; call [close] when done.
 */
class TranslationManager(
    sourceLangCode: String = TranslateLanguage.JAPANESE,
    targetLangCode: String = TranslateLanguage.ENGLISH
) {
    val sourceLang: String = sourceLangCode
    val targetLang: String = targetLangCode

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(sourceLangCode)
        .setTargetLanguage(targetLangCode)
        .build()

    private val translator = Translation.getClient(options)

    private var modelReady = false

    /**
     * Downloads the language model if it is not already present.
     * Requires a network connection on first use; subsequent calls are instant.
     *
     * @param requireWifi  If true, download only on Wi-Fi (recommended for large models).
     */
    suspend fun ensureModelReady(requireWifi: Boolean = false) {
        if (modelReady) return

        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()

        suspendCancellableCoroutine<Unit> { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    modelReady = true
                    cont.resume(Unit)
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    /**
     * Translates [text]. Call [ensureModelReady] at least once before this.
     */
    suspend fun translate(text: String): String {
        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    fun close() {
        translator.close()
    }
}
