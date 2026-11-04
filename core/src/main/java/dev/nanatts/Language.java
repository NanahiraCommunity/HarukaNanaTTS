package dev.nanatts;

import dev.nanatts.g2p.EnglishFrontend;
import dev.nanatts.text.KanaFrontend;
import dev.nanatts.text.TextFrontend;

/** Supported input languages. */
public enum Language {
    /** English text (numbers, abbreviations and punctuation are normalised). */
    ENGLISH {
        @Override
        public TextFrontend frontend() {
            return EnglishFrontend.shared();
        }
    },
    /** Japanese written in hiragana/katakana (kanji not supported yet). */
    JAPANESE_KANA {
        @Override
        public TextFrontend frontend() {
            return new KanaFrontend();
        }
    };

    public abstract TextFrontend frontend();
}
