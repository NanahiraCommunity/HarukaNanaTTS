package dev.nanatts.g2p

/**
 * Rule-based English letter-to-sound conversion for words missing from the dictionary (names, brands,
 * neologisms). Greedy longest-match graphemes with a few context rules (magic e, soft c/g, final y)
 * and heuristic stress placement.
 */
internal object LetterToSound {

    private val GRAPHEMES = arrayOf(
        // longest first
        "tion" to "SH AH N", "sion" to "ZH AH N", "ture" to "CH ER", "cial" to "SH AH L", "tial" to "SH AH L",
        "ough" to "AO", "augh" to "AO", "eigh" to "EY", "ight" to "AY T",
        "tch" to "CH", "sch" to "S K", "dge" to "JH", "igh" to "AY", "eau" to "OW", "ous" to "AH S",
        "air" to "EH R", "ear" to "IH R", "eer" to "IH R", "oor" to "AO R",
        "ch" to "CH", "sh" to "SH", "th" to "TH", "ph" to "F", "wh" to "W", "ck" to "K", "ng" to "NG",
        "qu" to "K W", "gh" to "", "kn" to "N", "wr" to "R",
        "ee" to "IY", "ea" to "IY", "oo" to "UW", "ou" to "AW", "ai" to "EY", "ay" to "EY", "oa" to "OW",
        "oi" to "OY", "oy" to "OY", "au" to "AO", "aw" to "AO", "ie" to "IY", "ei" to "EY", "ey" to "EY",
        "ue" to "UW", "ew" to "UW", "ui" to "UW",
        "ar" to "AA R", "er" to "ER", "ir" to "ER", "ur" to "ER", "or" to "AO R",
        "b" to "B", "d" to "D", "f" to "F", "h" to "HH", "j" to "JH", "k" to "K", "l" to "L", "m" to "M",
        "n" to "N", "p" to "P", "r" to "R", "s" to "S", "t" to "T", "v" to "V", "w" to "W", "x" to "K S",
        "z" to "Z", "q" to "K",
    )

    private val UNSTRESSED_PREFIXES = arrayOf("a", "be", "de", "re", "con", "com", "ex", "pre", "for", "di")

    private val NON_LETTERS = Regex("[^a-z]")

    fun convert(word: String): Array<String> {
        val w = word.lowercase().replace(NON_LETTERS, "")
        if (w.isEmpty()) {
            return emptyArray()
        }
        val silentE = w.length > 3 && w.endsWith("e") && !isVowel(w[w.length - 2]) && !w.endsWith("le")
        val body = if (silentE) w.substring(0, w.length - 1) else w
        val phones = ArrayList<String>()
        val vowelPos = ArrayList<Int>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            // doubled consonants are pronounced once
            if (i > 0 && c == body[i - 1] && !isVowel(c)) {
                i++
                continue
            }
            var matched: String? = null
            var ph: String? = null
            if (body.startsWith("ow", i)) {
                ph = if (i + 2 == body.length) "OW" else "AW"
                matched = "ow"
            } else if (isVowel(c) || c == 'y') {
                ph = vowel(body, i, silentE)
                matched = if (ph == null) null else c.toString()
            }
            if (matched == null && !isVowel(c) && c != 'y') {
                if (c == 'c') {
                    ph = if (i + 1 < body.length && "eiy".indexOf(body[i + 1]) >= 0) "S" else "K"
                    if (i + 1 < body.length && (body[i + 1] == 'k' || body[i + 1] == 'h')) {
                        ph = null
                    } else {
                        matched = "c"
                    }
                } else if (c == 'g' && i + 1 < body.length && "eiy".indexOf(body[i + 1]) >= 0 && i > 0) {
                    ph = "JH"
                    matched = "g"
                } else if (c == 's' && i > 0 && i + 1 < body.length && isVowel(body[i - 1]) && isVowel(body[i + 1])) {
                    ph = "Z"
                    matched = "s"
                }
            }
            if (matched == null) {
                for ((g, p) in GRAPHEMES) {
                    if (body.startsWith(g, i)) {
                        if (g == "gh" && i == 0) {
                            continue
                        }
                        if ((g == "kn" || g == "wr") && i != 0) {
                            continue
                        }
                        matched = g
                        ph = p
                        break
                    }
                }
            }
            if (matched == null) {
                if (c == 'g') {
                    matched = "g"
                    ph = "G"
                } else if (c == 'c') {
                    matched = "c"
                    ph = "K"
                } else {
                    i++
                    continue
                }
            }
            for (p in ph!!.split(' ')) {
                if (p.isEmpty()) {
                    continue
                }
                if (isVowelPhone(p)) {
                    vowelPos.add(phones.size)
                }
                phones.add(p)
            }
            i += matched.length
        }
        if (w.endsWith("le") && w.length > 3) {
            vowelPos.add(phones.size)
            phones.add("AH")
            phones.add("L")
        }
        // stress: first syllable, or second after an unstressed prefix / before -tion, -ic
        var stressed = 0
        if (vowelPos.size > 1) {
            for (pre in UNSTRESSED_PREFIXES) {
                if (w.startsWith(pre) && w.length > pre.length + 3) {
                    stressed = 1
                    break
                }
            }
            if (w.endsWith("tion") || w.endsWith("sion") || w.endsWith("ic") || w.endsWith("ical")) {
                stressed = maxOf(0, vowelPos.size - 2)
            }
        }
        for (v in vowelPos.indices) {
            val p = vowelPos[v]
            phones[p] = phones[p] + if (v == stressed) "1" else "0"
        }
        return phones.toTypedArray()
    }

    /** Phone for a single vowel letter (or y), or null if part of a multi-letter grapheme. */
    private fun vowel(s: String, i: Int, silentE: Boolean): String? {
        val c = s[i]
        for ((g, _) in GRAPHEMES) {
            if (g.length >= 2 && s.startsWith(g, i)) {
                return null
            }
        }
        val last = i == s.length - 1
        if (c == 'y') {
            if (i == 0 && s.length > 1 && isVowel(s[1])) {
                return "Y"
            }
            if (last) {
                return if (countVowelLetters(s) > 1) "IY" else "AY"
            }
            return "IH"
        }
        // magic e: vowel + single consonant + silent final e
        val magic = silentE && i == s.length - 2
        if (last && !silentE) {
            return when (c) {
                'a' -> "AH"
                'e' -> "IY"
                'i' -> "IY"
                'o' -> "OW"
                else -> "UW"
            }
        }
        if (magic) {
            return when (c) {
                'a' -> "EY"
                'e' -> "IY"
                'i' -> "AY"
                'o' -> "OW"
                else -> "UW"
            }
        }
        return when (c) {
            'a' -> "AE"
            'e' -> "EH"
            'i' -> "IH"
            'o' -> "AA"
            else -> "AH"
        }
    }

    private fun countVowelLetters(s: String): Int = s.count { isVowel(it) }

    fun isVowel(c: Char): Boolean = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'

    fun isVowelPhone(p: String): Boolean {
        val c = p[0]
        return c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U'
    }
}
