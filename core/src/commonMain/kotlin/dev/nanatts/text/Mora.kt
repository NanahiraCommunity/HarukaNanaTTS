package dev.nanatts.text

/**
 * One Japanese mora: the unit the voicebank is built from. Language frontends produce morae annotated
 * with the linguistic features the prosody model needs.
 */
class Mora(
    /** Kana as used in the voicebank aliases, e.g. `"か"`, `"てぃ"`, `"ん"`. */
    val kana: String,
    /** Consonant in romaji (`""` for none), e.g. `"k"`, `"sh"`, `"ky"`, or `"N"` for ん. */
    val consonant: String,
    /** Vowel: one of `a i u e o`, or `n` for the moraic nasal. */
    val vowel: Char,
) {

    /** Lexical stress of the syllable this mora belongs to: 0 none, 1 primary, 2 secondary. */
    var stress = 0

    /** True for the first mora of a stressed syllable (where the pitch accent lands). */
    var accentHead = false

    /** Vowel inserted only to make an English consonant pronounceable (kept very short). */
    var epenthetic = false

    /** Whispered vowel (e.g. epenthetic vowel after a voiceless consonant). */
    var devoiced = false

    /** Second element of a diphthong (the "i" of "ai"): a short glide, never accented. */
    var offglide = false

    /** Vowel held for about two morae (ー, English long vowels). */
    var longVowel = false

    /** Preceded by a closure / hold (っ). */
    var geminate = false
    var wordStart = false
    var wordEnd = false

    /** Function word (article, preposition...): de-accented by the prosody model. */
    var functionWord = false

    /** Part of an `*emphasised*` word. */
    var emphasis = false

    /** Vowel stretched with a rise-fall pitch glide (`~`). */
    var glide = false

    /** Optional explicit pitch target in semitones relative to the phrase baseline (overrides stress). */
    var pitch: Double? = null

    val isNasal: Boolean
        get() = vowel == 'n'

    fun hasConsonant(): Boolean = consonant.isNotEmpty() && !isNasal

    override fun toString(): String {
        val sb = StringBuilder(kana)
        if (longVowel) {
            sb.append('ー')
        }
        if (stress == 1) {
            sb.append('\'')
        } else if (stress == 2) {
            sb.append(',')
        }
        if (devoiced) {
            sb.append('°')
        }
        if (glide) {
            sb.append('~')
        }
        if (emphasis) {
            sb.append('*')
        }
        return sb.toString()
    }
}
