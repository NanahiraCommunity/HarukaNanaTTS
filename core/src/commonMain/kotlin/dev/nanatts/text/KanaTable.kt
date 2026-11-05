package dev.nanatts.text

/**
 * The kana inventory of a Japanese CV/VCV voicebank, keyed by romaji consonant and vowel.
 * Includes the extended kana used for loanwords (てぃ, とぅ, ふぁ, うぃ, ヴぁ, つぁ...).
 */
object KanaTable {

    private const val VOWELS = "aiueo"
    private val ROWS = arrayOf(
        arrayOf("", "あ", "い", "う", "え", "お"),
        arrayOf("k", "か", "き", "く", "け", "こ"),
        arrayOf("g", "が", "ぎ", "ぐ", "げ", "ご"),
        arrayOf("s", "さ", "すぃ", "す", "せ", "そ"),
        arrayOf("z", "ざ", "ずぃ", "ず", "ぜ", "ぞ"),
        arrayOf("sh", "しゃ", "し", "しゅ", "しぇ", "しょ"),
        arrayOf("j", "じゃ", "じ", "じゅ", "じぇ", "じょ"),
        arrayOf("t", "た", "てぃ", "とぅ", "て", "と"),
        arrayOf("d", "だ", "でぃ", "どぅ", "で", "ど"),
        arrayOf("ts", "つぁ", "つぃ", "つ", "つぇ", "つぉ"),
        arrayOf("ch", "ちゃ", "ち", "ちゅ", "ちぇ", "ちょ"),
        arrayOf("n", "な", "に", "ぬ", "ね", "の"),
        arrayOf("h", "は", "ひ", "ふ", "へ", "ほ"),
        arrayOf("f", "ふぁ", "ふぃ", "ふ", "ふぇ", "ふぉ"),
        arrayOf("b", "ば", "び", "ぶ", "べ", "ぼ"),
        arrayOf("p", "ぱ", "ぴ", "ぷ", "ぺ", "ぽ"),
        arrayOf("m", "ま", "み", "む", "め", "も"),
        arrayOf("y", "や", "い", "ゆ", "いぇ", "よ"),
        arrayOf("r", "ら", "り", "る", "れ", "ろ"),
        arrayOf("w", "わ", "うぃ", "う", "うぇ", "うぉ"),
        arrayOf("v", "ヴぁ", "ヴぃ", "ヴ", "ヴぇ", "ヴぉ"),
        arrayOf("ky", "きゃ", "き", "きゅ", "きぇ", "きょ"),
        arrayOf("gy", "ぎゃ", "ぎ", "ぎゅ", "ぎぇ", "ぎょ"),
        arrayOf("ny", "にゃ", "に", "にゅ", "にぇ", "にょ"),
        arrayOf("hy", "ひゃ", "ひ", "ひゅ", "ひぇ", "ひょ"),
        arrayOf("by", "びゃ", "び", "びゅ", "びぇ", "びょ"),
        arrayOf("py", "ぴゃ", "ぴ", "ぴゅ", "ぴぇ", "ぴょ"),
        arrayOf("my", "みゃ", "み", "みゅ", "みぇ", "みょ"),
        arrayOf("ry", "りゃ", "り", "りゅ", "りぇ", "りょ"),
        arrayOf("kw", "くぁ", "くぃ", "く", "くぇ", "くぉ"),
        arrayOf("gw", "ぐぁ", "ぐぃ", "ぐ", "ぐぇ", "ぐぉ"),
        arrayOf("ty", "ちゃ", "てぃ", "てゅ", "ちぇ", "ちょ"),
        arrayOf("dy", "じゃ", "でぃ", "でゅ", "じぇ", "じょ"),
    )

    /** Kana that are not in the bank but have an equivalent there. */
    private val ALIASES = arrayOf(
        arrayOf("ぢ", "じ"), arrayOf("づ", "ず"), arrayOf("を", "お"), arrayOf("ゐ", "い"), arrayOf("ゑ", "え"),
        arrayOf("ぢゃ", "じゃ"), arrayOf("ぢゅ", "じゅ"), arrayOf("ぢょ", "じょ"), arrayOf("すぃ", "すぃ"), arrayOf("ずぃ", "ずぃ"),
        arrayOf("いぇ", "いぇ"), arrayOf("ふゅ", "ひゅ"), arrayOf("ヴゅ", "びゅ"), arrayOf("しぃ", "し"), arrayOf("じぃ", "じ"),
        arrayOf("うぅ", "う"), arrayOf("くぅ", "く"), arrayOf("ぐぅ", "ぐ"), arrayOf("てゃ", "ちゃ"), arrayOf("てょ", "ちょ"),
        arrayOf("でゃ", "じゃ"), arrayOf("でょ", "じょ"), arrayOf("てぇ", "て"), arrayOf("でぇ", "で"),
        arrayOf("ぁ", "あ"), arrayOf("ぃ", "い"), arrayOf("ぅ", "う"), arrayOf("ぇ", "え"), arrayOf("ぉ", "お"),
        arrayOf("ゃ", "や"), arrayOf("ゅ", "ゆ"), arrayOf("ょ", "よ"), arrayOf("ゎ", "わ"),
    )

    private val KANA = HashMap<String, String>()
    private val REVERSE = HashMap<String, Array<String>>()

    init {
        for (row in ROWS) {
            for (v in 0 until 5) {
                val kana = row[v + 1]
                KANA[row[0] + VOWELS[v]] = kana
                if (kana !in REVERSE) {
                    REVERSE[kana] = arrayOf(row[0], VOWELS[v].toString())
                }
            }
        }
        REVERSE["ん"] = arrayOf("N", "n")
        for (a in ALIASES) {
            val target = REVERSE[a[1]]
            if (target != null && a[0] !in REVERSE) {
                REVERSE[a[0]] = target
            }
        }
    }

    /** Builds a mora from a romaji consonant and vowel, e.g. `("ky", 'a')` → きゃ. */
    fun mora(consonant: String, vowel: Char): Mora {
        if (vowel == 'n') {
            return Mora("ん", "N", 'n')
        }
        val kana = KANA[consonant + vowel] ?: throw IllegalArgumentException("no kana for $consonant$vowel")
        return Mora(kana, consonant, vowel)
    }

    fun exists(consonant: String, vowel: Char): Boolean = KANA.containsKey(consonant + vowel)

    /** Parses one kana (1-2 chars) into a mora, or returns null if unknown. */
    fun parse(kana: String): Mora? {
        val cv = REVERSE[kana] ?: return null
        var canonical = KANA[cv[0] + cv[1]]
        if (cv[1] == "n") {
            canonical = "ん"
        }
        return Mora(canonical!!, cv[0], cv[1][0])
    }
}
