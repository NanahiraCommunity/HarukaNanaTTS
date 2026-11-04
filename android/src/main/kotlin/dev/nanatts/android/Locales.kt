package dev.nanatts.android

import android.speech.tts.TextToSpeech
import dev.nanatts.android.engine.EngineVoice
import java.util.Locale

/** The framework speaks ISO-639-2 / ISO-3166 alpha-3 codes; engines use [Locale]s. */
internal object Locales {
    fun iso3Language(l: Locale): String = runCatching { l.isO3Language }.getOrDefault("")
    fun iso3Country(l: Locale): String = runCatching { l.isO3Country }.getOrDefault("")

    fun iso3Triple(l: Locale): Array<String> = arrayOf(iso3Language(l), iso3Country(l), l.variant)

    /** "eng-USA" style key used by CHECK_TTS_DATA results. */
    fun iso3Key(l: Locale): String =
        listOf(iso3Language(l), iso3Country(l), l.variant).filter { it.isNotEmpty() }.joinToString("-")

    /** How well [l] satisfies a request; a TextToSpeech.LANG_* code. */
    fun matchLevel(l: Locale, lang: String?, country: String?, variant: String?): Int {
        if (lang.isNullOrEmpty() || !iso3Language(l).equals(lang, ignoreCase = true)) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (country.isNullOrEmpty() || !iso3Country(l).equals(country, ignoreCase = true)) {
            return TextToSpeech.LANG_AVAILABLE
        }
        if (variant.isNullOrEmpty() || !l.variant.equals(variant, ignoreCase = true)) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
        return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    /** Best voice for the request, or null if the language is not supported at all. */
    fun bestVoice(voices: List<EngineVoice>, lang: String?, country: String?, variant: String?): Pair<EngineVoice, Int>? =
        voices.map { it to matchLevel(it.locale, lang, country, variant) }
            .filter { it.second >= TextToSpeech.LANG_AVAILABLE }
            .maxByOrNull { it.second }
}
