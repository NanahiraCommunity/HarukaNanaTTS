package dev.nanatts.g2p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * English word to ARPAbet: CMUdict, then morphological decomposition (suffixes, compounds) against the
 * dictionary, then spelling for acronyms, then letter-to-sound rules.
 */
public final class EnglishG2P {

    private static final String[][] LETTERS = {
            {"EY1"}, {"B", "IY1"}, {"S", "IY1"}, {"D", "IY1"}, {"IY1"}, {"EH1", "F"}, {"JH", "IY1"},
            {"EY1", "CH"}, {"AY1"}, {"JH", "EY1"}, {"K", "EY1"}, {"EH1", "L"}, {"EH1", "M"}, {"EH1", "N"},
            {"OW1"}, {"P", "IY1"}, {"K", "Y", "UW1"}, {"AA1", "R"}, {"EH1", "S"}, {"T", "IY1"},
            {"Y", "UW1"}, {"V", "IY1"}, {"D", "AH1", "B", "AH0", "L", "Y", "UW0"}, {"EH1", "K", "S"},
            {"W", "AY1"}, {"Z", "IY1"},
    };

    private final CmuDict dict;

    public EnglishG2P(CmuDict dict) {
        this.dict = dict;
    }

    /**
     * Pronounces one normalised word. A leading {@code #} marks an all-caps token, which is spelled out
     * when it is short or unknown.
     */
    public String[] phones(String word) {
        if (word.startsWith("#")) {
            String core = word.substring(1).toLowerCase(Locale.ROOT);
            String[] known = dict.lookup(core);
            if (known != null && core.length() > 3) {
                return known;
            }
            return spell(core);
        }
        String w = word.toLowerCase(Locale.ROOT);
        String[] p = dict.lookup(w);
        if (p != null) {
            return p;
        }
        p = morphological(w, 2);
        if (p != null) {
            return p;
        }
        if (w.length() == 1) {
            return spell(w);
        }
        if (!w.matches(".*[aeiouy].*")) {
            return spell(w);
        }
        return LetterToSound.convert(w);
    }

    /** Spells a word letter by letter. */
    public String[] spell(String word) {
        List<String> out = new ArrayList<>();
        for (char c : word.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                out.addAll(Arrays.asList(LETTERS[c - 'a']));
            } else if (c >= '0' && c <= '9') {
                String[] d = dict.lookup(NumberWords.cardinal(c - '0'));
                if (d != null) {
                    out.addAll(Arrays.asList(d));
                }
            }
        }
        return out.toArray(new String[0]);
    }

    private String[] morphological(String w, int depth) {
        if (depth <= 0 || w.length() < 4) {
            return null;
        }
        if (w.endsWith("'s")) {
            String[] stem = lookupOrDerive(w.substring(0, w.length() - 2), depth);
            if (stem != null) {
                return concat(stem, pluralSuffix(stem));
            }
        }
        String[][] suffixes = {
                {"ing", "IH0 NG"}, {"ers", "ER0 Z"}, {"er", "ER0"}, {"est", "AH0 S T"}, {"ly", "L IY0"},
                {"ness", "N AH0 S"}, {"ful", "F AH0 L"}, {"less", "L AH0 S"}, {"ment", "M AH0 N T"},
                {"able", "AH0 B AH0 L"}, {"ed", null}, {"es", null}, {"s", null},
        };
        for (String[] s : suffixes) {
            if (!w.endsWith(s[0]) || w.length() - s[0].length() < 2) {
                continue;
            }
            String stemText = w.substring(0, w.length() - s[0].length());
            for (String candidate : stemCandidates(stemText, s[0])) {
                String[] stem = lookupOrDerive(candidate, depth);
                if (stem == null) {
                    continue;
                }
                if (s[1] != null) {
                    return concat(stem, s[1].split(" "));
                }
                if (s[0].equals("ed")) {
                    return concat(stem, pastSuffix(stem));
                }
                if (s[0].equals("es") && !endsWithSibilant(stem)) {
                    continue;
                }
                return concat(stem, pluralSuffix(stem));
            }
        }
        // compounds: longest dictionary prefix whose remainder is also known
        for (int k = w.length() - 3; k >= 3; k--) {
            String[] a = dict.lookup(w.substring(0, k));
            if (a == null) {
                continue;
            }
            String[] b = dict.lookup(w.substring(k));
            if (b == null) {
                b = morphological(w.substring(k), depth - 1);
            }
            if (b != null) {
                return concat(a, demote(b));
            }
        }
        return null;
    }

    private String[] lookupOrDerive(String stem, int depth) {
        String[] p = dict.lookup(stem);
        return p != null ? p : morphological(stem, depth - 1);
    }

    private static List<String> stemCandidates(String stem, String suffix) {
        List<String> c = new ArrayList<>();
        c.add(stem);
        if (suffix.equals("ing") || suffix.equals("ed") || suffix.startsWith("er") || suffix.equals("est")
                || suffix.equals("able")) {
            c.add(stem + "e");
            int n = stem.length();
            if (n >= 2 && stem.charAt(n - 1) == stem.charAt(n - 2)) {
                c.add(stem.substring(0, n - 1)); // running -> run
            }
            if (stem.endsWith("i")) {
                c.add(stem.substring(0, n - 1) + "y"); // happier -> happy
            }
        }
        if ((suffix.equals("es") || suffix.equals("ly") || suffix.equals("ness")) && stem.endsWith("i")) {
            c.add(stem.substring(0, stem.length() - 1) + "y");
        }
        return c;
    }

    private static boolean endsWithSibilant(String[] stem) {
        String last = base(stem[stem.length - 1]);
        return last.equals("S") || last.equals("Z") || last.equals("SH") || last.equals("ZH")
                || last.equals("CH") || last.equals("JH");
    }

    private static String[] pluralSuffix(String[] stem) {
        String last = base(stem[stem.length - 1]);
        if (endsWithSibilant(stem)) {
            return new String[]{"IH0", "Z"};
        }
        if (last.equals("P") || last.equals("T") || last.equals("K") || last.equals("F") || last.equals("TH")) {
            return new String[]{"S"};
        }
        return new String[]{"Z"};
    }

    private static String[] pastSuffix(String[] stem) {
        String last = base(stem[stem.length - 1]);
        if (last.equals("T") || last.equals("D")) {
            return new String[]{"IH0", "D"};
        }
        if (last.equals("P") || last.equals("K") || last.equals("F") || last.equals("TH") || last.equals("S")
                || last.equals("SH") || last.equals("CH")) {
            return new String[]{"T"};
        }
        return new String[]{"D"};
    }

    private static String[] demote(String[] p) {
        String[] out = p.clone();
        for (int i = 0; i < out.length; i++) {
            if (out[i].endsWith("1")) {
                out[i] = out[i].substring(0, out[i].length() - 1) + "2";
            }
        }
        return out;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static String base(String phone) {
        int n = phone.length();
        return n > 0 && Character.isDigit(phone.charAt(n - 1)) ? phone.substring(0, n - 1) : phone;
    }
}
