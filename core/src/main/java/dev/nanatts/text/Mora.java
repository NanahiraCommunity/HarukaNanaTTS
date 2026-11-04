package dev.nanatts.text;

/**
 * One Japanese mora: the unit the voicebank is built from. Language frontends produce morae annotated
 * with the linguistic features the prosody model needs.
 */
public final class Mora {

    /** Kana as used in the voicebank aliases, e.g. {@code "か"}, {@code "てぃ"}, {@code "ん"}. */
    public final String kana;
    /** Consonant in romaji ({@code ""} for none), e.g. {@code "k"}, {@code "sh"}, {@code "ky"}, or {@code "N"} for ん. */
    public final String consonant;
    /** Vowel: one of {@code a i u e o}, or {@code n} for the moraic nasal. */
    public final char vowel;

    /** Lexical stress of the syllable this mora belongs to: 0 none, 1 primary, 2 secondary. */
    public int stress;
    /** True for the first mora of a stressed syllable (where the pitch accent lands). */
    public boolean accentHead;
    /** Vowel inserted only to make an English consonant pronounceable (kept very short). */
    public boolean epenthetic;
    /** Whispered vowel (e.g. epenthetic vowel after a voiceless consonant). */
    public boolean devoiced;
    /** Second element of a diphthong (the "i" of "ai"): a short glide, never accented. */
    public boolean offglide;
    /** Vowel held for about two morae (ー, English long vowels). */
    public boolean longVowel;
    /** Preceded by a closure / hold (っ). */
    public boolean geminate;
    public boolean wordStart;
    public boolean wordEnd;
    /** Function word (article, preposition...): de-accented by the prosody model. */
    public boolean functionWord;
    /** Part of an {@code *emphasised*} word. */
    public boolean emphasis;
    /** Vowel stretched with a rise-fall pitch glide ({@code ~}). */
    public boolean glide;
    /** Optional explicit pitch target in semitones relative to the phrase baseline (overrides stress). */
    public Double pitch;

    public Mora(String kana, String consonant, char vowel) {
        this.kana = kana;
        this.consonant = consonant;
        this.vowel = vowel;
    }

    public boolean isNasal() {
        return vowel == 'n';
    }

    public boolean hasConsonant() {
        return !consonant.isEmpty() && !isNasal();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(kana);
        if (longVowel) {
            sb.append('ー');
        }
        if (stress == 1) {
            sb.append('\'');
        } else if (stress == 2) {
            sb.append(',');
        }
        if (devoiced) {
            sb.append('°');
        }
        if (glide) {
            sb.append('~');
        }
        if (emphasis) {
            sb.append('*');
        }
        return sb.toString();
    }
}
