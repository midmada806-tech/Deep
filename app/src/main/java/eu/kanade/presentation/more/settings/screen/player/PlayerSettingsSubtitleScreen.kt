package eu.kanade.presentation.more.settings.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.utils.subtitle.SubtitleTranslator
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.MissingResourceException

object PlayerSettingsSubtitleScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.pref_player_subtitle

    @Composable
    override fun getPreferences(): List<Preference> {
        val subtitlePreferences = remember { Injekt.get<SubtitlePreferences>() }

        val langPref = subtitlePreferences.preferredSubLanguages()
        val whitelist = subtitlePreferences.subtitleWhitelist()
        val blacklist = subtitlePreferences.subtitleBlacklist()

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = subtitlePreferences.translateEnabled(),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate),
                subtitle = stringResource(AYMR.strings.pref_player_subtitle_translate_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = subtitlePreferences.translateTarget(),
                entries = SubtitleTranslator.LANGUAGES.toPersistentMap(),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_target),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = subtitlePreferences.translateMode(),
                entries = persistentMapOf(
                    0 to stringResource(AYMR.strings.pref_player_subtitle_translate_mode_replace),
                    1 to stringResource(AYMR.strings.pref_player_subtitle_translate_mode_both),
                ),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_mode),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = subtitlePreferences.translateWholeFile(),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_whole_file),
                subtitle = stringResource(AYMR.strings.pref_player_subtitle_translate_whole_file_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = subtitlePreferences.translateEngine(),
                entries = persistentMapOf(
                    "google" to stringResource(AYMR.strings.pref_player_subtitle_translate_engine_google),
                    "claude" to stringResource(AYMR.strings.pref_player_subtitle_translate_engine_claude),
                    "gemini" to stringResource(AYMR.strings.pref_player_subtitle_translate_engine_gemini),
                ),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_engine),
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = subtitlePreferences.translateClaudeKey(),
                dialogSubtitle = stringResource(AYMR.strings.pref_player_subtitle_translate_key_hint),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_claude_key),
                subtitle = stringResource(AYMR.strings.pref_player_subtitle_translate_key_hint),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = subtitlePreferences.translateClaudeModel(),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_claude_model),
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = subtitlePreferences.translateGeminiKey(),
                dialogSubtitle = stringResource(AYMR.strings.pref_player_subtitle_translate_key_hint),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_gemini_key),
                subtitle = stringResource(AYMR.strings.pref_player_subtitle_translate_key_hint),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = subtitlePreferences.translateGeminiModel(),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_gemini_model),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = subtitlePreferences.translateStyle(),
                title = stringResource(AYMR.strings.pref_player_subtitle_translate_style),
                subtitle = null,
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = langPref,
                dialogSubtitle = stringResource(AYMR.strings.pref_player_subtitle_lang_info),
                title = stringResource(AYMR.strings.pref_player_subtitle_lang),
                validate = { pref ->
                    val langs = pref.split(",").filter(String::isNotEmpty).map(String::trim)
                    langs.forEach {
                        try {
                            val locale = Locale(it)
                            if (locale.isO3Language == locale.language &&
                                locale.language == locale.getDisplayName(Locale.ENGLISH)
                            ) {
                                throw MissingResourceException("", "", "")
                            }
                        } catch (_: MissingResourceException) {
                            return@EditTextInfoPreference false
                        }
                    }

                    true
                },
                errorMessage = { pref ->
                    val langs = pref.split(",").filter(String::isNotEmpty).map(String::trim)
                    langs.forEach {
                        try {
                            val locale = Locale(it)
                            if (locale.isO3Language == locale.language &&
                                locale.language == locale.getDisplayName(Locale.ENGLISH)
                            ) {
                                throw MissingResourceException("", "", "")
                            }
                        } catch (_: MissingResourceException) {
                            return@EditTextInfoPreference stringResource(
                                AYMR.strings.pref_player_subtitle_invalid_lang,
                                it,
                            )
                        }
                    }
                    ""
                },
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = whitelist,
                dialogSubtitle = stringResource(AYMR.strings.pref_player_subtitle_whitelist_info),
                title = stringResource(AYMR.strings.pref_player_subtitle_whitelist),
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = blacklist,
                dialogSubtitle = stringResource(AYMR.strings.pref_player_subtitle_blacklist_info),
                title = stringResource(AYMR.strings.pref_player_subtitle_blacklist),
            ),
        )
    }
}
