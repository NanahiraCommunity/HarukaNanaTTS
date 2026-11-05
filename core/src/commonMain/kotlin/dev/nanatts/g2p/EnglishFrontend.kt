package dev.nanatts.g2p

import dev.nanatts.text.KanaFrontend
import dev.nanatts.text.Mora
import dev.nanatts.text.Phrase
import dev.nanatts.text.TextFrontend

/**
 * English text frontend: normalisation, CMUdict-based G2P and mapping onto the voicebank's morae.
 * Hiragana/katakana words inside the text are pronounced as Japanese.
 */
class EnglishFrontend(dict: CmuDict) : TextFrontend {

    private val normalizer = EnglishNormalizer()
    val g2p = EnglishG2P(dict)

    override fun analyze(text: String): List<Phrase> {
        val phrases = ArrayList<Phrase>()
        for (chunk in normalizer.normalize(text)) {
            val phrase = Phrase()
            phrase.ending = chunk.ending
            phrase.pauseAfter = chunk.pause
            phrase.sung = chunk.sung
            phrase.excited = chunk.excited
            for (w in chunk.words.indices) {
                val word = chunk.words[w]
                val japanese = EnglishNormalizer.isKana(word[0])
                val morae = if (japanese) japaneseWord(word) else ArpabetToKana.convert(g2p.phones(word))
                if (morae.isEmpty()) {
                    continue
                }
                val function = word in FUNCTION_WORDS
                val emphasis = w in chunk.emphasized
                for (m in morae) {
                    m.functionWord = function && !emphasis
                    m.emphasis = emphasis
                }
                if (w in chunk.glides) {
                    // glide on the last real vowel, not on an epenthetic one (cute~ -> きゅ~と)
                    var g = morae.size - 1
                    while (g > 0 && morae[g].epenthetic) {
                        g--
                    }
                    morae[g].glide = true
                }
                morae[0].wordStart = true
                morae[morae.size - 1].wordEnd = true
                phrase.morae.addAll(morae)
            }
            if (phrase.morae.isNotEmpty()) {
                phrases.add(phrase)
            }
        }
        return phrases
    }

    companion object {
        private val FUNCTION_WORDS = setOf(
            "a", "an", "the", "of", "to", "in", "on", "at", "for", "with", "by", "from", "into", "and", "or", "but",
            "is", "are", "was", "were", "be", "been", "am", "as", "it", "its", "that", "than", "then", "if", "so",
            "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "our",
            "their", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "shall",
            "should", "may", "might", "must", "there", "this", "these", "those", "some", "just", "i'm", "it's",
            "you're", "we're", "they're", "i'll", "you'll", "i've", "what's", "that's", "there's",
        )

        private val sharedInstance: EnglishFrontend by lazy { EnglishFrontend(CmuDict.read(bundledDictionary())) }

        /** Lazily created instance backed by the bundled dictionary. */
        fun shared(): EnglishFrontend = sharedInstance

        /**
         * A kana word inside English text. It is treated like a content word with the accent on its first
         * mora, so it joins the English intonation instead of sounding flat.
         */
        private fun japaneseWord(kana: String): MutableList<Mora> {
            val morae = KanaFrontend.word(kana)
            if (morae.isNotEmpty()) {
                val head = morae[0]
                head.accentHead = true
                for (m in morae) {
                    m.stress = if (m === head) 1 else 0
                }
            }
            return morae
        }
    }
}

/**
 * Contents of the dictionary bundled with the library ([CmuDict.RESOURCE] on the JVM classpath;
 * `cmudict.tsv.gz` in the working directory on WASI).
 */
internal expect fun bundledDictionary(): ByteArray
