package dev.nanatts

import dev.nanatts.g2p.EnglishFrontend
import dev.nanatts.text.KanaFrontend
import dev.nanatts.text.TextFrontend

/** Supported input languages. */
enum class Language {
    /** English text (numbers, abbreviations and punctuation are normalised). */
    ENGLISH {
        override fun frontend(): TextFrontend = EnglishFrontend.shared()
    },

    /** Japanese written in hiragana/katakana (kanji not supported yet). */
    JAPANESE_KANA {
        override fun frontend(): TextFrontend = KanaFrontend()
    },
    ;

    abstract fun frontend(): TextFrontend
}
