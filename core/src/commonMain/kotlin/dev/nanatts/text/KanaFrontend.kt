package dev.nanatts.text

import dev.nanatts.util.isJavaWhitespace

/**
 * Frontend for Japanese written in kana (hiragana or katakana); kanji are not supported yet.
 * Uses a simple Tokyo-style flat accent (low first mora, high rest) as the default intonation.
 * Expressive cues: `*emphasis*`, `〜` glide, `♪` half-sung, `！！` excited;
 * a phrase-final `ー` also glides.
 */
class KanaFrontend : TextFrontend {

    override fun analyze(text: String): List<Phrase> {
        val phrases = ArrayList<Phrase>()
        var current = Phrase()
        var wordStart = true
        var geminateNext = false
        var emphasis = false
        var i = 0
        val s = toHiragana(text)
        while (i < s.length) {
            val c = s[i]
            if (c == 'ー') {
                if (current.morae.isNotEmpty()) {
                    last(current).longVowel = true
                }
                i++
                continue
            }
            if (c == '〜' || c == '～' || c == '~') {
                lastMora(current, phrases)?.glide = true
                i++
                continue
            }
            if (c == '♪' || c == '♫') {
                (if (current.morae.isEmpty() && phrases.isNotEmpty()) phrases[phrases.size - 1] else current).sung = true
                i++
                continue
            }
            if (c == '*' || c == '＊') {
                emphasis = !emphasis
                i++
                continue
            }
            if (c == 'っ') {
                geminateNext = true
                i++
                continue
            }
            val ending = ending(c)
            if (ending != null) {
                var bangs = 0
                while (i < s.length && ending == Phrase.Ending.EXCLAMATION && ending(s[i]) == ending) {
                    bangs++
                    i++
                }
                if (bangs == 0) {
                    i++
                }
                if (current.morae.isNotEmpty()) {
                    current.ending = ending
                    current.excited = bangs >= 2
                    current.pauseAfter = if (ending == Phrase.Ending.CONTINUATION) 0.18 else 0.35
                    phrases.add(finish(current))
                    current = Phrase()
                }
                wordStart = true
                continue
            }
            if (isJavaWhitespace(c)) {
                wordStart = true
                i++
                continue
            }
            val used = IntArray(1)
            val m = parseMora(s, i, used)
            i += used[0]
            if (m == null) {
                continue // unsupported character (e.g. kanji)
            }
            if (wordStart && current.morae.isNotEmpty()) {
                last(current).wordEnd = true
            }
            m.wordStart = wordStart
            m.geminate = geminateNext
            m.emphasis = emphasis
            wordStart = false
            geminateNext = false
            current.morae.add(m)
        }
        if (current.morae.isNotEmpty()) {
            phrases.add(finish(current))
        }
        return phrases
    }

    companion object {
        private fun lastMora(current: Phrase, done: List<Phrase>): Mora? {
            if (current.morae.isNotEmpty()) {
                return last(current)
            }
            return if (done.isEmpty()) null else last(done[done.size - 1])
        }

        /**
         * Converts one run of kana (no punctuation) into morae, handling small kana, ー (long vowel) and っ
         * (geminate). Used for kana words inside English text.
         */
        fun word(kana: String): MutableList<Mora> {
            val out = ArrayList<Mora>()
            val s = toHiragana(kana)
            var geminateNext = false
            val used = IntArray(1)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == 'ー') {
                    if (out.isNotEmpty()) {
                        out[out.size - 1].longVowel = true
                    }
                    i++
                    continue
                }
                if (c == 'っ') {
                    geminateNext = true
                    i++
                    continue
                }
                val m = parseMora(s, i, used)
                i += used[0]
                if (m != null) {
                    m.geminate = geminateNext
                    geminateNext = false
                    out.add(m)
                }
            }
            return out
        }

        /** Parses the mora at [i] (two-character combinations first); `used[0]` = chars consumed. */
        private fun parseMora(s: String, i: Int, used: IntArray): Mora? {
            if (i + 1 < s.length) {
                val m = KanaTable.parse(s.substring(i, i + 2))
                if (m != null) {
                    used[0] = 2
                    return m
                }
            }
            used[0] = 1
            return KanaTable.parse(s.substring(i, i + 1))
        }

        private fun finish(p: Phrase): Phrase {
            last(p).wordEnd = true
            // a long vowel at the very end of a phrase (ねー, よー) is expressive: glide it
            if (last(p).longVowel) {
                last(p).glide = true
            }
            for (i in p.morae.indices) {
                p.morae[i].pitch = if (i == 0 && p.morae.size > 1) -1.5 else 0.0
            }
            return p
        }

        private fun last(p: Phrase): Mora = p.morae[p.morae.size - 1]

        private fun ending(c: Char): Phrase.Ending? = when (c) {
            '。', '.' -> Phrase.Ending.STATEMENT
            '、', ',', '，' -> Phrase.Ending.CONTINUATION
            '？', '?' -> Phrase.Ending.QUESTION
            '！', '!' -> Phrase.Ending.EXCLAMATION
            else -> null
        }

        /** Katakana to hiragana, keeping ヴ (the voicebank spells the v-row with katakana ヴ). */
        internal fun toHiragana(text: String): String {
            val s = Normalizer.normalize(text, Normalizer.Form.NFKC) // half-width ｶﾅ -> カナ
            val sb = StringBuilder(s.length)
            for (c in s) {
                if (c == 'ヴ' || c == 'ゔ') {
                    sb.append('ヴ')
                } else if (c in 'ァ'..'ヶ') {
                    sb.append((c.code - 0x60).toChar())
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
