package app.fluxa

import android.content.Intent

/**
 * Mode "อัดจอ" (screen record). Mode "ปกติ" is simply [enabled] = false: nothing is recorded and
 * the pipeline behaves exactly as before.
 *
 * The video is made from the frames the presenter shows (generated + real) - it reuses the
 * existing capture -> presenter path, no extra capture is started.
 */
data class RecordOptions(
    val enabled: Boolean = false,
    /** [AUDIO_APP] = app (internal) sound only, [AUDIO_APP_MIC] = app sound + outside sound (mic). */
    val audio: Int = AUDIO_APP,
    /** Video orientation: true = portrait, false = landscape. */
    val portrait: Boolean = false,
) {
    val withMic: Boolean get() = audio == AUDIO_APP_MIC

    fun writeTo(intent: Intent): Intent {
        intent.putExtra(EXTRA_ENABLED, enabled)
        intent.putExtra(EXTRA_AUDIO, audio)
        intent.putExtra(EXTRA_PORTRAIT, portrait)
        return intent
    }

    companion object {
        const val EXTRA_ENABLED = "rec"
        const val EXTRA_AUDIO = "rec_audio"
        const val EXTRA_PORTRAIT = "rec_portrait"

        const val AUDIO_APP = 0
        const val AUDIO_APP_MIC = 1

        fun clampAudio(value: Int): Int = if (value == AUDIO_APP_MIC) AUDIO_APP_MIC else AUDIO_APP

        fun fromIntent(intent: Intent?): RecordOptions {
            if (intent == null || !intent.getBooleanExtra(EXTRA_ENABLED, false)) return RecordOptions()
            return RecordOptions(
                enabled = true,
                audio = clampAudio(intent.getIntExtra(EXTRA_AUDIO, AUDIO_APP)),
                portrait = intent.getBooleanExtra(EXTRA_PORTRAIT, false),
            )
        }
    }
}
