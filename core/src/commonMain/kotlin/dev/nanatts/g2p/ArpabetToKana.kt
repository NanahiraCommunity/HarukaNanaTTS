package dev.nanatts.g2p

import dev.nanatts.text.KanaTable
import dev.nanatts.text.Mora

/**
 * Maps the ARPAbet pronunciation of one English word onto Japanese morae available in a CV/VCV voicebank,
 * in the style of katakana loanwords (a Japanese accent): consonants without a vowel get a short voiced
 * epenthetic vowel (`cat` → きゃっと), diphthongs become vowel glides, and stress is kept.
 */
object ArpabetToKana {

    private val ROW = mapOf(
        "B" to "b", "CH" to "ch", "D" to "d", "DH" to "z", "F" to "f", "G" to "g", "HH" to "h", "JH" to "j",
        "K" to "k", "L" to "r", "M" to "m", "N" to "n", "NG" to "g", "P" to "p", "R" to "r", "S" to "s",
        "SH" to "sh", "T" to "t", "TH" to "s", "V" to "v", "W" to "w", "Y" to "y", "Z" to "z", "ZH" to "j",
    )

    /** Rows used when a consonant is followed by Y + U (cute, few, music...). */
    private val PALATAL = mapOf(
        "K" to "ky", "G" to "gy", "N" to "ny", "HH" to "hy", "B" to "by", "P" to "py", "M" to "my", "R" to "ry",
        "L" to "ry", "F" to "hy", "V" to "by", "T" to "ty", "D" to "dy", "S" to "sh", "Z" to "j",
    )

    private val SHORT_VOWELS = setOf("AE", "EH", "IH", "AA", "AH", "UH")

    fun convert(phones: Array<String>): MutableList<Mora> {
        val out = ArrayList<Mora>()
        val n = phones.size
        var i = 0
        while (i < n) {
            val p = EnglishG2P.base(phones[i])
            if (isVowel(p)) {
                vowel("", p, stress(phones[i]), out)
                i++
                continue
            }
            val next = if (i + 1 < n) EnglishG2P.base(phones[i + 1]) else null
            val next2 = if (i + 2 < n) EnglishG2P.base(phones[i + 2]) else null

            // C + Y + U -> palatalised mora (cute -> きゅ)
            if (next == "Y" && next2 != null && (next2 == "UW" || next2 == "UH") && p in PALATAL) {
                vowel(PALATAL.getValue(p), next2, stress(phones[i + 2]), out)
                i += 3
                continue
            }
            // K/G + W + vowel -> くぁ/ぐぃ...
            if ((p == "K" || p == "G") && next == "W" && next2 != null && isVowel(next2) && next2 != "UW" &&
                next2 != "UH"
            ) {
                vowel(if (p == "K") "kw" else "gw", next2, stress(phones[i + 2]), out)
                i += 3
                continue
            }
            if (next != null && isVowel(next)) {
                if (p == "NG") {
                    out.add(KanaTable.mora("N", 'n'))
                }
                var row = ROW[p]
                if (row == null) {
                    i++
                    continue
                }
                // "cat" -> きゃ: the palatal glide approximates /æ/ after velars
                if (next == "AE" && (p == "K" || p == "G")) {
                    row = if (p == "K") "ky" else "gy"
                }
                vowel(row, next, stress(phones[i + 1]), out)
                i += 2
                continue
            }
            coda(phones, i, out)
            i++
        }
        return out
    }

    /** A consonant not followed by a vowel. */
    private fun coda(phones: Array<String>, i: Int, out: MutableList<Mora>) {
        val p = EnglishG2P.base(phones[i])
        val prev = if (i > 0) EnglishG2P.base(phones[i - 1]) else null
        val next = if (i + 1 < phones.size) EnglishG2P.base(phones[i + 1]) else null
        val afterVowel = prev != null && isVowel(prev)
        when (p) {
            "R" -> {
                if (afterVowel) {
                    // non-rhotic: "car" -> かー, "here" -> ひあ
                    if (prev == "IH" || prev == "EH" || prev == "UH" || prev == "AY" || prev == "AW" ||
                        prev == "IY" || prev == "UW"
                    ) {
                        val a = KanaTable.mora("", 'a')
                        a.offglide = true
                        a.stress = if (out.isEmpty()) 0 else out[out.size - 1].stress
                        out.add(a)
                    } else if (out.isNotEmpty() && prev != "ER") {
                        out[out.size - 1].longVowel = true
                    }
                    return
                }
                epenthetic("r", 'u', out)
                return
            }
            "N", "NG" -> {
                out.add(KanaTable.mora("N", 'n'))
                return
            }
            "M" -> {
                if (next != null && (next == "P" || next == "B" || next == "M")) {
                    out.add(KanaTable.mora("N", 'n'))
                } else {
                    epenthetic("m", 'u', out)
                }
                return
            }
            "Y", "W" -> return
        }
        val v = when (p) {
            "T", "D" -> 'o'
            "CH", "JH" -> 'i'
            else -> 'u'
        }
        val m = epenthetic(ROW.getValue(p), v, out)
        // short stressed vowel + voiceless stop: hold the closure (cat -> きゃっと)
        if (afterVowel && prev in SHORT_VOWELS && stressDigit(phones[i - 1]) == 1 &&
            (p == "P" || p == "T" || p == "K" || p == "CH")
        ) {
            m.geminate = true
        }
    }

    private fun epenthetic(row: String, vowel: Char, out: MutableList<Mora>): Mora {
        val m = KanaTable.mora(row, vowel)
        m.epenthetic = true
        out.add(m)
        return m
    }

    private fun vowel(rowIn: String, v: String, stress: Int, out: MutableList<Mora>) {
        var row = rowIn
        var first: Char
        var second = 0.toChar()
        var longV = false
        when (v) {
            "AA", "AE", "AH" -> first = 'a'
            "AO" -> {
                first = 'o'
                longV = stress == 1
            }
            "AW" -> {
                first = 'a'
                second = 'u'
            }
            "AY" -> {
                first = 'a'
                second = 'i'
            }
            "EH" -> first = 'e'
            "ER" -> {
                first = 'a'
                longV = stress >= 1
            }
            "EY" -> {
                first = 'e'
                second = 'i'
            }
            "IH" -> first = 'i'
            "IY" -> {
                first = 'i'
                longV = stress >= 1
            }
            "OW" -> {
                first = 'o'
                second = if (stress >= 1) 'u' else 0.toChar()
            }
            "OY" -> {
                first = 'o'
                second = 'i'
            }
            "UH" -> first = 'u'
            "UW" -> {
                first = 'u'
                longV = stress >= 1
            }
            else -> first = 'a'
        }
        if (!KanaTable.exists(row, first)) {
            row = if (row.length > 1) row.substring(0, 1) else ""
        }
        val m = KanaTable.mora(row, first)
        m.stress = maxOf(0, stress)
        m.accentHead = stress >= 1
        m.longVowel = longV
        out.add(m)
        if (second.code != 0) {
            val g = KanaTable.mora("", second)
            g.offglide = true
            g.stress = m.stress
            out.add(g)
        }
    }

    private fun stress(phone: String): Int = stressDigit(phone)

    private fun stressDigit(phone: String): Int {
        val c = phone[phone.length - 1]
        return if (c.isDigit()) c - '0' else 0
    }

    internal fun isVowel(p: String): Boolean = p.isNotEmpty() && "AEIOU".indexOf(p[0]) >= 0
}
