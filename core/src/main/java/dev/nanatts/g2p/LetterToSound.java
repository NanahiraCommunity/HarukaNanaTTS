package dev.nanatts.g2p;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rule-based English letter-to-sound conversion for words missing from the dictionary (names, brands,
 * neologisms). Greedy longest-match graphemes with a few context rules (magic e, soft c/g, final y)
 * and heuristic stress placement.
 */
final class LetterToSound {

    private static final String[][] GRAPHEMES = {
            // longest first
            {"tion", "SH AH N"}, {"sion", "ZH AH N"}, {"ture", "CH ER"}, {"cial", "SH AH L"}, {"tial", "SH AH L"},
            {"ough", "AO"}, {"augh", "AO"}, {"eigh", "EY"}, {"ight", "AY T"},
            {"tch", "CH"}, {"sch", "S K"}, {"dge", "JH"}, {"igh", "AY"}, {"eau", "OW"}, {"ous", "AH S"},
            {"air", "EH R"}, {"ear", "IH R"}, {"eer", "IH R"}, {"oor", "AO R"},
            {"ch", "CH"}, {"sh", "SH"}, {"th", "TH"}, {"ph", "F"}, {"wh", "W"}, {"ck", "K"}, {"ng", "NG"},
            {"qu", "K W"}, {"gh", ""}, {"kn", "N"}, {"wr", "R"},
            {"ee", "IY"}, {"ea", "IY"}, {"oo", "UW"}, {"ou", "AW"}, {"ai", "EY"}, {"ay", "EY"}, {"oa", "OW"},
            {"oi", "OY"}, {"oy", "OY"}, {"au", "AO"}, {"aw", "AO"}, {"ie", "IY"}, {"ei", "EY"}, {"ey", "EY"},
            {"ue", "UW"}, {"ew", "UW"}, {"ui", "UW"},
            {"ar", "AA R"}, {"er", "ER"}, {"ir", "ER"}, {"ur", "ER"}, {"or", "AO R"},
            {"b", "B"}, {"d", "D"}, {"f", "F"}, {"h", "HH"}, {"j", "JH"}, {"k", "K"}, {"l", "L"}, {"m", "M"},
            {"n", "N"}, {"p", "P"}, {"r", "R"}, {"s", "S"}, {"t", "T"}, {"v", "V"}, {"w", "W"}, {"x", "K S"},
            {"z", "Z"}, {"q", "K"},
    };

    private static final String[] UNSTRESSED_PREFIXES = {"a", "be", "de", "re", "con", "com", "ex", "pre", "for", "di"};

    private LetterToSound() {
    }

    static String[] convert(String word) {
        String w = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (w.isEmpty()) {
            return new String[0];
        }
        boolean silentE = w.length() > 3 && w.endsWith("e") && !isVowel(w.charAt(w.length() - 2))
                && !w.endsWith("le");
        String body = silentE ? w.substring(0, w.length() - 1) : w;
        List<String> phones = new ArrayList<>();
        List<Integer> vowelPos = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            // doubled consonants are pronounced once
            if (i > 0 && c == body.charAt(i - 1) && !isVowel(c)) {
                i++;
                continue;
            }
            String matched = null;
            String ph = null;
            if (body.startsWith("ow", i)) {
                ph = i + 2 == body.length() ? "OW" : "AW";
                matched = "ow";
            } else if (isVowel(c) || c == 'y') {
                ph = vowel(body, i, silentE);
                matched = ph == null ? null : String.valueOf(c);
            }
            if (matched == null && !isVowel(c) && c != 'y') {
                if (c == 'c') {
                    ph = i + 1 < body.length() && "eiy".indexOf(body.charAt(i + 1)) >= 0 ? "S" : "K";
                    if (i + 1 < body.length() && (body.charAt(i + 1) == 'k' || body.charAt(i + 1) == 'h')) {
                        ph = null;
                    } else {
                        matched = "c";
                    }
                } else if (c == 'g' && i + 1 < body.length() && "eiy".indexOf(body.charAt(i + 1)) >= 0 && i > 0) {
                    ph = "JH";
                    matched = "g";
                } else if (c == 's' && i > 0 && i + 1 < body.length() && isVowel(body.charAt(i - 1))
                        && isVowel(body.charAt(i + 1))) {
                    ph = "Z";
                    matched = "s";
                }
            }
            if (matched == null) {
                for (String[] g : GRAPHEMES) {
                    if (body.startsWith(g[0], i)) {
                        if (g[0].equals("gh") && i == 0) {
                            continue;
                        }
                        if ((g[0].equals("kn") || g[0].equals("wr")) && i != 0) {
                            continue;
                        }
                        matched = g[0];
                        ph = g[1];
                        break;
                    }
                }
            }
            if (matched == null) {
                if (c == 'g') {
                    matched = "g";
                    ph = "G";
                } else if (c == 'c') {
                    matched = "c";
                    ph = "K";
                } else {
                    i++;
                    continue;
                }
            }
            for (String p : ph.split(" ")) {
                if (p.isEmpty()) {
                    continue;
                }
                if (isVowelPhone(p)) {
                    vowelPos.add(phones.size());
                }
                phones.add(p);
            }
            i += matched.length();
        }
        if (w.endsWith("le") && w.length() > 3) {
            vowelPos.add(phones.size());
            phones.add("AH");
            phones.add("L");
        }
        // stress: first syllable, or second after an unstressed prefix / before -tion, -ic
        int stressed = 0;
        if (vowelPos.size() > 1) {
            for (String pre : UNSTRESSED_PREFIXES) {
                if (w.startsWith(pre) && w.length() > pre.length() + 3) {
                    stressed = 1;
                    break;
                }
            }
            if (w.endsWith("tion") || w.endsWith("sion") || w.endsWith("ic") || w.endsWith("ical")) {
                stressed = Math.max(0, vowelPos.size() - 2);
            }
        }
        for (int v = 0; v < vowelPos.size(); v++) {
            int p = vowelPos.get(v);
            phones.set(p, phones.get(p) + (v == stressed ? "1" : "0"));
        }
        return phones.toArray(new String[0]);
    }

    /** Phone for a single vowel letter (or y), or null if part of a multi-letter grapheme. */
    private static String vowel(String s, int i, boolean silentE) {
        char c = s.charAt(i);
        for (String[] g : GRAPHEMES) {
            if (g[0].length() >= 2 && s.startsWith(g[0], i)) {
                return null;
            }
        }
        boolean last = i == s.length() - 1;
        if (c == 'y') {
            if (i == 0 && s.length() > 1 && isVowel(s.charAt(1))) {
                return "Y";
            }
            if (last) {
                return countVowelLetters(s) > 1 ? "IY" : "AY";
            }
            return "IH";
        }
        // magic e: vowel + single consonant + silent final e
        boolean magic = silentE && i == s.length() - 2;
        if (last && !silentE) {
            switch (c) {
                case 'a': return "AH";
                case 'e': return "IY";
                case 'i': return "IY";
                case 'o': return "OW";
                default: return "UW";
            }
        }
        if (magic) {
            switch (c) {
                case 'a': return "EY";
                case 'e': return "IY";
                case 'i': return "AY";
                case 'o': return "OW";
                default: return "UW";
            }
        }
        switch (c) {
            case 'a': return "AE";
            case 'e': return "EH";
            case 'i': return "IH";
            case 'o': return "AA";
            default: return "AH";
        }
    }

    private static int countVowelLetters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isVowel(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    static boolean isVowelPhone(String p) {
        char c = p.charAt(0);
        return c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U';
    }
}
