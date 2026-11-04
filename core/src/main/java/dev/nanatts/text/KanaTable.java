package dev.nanatts.text;

import java.util.HashMap;
import java.util.Map;

/**
 * The kana inventory of a Japanese CV/VCV voicebank, keyed by romaji consonant and vowel.
 * Includes the extended kana used for loanwords (てぃ, とぅ, ふぁ, うぃ, ヴぁ, つぁ...).
 */
public final class KanaTable {

    private static final String VOWELS = "aiueo";
    private static final String[][] ROWS = {
            {"", "あ", "い", "う", "え", "お"},
            {"k", "か", "き", "く", "け", "こ"},
            {"g", "が", "ぎ", "ぐ", "げ", "ご"},
            {"s", "さ", "すぃ", "す", "せ", "そ"},
            {"z", "ざ", "ずぃ", "ず", "ぜ", "ぞ"},
            {"sh", "しゃ", "し", "しゅ", "しぇ", "しょ"},
            {"j", "じゃ", "じ", "じゅ", "じぇ", "じょ"},
            {"t", "た", "てぃ", "とぅ", "て", "と"},
            {"d", "だ", "でぃ", "どぅ", "で", "ど"},
            {"ts", "つぁ", "つぃ", "つ", "つぇ", "つぉ"},
            {"ch", "ちゃ", "ち", "ちゅ", "ちぇ", "ちょ"},
            {"n", "な", "に", "ぬ", "ね", "の"},
            {"h", "は", "ひ", "ふ", "へ", "ほ"},
            {"f", "ふぁ", "ふぃ", "ふ", "ふぇ", "ふぉ"},
            {"b", "ば", "び", "ぶ", "べ", "ぼ"},
            {"p", "ぱ", "ぴ", "ぷ", "ぺ", "ぽ"},
            {"m", "ま", "み", "む", "め", "も"},
            {"y", "や", "い", "ゆ", "いぇ", "よ"},
            {"r", "ら", "り", "る", "れ", "ろ"},
            {"w", "わ", "うぃ", "う", "うぇ", "うぉ"},
            {"v", "ヴぁ", "ヴぃ", "ヴ", "ヴぇ", "ヴぉ"},
            {"ky", "きゃ", "き", "きゅ", "きぇ", "きょ"},
            {"gy", "ぎゃ", "ぎ", "ぎゅ", "ぎぇ", "ぎょ"},
            {"ny", "にゃ", "に", "にゅ", "にぇ", "にょ"},
            {"hy", "ひゃ", "ひ", "ひゅ", "ひぇ", "ひょ"},
            {"by", "びゃ", "び", "びゅ", "びぇ", "びょ"},
            {"py", "ぴゃ", "ぴ", "ぴゅ", "ぴぇ", "ぴょ"},
            {"my", "みゃ", "み", "みゅ", "みぇ", "みょ"},
            {"ry", "りゃ", "り", "りゅ", "りぇ", "りょ"},
            {"kw", "くぁ", "くぃ", "く", "くぇ", "くぉ"},
            {"gw", "ぐぁ", "ぐぃ", "ぐ", "ぐぇ", "ぐぉ"},
            {"ty", "ちゃ", "てぃ", "てゅ", "ちぇ", "ちょ"},
            {"dy", "じゃ", "でぃ", "でゅ", "じぇ", "じょ"},
    };

    /** Kana that are not in the bank but have an equivalent there. */
    private static final String[][] ALIASES = {
            {"ぢ", "じ"}, {"づ", "ず"}, {"を", "お"}, {"ゐ", "い"}, {"ゑ", "え"},
            {"ぢゃ", "じゃ"}, {"ぢゅ", "じゅ"}, {"ぢょ", "じょ"}, {"すぃ", "すぃ"}, {"ずぃ", "ずぃ"},
            {"いぇ", "いぇ"}, {"ふゅ", "ひゅ"}, {"ヴゅ", "びゅ"}, {"しぃ", "し"}, {"じぃ", "じ"},
            {"うぅ", "う"}, {"くぅ", "く"}, {"ぐぅ", "ぐ"}, {"てゃ", "ちゃ"}, {"てょ", "ちょ"},
            {"でゃ", "じゃ"}, {"でょ", "じょ"}, {"てぇ", "て"}, {"でぇ", "で"},
            {"ぁ", "あ"}, {"ぃ", "い"}, {"ぅ", "う"}, {"ぇ", "え"}, {"ぉ", "お"},
            {"ゃ", "や"}, {"ゅ", "ゆ"}, {"ょ", "よ"}, {"ゎ", "わ"},
    };

    private static final Map<String, String> KANA = new HashMap<>();
    private static final Map<String, String[]> REVERSE = new HashMap<>();

    static {
        for (String[] row : ROWS) {
            for (int v = 0; v < 5; v++) {
                String kana = row[v + 1];
                KANA.put(row[0] + VOWELS.charAt(v), kana);
                REVERSE.putIfAbsent(kana, new String[]{row[0], String.valueOf(VOWELS.charAt(v))});
            }
        }
        REVERSE.put("ん", new String[]{"N", "n"});
        for (String[] a : ALIASES) {
            String[] target = REVERSE.get(a[1]);
            if (target != null) {
                REVERSE.putIfAbsent(a[0], target);
            }
        }
    }

    private KanaTable() {
    }

    /** Builds a mora from a romaji consonant and vowel, e.g. {@code ("ky", 'a')} &rarr; きゃ. */
    public static Mora mora(String consonant, char vowel) {
        if (vowel == 'n') {
            return new Mora("ん", "N", 'n');
        }
        String kana = KANA.get(consonant + vowel);
        if (kana == null) {
            throw new IllegalArgumentException("no kana for " + consonant + vowel);
        }
        return new Mora(kana, consonant, vowel);
    }

    public static boolean exists(String consonant, char vowel) {
        return KANA.containsKey(consonant + vowel);
    }

    /** Parses one kana (1-2 chars) into a mora, or returns null if unknown. */
    public static Mora parse(String kana) {
        String[] cv = REVERSE.get(kana);
        if (cv == null) {
            return null;
        }
        String canonical = KANA.get(cv[0] + cv[1]);
        if (cv[1].equals("n")) {
            canonical = "ん";
        }
        return new Mora(canonical, cv[0], cv[1].charAt(0));
    }
}
