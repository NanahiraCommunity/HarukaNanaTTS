package dev.nanatts.g2p

import dev.nanatts.text.Normalizer
import dev.nanatts.text.Phrase
import dev.nanatts.util.isJavaWhitespace

/**
 * Turns raw English text into phrases of plain lower-case words: expands numbers, currency, ordinals,
 * abbreviations and symbols, and splits at punctuation into breath groups.
 */
class EnglishNormalizer {

    /** A normalised phrase: words plus the intonation of its ending and expressive cues. */
    class Chunk {
        val words: MutableList<String> = ArrayList()

        /** Indices of `*emphasised*` words. */
        val emphasized: MutableSet<Int> = HashSet()

        /** Indices of words followed by `~`. */
        val glides: MutableSet<Int> = HashSet()

        /** Contains `♪`. */
        var sung = false

        /** Ended with `!!`. */
        var excited = false
        var ending = Phrase.Ending.STATEMENT
        var pause = 0.3

        override fun toString(): String = words.joinToString(" ") + " [" + ending + "]"
    }

    fun normalize(input: String): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        var cur = Chunk()
        val text = stripDiacritics(input)
        var emphasis = false
        for (m in TOKEN.findAll(text)) {
            val tok = m.value
            val c0 = tok[0]
            if (tok == "*") {
                emphasis = !emphasis
                continue
            }
            if ("~〜～♪♫".indexOf(c0) >= 0) {
                // cues attach to the current phrase, or to the one just closed ("Hi!~")
                val target = if (cur.words.isEmpty() && chunks.isNotEmpty()) chunks[chunks.size - 1] else cur
                if (c0 == '♪' || c0 == '♫') {
                    target.sung = true
                } else if (target.words.isNotEmpty()) {
                    target.glides.add(target.words.size - 1)
                }
                continue
            }
            if (tok.startsWith("!") || tok.length == 1 && ".,;:?…—–()\"。、".indexOf(c0) >= 0 || tok == "..." ||
                isLineBreak(tok)
            ) {
                val ending = if (tok.startsWith("!")) Phrase.Ending.EXCLAMATION else endingFor(tok)
                if (ending != null && cur.words.isNotEmpty()) {
                    finish(cur, ending, tok)
                    cur.excited = tok.length >= 2 && tok.startsWith("!")
                    chunks.add(cur)
                    cur = Chunk()
                }
                continue
            }
            val before = cur.words.size
            if (tok == "-") {
                cur.words.add("minus")
            } else if (c0 == '$') {
                currency(tok.substring(1).replace(",", ""), cur.words)
            } else if (c0.isDigit()) {
                number(tok, cur.words)
            } else if (isKana(c0)) {
                cur.words.add(tok) // pronounced by the frontend as Japanese
            } else if (tok.length == 1 && tok in SYMBOLS) {
                cur.words.add(SYMBOLS.getValue(tok))
            } else {
                word(tok, cur.words, m.range.last + 1, text)
                markEmphasis(cur, before, emphasis)
                // a sentence-final period glued to a word ("end.") ends the phrase
                if (tok.endsWith(".") && !isAbbreviation(tok) && cur.words.isNotEmpty()) {
                    finish(cur, Phrase.Ending.STATEMENT, ".")
                    chunks.add(cur)
                    cur = Chunk()
                }
            }
            markEmphasis(cur, before, emphasis)
            if (cur.words.size >= 14 && isConjunction(cur.words[cur.words.size - 1])) {
                // break very long phrases before a conjunction
                val conj = cur.words.removeAt(cur.words.size - 1)
                finish(cur, Phrase.Ending.CONTINUATION, ",")
                cur.pause = 0.12
                chunks.add(cur)
                cur = Chunk()
                cur.words.add(conj)
            }
        }
        if (cur.words.isNotEmpty()) {
            finish(cur, Phrase.Ending.STATEMENT, ".")
            chunks.add(cur)
        }
        return chunks
    }

    private fun word(tok: String, out: MutableList<String>, end: Int, text: String) {
        val t = tok.replace('’', '\'')
        val dot = t.endsWith(".")
        val core = if (dot) t.substring(0, t.length - 1) else t
        val lower = core.lowercase()
        if (dot && lower in ABBREVIATIONS) {
            // "St." before a capitalised word is "Saint", otherwise "street"
            if (lower == "st" && !nextWordCapitalised(text, end)) {
                out.add("street")
            } else if (lower == "no" && !nextIsDigit(text, end)) {
                out.add("no")
            } else {
                split(ABBREVIATIONS.getValue(lower), out)
            }
            return
        }
        if (core.indexOf('.') > 0) {
            // dotted abbreviation: U.S.A. -> u s a
            for (part in core.split('.')) {
                if (part.isNotEmpty()) {
                    out.add(part.lowercase())
                }
            }
            return
        }
        out.add(if (core == core.uppercase() && core.length > 1) "#$core" else lower)
    }

    companion object {
        private val ABBREVIATIONS = mapOf(
            "mr" to "mister", "mrs" to "missus", "ms" to "miz", "dr" to "doctor", "prof" to "professor",
            "st" to "saint", "jr" to "junior", "sr" to "senior", "vs" to "versus", "etc" to "et cetera",
            "approx" to "approximately", "dept" to "department", "e.g" to "for example", "i.e" to "that is",
            "no" to "number", "ave" to "avenue", "mt" to "mount", "ft" to "feet", "min" to "minutes",
            "sec" to "seconds", "hr" to "hours", "km" to "kilometers", "kg" to "kilograms", "cm" to "centimeters",
            "mm" to "millimeters", "lbs" to "pounds", "oz" to "ounces",
        )

        private val SYMBOLS = mapOf(
            "&" to "and", "@" to "at", "+" to "plus", "=" to "equals", "%" to "percent", "#" to "number",
            "°" to "degrees", "€" to "euros", "£" to "pounds", "¥" to "yen", "×" to "times",
        )

        private val WH_WORDS = setOf("what", "where", "when", "why", "who", "whom", "whose", "which", "how")

        /** Same as `\R`, spelled out for regex engines without it. */
        private const val LINE_BREAK = "\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]"

        private val TOKEN = Regex(
            "(?<![\\w.])-(?=\\d)" +                // minus sign
                "|\\$\\d[\\d,]*(?:\\.\\d+)?" +         // currency
                "|\\d+(?:st|nd|rd|th)\\b" +             // ordinal
                "|\\d{1,2}:\\d{2}" +                    // time
                "|\\d[\\d,]*(?:\\.\\d+)?%?" +           // number / percentage
                "|[A-Za-z]+(?:\\.[A-Za-z])+\\.?" +      // dotted abbreviation (e.g. U.S.A.)
                "|[A-Za-z]+(?:['’][A-Za-z]+)*\\.?" +    // word (with contraction, maybe abbreviation dot)
                "|[\\u3041-\\u3096\\u30A1-\\u30FAー\\u30FC]+" + // hiragana / katakana word
                "|\\.\\.\\.|!+|[.,;:?…—–()\"。、]" +     // punctuation
                "|" + LINE_BREAK +
                "|[*~〜～♪♫]" +                         // expressive cues (never spoken)
                "|[&@+=%#°€£¥×]",                       // symbols
        )

        private val ORDINAL = Regex("\\d+(st|nd|rd|th)")
        private val TIME = Regex("\\d{1,2}:\\d{2}")
        private val NON_DIGIT = Regex("\\D")

        private fun markEmphasis(c: Chunk, from: Int, on: Boolean) {
            if (on) {
                for (i in from until c.words.size) {
                    c.emphasized.add(i)
                }
            }
        }

        /** True for hiragana and katakana (including ー). */
        fun isKana(c: Char): Boolean = c in '\u3041'..'\u30FF'

        /**
         * "café naïve" -> "cafe naive"; ligatures and letters without decomposition are mapped by hand.
         * Full-width / half-width forms are folded (ﾗｰﾒﾝ -> ラーメン, ！ -> !), and the kana voicing marks
         * (dakuten, handakuten) are kept so that が stays が.
         */
        internal fun stripDiacritics(text: String): String {
            val d = Normalizer.normalize(Normalizer.normalize(text, Normalizer.Form.NFKC), Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            for (c in d) {
                if (c.category == CharCategory.NON_SPACING_MARK && c != '\u3099' && c != '\u309A') {
                    continue
                }
                when (c) {
                    'æ' -> sb.append("ae")
                    'Æ' -> sb.append("AE")
                    'ø' -> sb.append('o')
                    'Ø' -> sb.append('O')
                    'œ' -> sb.append("oe")
                    'Œ' -> sb.append("OE")
                    'ß' -> sb.append("ss")
                    'ł' -> sb.append('l')
                    'Ł' -> sb.append('L')
                    else -> sb.append(c)
                }
            }
            return Normalizer.normalize(sb, Normalizer.Form.NFC)
        }

        private fun isLineBreak(tok: String): Boolean =
            tok == "\r\n" || tok.length == 1 && "\n\r\u000B\u000C\u0085\u2028\u2029".indexOf(tok[0]) >= 0

        private fun isConjunction(w: String): Boolean =
            w == "and" || w == "but" || w == "because" || w == "so" || w == "or"

        private fun endingFor(p: String): Phrase.Ending? = when (p) {
            ".", "。", "…", "..." -> Phrase.Ending.STATEMENT
            "!" -> Phrase.Ending.EXCLAMATION
            "?" -> Phrase.Ending.QUESTION
            ",", "、", ";", ":", "—", "–", "(", ")" -> Phrase.Ending.CONTINUATION
            else -> if (isLineBreak(p)) Phrase.Ending.STATEMENT else null
        }

        private fun finish(c: Chunk, ending: Phrase.Ending, punct: String) {
            val e = if (ending == Phrase.Ending.QUESTION && c.words[0] in WH_WORDS) Phrase.Ending.WH_QUESTION else ending
            c.ending = e
            c.pause = when (e) {
                Phrase.Ending.CONTINUATION -> if (punct == ",") 0.16 else 0.22
                Phrase.Ending.STATEMENT -> if (punct.startsWith(".") || punct == "…") 0.4 else 0.3
                else -> 0.4
            }
        }

        private fun isAbbreviation(tok: String): Boolean {
            val core = tok.substring(0, tok.length - 1).lowercase()
            return core in ABBREVIATIONS || core.contains(".")
        }

        private fun nextWordCapitalised(text: String, from: Int): Boolean {
            for (i in from until text.length) {
                val c = text[i]
                if (c.isLetter()) {
                    return c.isUpperCase()
                }
                if (!isJavaWhitespace(c)) {
                    return false
                }
            }
            return false
        }

        private fun nextIsDigit(text: String, from: Int): Boolean {
            for (i in from until text.length) {
                val c = text[i]
                if (!isJavaWhitespace(c)) {
                    return c.isDigit()
                }
            }
            return false
        }

        private fun currency(amount: String, out: MutableList<String>) {
            val parts = amount.split('.')
            val dollars = parse(parts[0])
            split(NumberWords.cardinal(dollars), out)
            out.add(if (dollars == 1L) "dollar" else "dollars")
            if (parts.size > 1 && parts[1].isNotEmpty()) {
                val c = (parts[1] + "0").substring(0, 2)
                val cents = c.toLong()
                if (cents > 0) {
                    out.add("and")
                    split(NumberWords.cardinal(cents), out)
                    out.add(if (cents == 1L) "cent" else "cents")
                }
            }
        }

        private fun number(tok: String, out: MutableList<String>) {
            if (ORDINAL.matches(tok)) {
                split(NumberWords.ordinal(parse(tok.replace(NON_DIGIT, ""))), out)
                return
            }
            if (TIME.matches(tok)) {
                val hm = tok.split(':')
                split(NumberWords.cardinal(hm[0].toLong()), out)
                val min = hm[1].toInt()
                if (min == 0) {
                    out.add("o'clock")
                } else if (min < 10) {
                    out.add("oh")
                    split(NumberWords.cardinal(min.toLong()), out)
                } else {
                    split(NumberWords.cardinal(min.toLong()), out)
                }
                return
            }
            val percent = tok.endsWith("%")
            val s = if (percent) tok.substring(0, tok.length - 1) else tok
            val parts = s.split('.')
            val intPart = parts[0].replace(",", "")
            if (parts.size == 1 && !percent && intPart.length == 4 && !parts[0].contains(",")) {
                val y = intPart.toInt()
                if (y in 1100..2099) {
                    split(NumberWords.year(y), out)
                    return
                }
            }
            if (intPart.length > 15) {
                split(NumberWords.digits(intPart), out)
            } else {
                split(NumberWords.cardinal(parse(intPart)), out)
            }
            if (parts.size > 1) {
                out.add("point")
                split(NumberWords.digits(parts[1]), out)
            }
            if (percent) {
                out.add("percent")
            }
        }

        private fun parse(digits: String): Long = if (digits.isEmpty()) 0 else digits.toLong()

        private fun split(words: String, out: MutableList<String>) {
            for (w in words.split(' ')) {
                if (w.isNotEmpty()) {
                    out.add(w)
                }
            }
        }
    }
}
