package dev.nanatts.g2p

/**
 * English word to ARPAbet: CMUdict, then morphological decomposition (suffixes, compounds) against the
 * dictionary, then spelling for acronyms, then letter-to-sound rules.
 */
class EnglishG2P(private val dict: CmuDict) {

    /**
     * Pronounces one normalised word. A leading `#` marks an all-caps token, which is spelled out
     * when it is short or unknown.
     */
    fun phones(word: String): Array<String> {
        if (word.startsWith("#")) {
            val core = word.substring(1).lowercase()
            val known = dict.lookup(core)
            if (known != null && core.length > 3) {
                return known
            }
            return spell(core)
        }
        val w = word.lowercase()
        dict.lookup(w)?.let { return it }
        morphological(w, 2)?.let { return it }
        if (w.length == 1) {
            return spell(w)
        }
        if (w.none { it in "aeiouy" }) {
            return spell(w)
        }
        return LetterToSound.convert(w)
    }

    /** Spells a word letter by letter. */
    fun spell(word: String): Array<String> {
        val out = ArrayList<String>()
        for (c in word.lowercase()) {
            if (c in 'a'..'z') {
                out.addAll(LETTERS[c - 'a'])
            } else if (c in '0'..'9') {
                dict.lookup(NumberWords.cardinal((c - '0').toLong()))?.let { out.addAll(it) }
            }
        }
        return out.toTypedArray()
    }

    private fun morphological(w: String, depth: Int): Array<String>? {
        if (depth <= 0 || w.length < 4) {
            return null
        }
        if (w.endsWith("'s")) {
            val stem = lookupOrDerive(w.substring(0, w.length - 2), depth)
            if (stem != null) {
                return stem + pluralSuffix(stem)
            }
        }
        for ((suffix, phones) in SUFFIXES) {
            if (!w.endsWith(suffix) || w.length - suffix.length < 2) {
                continue
            }
            val stemText = w.substring(0, w.length - suffix.length)
            for (candidate in stemCandidates(stemText, suffix)) {
                val stem = lookupOrDerive(candidate, depth) ?: continue
                if (phones != null) {
                    return stem + phones.split(' ')
                }
                if (suffix == "ed") {
                    return stem + pastSuffix(stem)
                }
                if (suffix == "es" && !endsWithSibilant(stem)) {
                    continue
                }
                return stem + pluralSuffix(stem)
            }
        }
        // compounds: longest dictionary prefix whose remainder is also known
        for (k in w.length - 3 downTo 3) {
            val a = dict.lookup(w.substring(0, k)) ?: continue
            val b = dict.lookup(w.substring(k)) ?: morphological(w.substring(k), depth - 1)
            if (b != null) {
                return a + demote(b)
            }
        }
        return null
    }

    private fun lookupOrDerive(stem: String, depth: Int): Array<String>? =
        dict.lookup(stem) ?: morphological(stem, depth - 1)

    companion object {
        private val LETTERS = arrayOf(
            listOf("EY1"), listOf("B", "IY1"), listOf("S", "IY1"), listOf("D", "IY1"), listOf("IY1"), listOf("EH1", "F"),
            listOf("JH", "IY1"), listOf("EY1", "CH"), listOf("AY1"), listOf("JH", "EY1"), listOf("K", "EY1"),
            listOf("EH1", "L"), listOf("EH1", "M"), listOf("EH1", "N"), listOf("OW1"), listOf("P", "IY1"),
            listOf("K", "Y", "UW1"), listOf("AA1", "R"), listOf("EH1", "S"), listOf("T", "IY1"), listOf("Y", "UW1"),
            listOf("V", "IY1"), listOf("D", "AH1", "B", "AH0", "L", "Y", "UW0"), listOf("EH1", "K", "S"),
            listOf("W", "AY1"), listOf("Z", "IY1"),
        )

        /** Suffix and its phones (null: depends on the stem). */
        private val SUFFIXES = listOf(
            "ing" to "IH0 NG", "ers" to "ER0 Z", "er" to "ER0", "est" to "AH0 S T", "ly" to "L IY0",
            "ness" to "N AH0 S", "ful" to "F AH0 L", "less" to "L AH0 S", "ment" to "M AH0 N T",
            "able" to "AH0 B AH0 L", "ed" to null, "es" to null, "s" to null,
        )

        private fun stemCandidates(stem: String, suffix: String): List<String> {
            val c = ArrayList<String>()
            c.add(stem)
            if (suffix == "ing" || suffix == "ed" || suffix.startsWith("er") || suffix == "est" || suffix == "able") {
                c.add(stem + "e")
                val n = stem.length
                if (n >= 2 && stem[n - 1] == stem[n - 2]) {
                    c.add(stem.substring(0, n - 1)) // running -> run
                }
                if (stem.endsWith("i")) {
                    c.add(stem.substring(0, n - 1) + "y") // happier -> happy
                }
            }
            if ((suffix == "es" || suffix == "ly" || suffix == "ness") && stem.endsWith("i")) {
                c.add(stem.substring(0, stem.length - 1) + "y")
            }
            return c
        }

        private fun endsWithSibilant(stem: Array<String>): Boolean {
            val last = base(stem[stem.size - 1])
            return last == "S" || last == "Z" || last == "SH" || last == "ZH" || last == "CH" || last == "JH"
        }

        private fun pluralSuffix(stem: Array<String>): Array<String> {
            val last = base(stem[stem.size - 1])
            if (endsWithSibilant(stem)) {
                return arrayOf("IH0", "Z")
            }
            if (last == "P" || last == "T" || last == "K" || last == "F" || last == "TH") {
                return arrayOf("S")
            }
            return arrayOf("Z")
        }

        private fun pastSuffix(stem: Array<String>): Array<String> {
            val last = base(stem[stem.size - 1])
            if (last == "T" || last == "D") {
                return arrayOf("IH0", "D")
            }
            if (last == "P" || last == "K" || last == "F" || last == "TH" || last == "S" || last == "SH" ||
                last == "CH"
            ) {
                return arrayOf("T")
            }
            return arrayOf("D")
        }

        private fun demote(p: Array<String>): Array<String> =
            Array(p.size) { if (p[it].endsWith("1")) p[it].substring(0, p[it].length - 1) + "2" else p[it] }

        internal fun base(phone: String): String {
            val n = phone.length
            return if (n > 0 && phone[n - 1].isDigit()) phone.substring(0, n - 1) else phone
        }
    }
}
