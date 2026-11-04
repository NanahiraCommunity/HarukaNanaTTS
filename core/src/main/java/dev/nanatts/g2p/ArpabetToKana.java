package dev.nanatts.g2p;

import dev.nanatts.text.KanaTable;
import dev.nanatts.text.Mora;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the ARPAbet pronunciation of one English word onto Japanese morae available in a CV/VCV voicebank,
 * in the style of katakana loanwords (a Japanese accent): consonants without a vowel get a short voiced
 * epenthetic vowel ({@code cat} &rarr; きゃっと), diphthongs become vowel glides, and stress is kept.
 */
public final class ArpabetToKana {

    private static final Map<String, String> ROW = Tables.map(
            "B", "b", "CH", "ch", "D", "d", "DH", "z", "F", "f", "G", "g", "HH", "h", "JH", "j", "K", "k",
            "L", "r", "M", "m", "N", "n", "NG", "g", "P", "p", "R", "r", "S", "s", "SH", "sh", "T", "t",
            "TH", "s", "V", "v", "W", "w", "Y", "y", "Z", "z", "ZH", "j");

    /** Rows used when a consonant is followed by Y + U (cute, few, music...). */
    private static final Map<String, String> PALATAL = Tables.map(
            "K", "ky", "G", "gy", "N", "ny", "HH", "hy", "B", "by", "P", "py", "M", "my", "R", "ry",
            "L", "ry", "F", "hy", "V", "by", "T", "ty", "D", "dy", "S", "sh", "Z", "j");

    private static final Set<String> SHORT_VOWELS = Tables.set("AE", "EH", "IH", "AA", "AH", "UH");

    private ArpabetToKana() {
    }

    public static List<Mora> convert(String[] phones) {
        List<Mora> out = new ArrayList<>();
        int n = phones.length;
        for (int i = 0; i < n; i++) {
            String p = EnglishG2P.base(phones[i]);
            if (isVowel(p)) {
                vowel("", p, stress(phones[i]), out);
                continue;
            }
            String next = i + 1 < n ? EnglishG2P.base(phones[i + 1]) : null;
            String next2 = i + 2 < n ? EnglishG2P.base(phones[i + 2]) : null;

            // C + Y + U -> palatalised mora (cute -> きゅ)
            if (next != null && next.equals("Y") && next2 != null && (next2.equals("UW") || next2.equals("UH"))
                    && PALATAL.containsKey(p)) {
                vowel(PALATAL.get(p), next2, stress(phones[i + 2]), out);
                i += 2;
                continue;
            }
            // K/G + W + vowel -> くぁ/ぐぃ...
            if ((p.equals("K") || p.equals("G")) && "W".equals(next) && next2 != null && isVowel(next2)
                    && !next2.equals("UW") && !next2.equals("UH")) {
                vowel(p.equals("K") ? "kw" : "gw", next2, stress(phones[i + 2]), out);
                i += 2;
                continue;
            }
            if (next != null && isVowel(next)) {
                if (p.equals("NG")) {
                    out.add(KanaTable.mora("N", 'n'));
                }
                String row = ROW.get(p);
                if (row == null) {
                    continue;
                }
                // "cat" -> きゃ: the palatal glide approximates /æ/ after velars
                if (next.equals("AE") && (p.equals("K") || p.equals("G"))) {
                    row = p.equals("K") ? "ky" : "gy";
                }
                vowel(row, next, stress(phones[i + 1]), out);
                i++;
                continue;
            }
            coda(phones, i, out);
        }
        return out;
    }

    /** A consonant not followed by a vowel. */
    private static void coda(String[] phones, int i, List<Mora> out) {
        String p = EnglishG2P.base(phones[i]);
        String prev = i > 0 ? EnglishG2P.base(phones[i - 1]) : null;
        String next = i + 1 < phones.length ? EnglishG2P.base(phones[i + 1]) : null;
        boolean afterVowel = prev != null && isVowel(prev);
        switch (p) {
            case "R":
                if (afterVowel) {
                    // non-rhotic: "car" -> かー, "here" -> ひあ
                    if (prev.equals("IH") || prev.equals("EH") || prev.equals("UH") || prev.equals("AY")
                            || prev.equals("AW") || prev.equals("IY") || prev.equals("UW")) {
                        Mora a = KanaTable.mora("", 'a');
                        a.offglide = true;
                        a.stress = out.isEmpty() ? 0 : out.get(out.size() - 1).stress;
                        out.add(a);
                    } else if (!out.isEmpty() && !prev.equals("ER")) {
                        out.get(out.size() - 1).longVowel = true;
                    }
                    return;
                }
                epenthetic("r", 'u', out);
                return;
            case "N":
                out.add(KanaTable.mora("N", 'n'));
                return;
            case "NG":
                out.add(KanaTable.mora("N", 'n'));
                return;
            case "M":
                if (next != null && (next.equals("P") || next.equals("B") || next.equals("M"))) {
                    out.add(KanaTable.mora("N", 'n'));
                } else {
                    epenthetic("m", 'u', out);
                }
                return;
            case "Y":
            case "W":
                return;
            default:
                break;
        }
        char v;
        switch (p) {
            case "T":
            case "D":
                v = 'o';
                break;
            case "CH":
            case "JH":
                v = 'i';
                break;
            default:
                v = 'u';
                break;
        }
        Mora m = epenthetic(ROW.get(p), v, out);
        // short stressed vowel + voiceless stop: hold the closure (cat -> きゃっと)
        if (afterVowel && SHORT_VOWELS.contains(prev) && stressDigit(phones[i - 1]) == 1
                && (p.equals("P") || p.equals("T") || p.equals("K") || p.equals("CH"))) {
            m.geminate = true;
        }
    }

    private static Mora epenthetic(String row, char vowel, List<Mora> out) {
        Mora m = KanaTable.mora(row, vowel);
        m.epenthetic = true;
        out.add(m);
        return m;
    }

    private static void vowel(String row, String v, int stress, List<Mora> out) {
        char first;
        char second = 0;
        boolean longV = false;
        switch (v) {
            case "AA": case "AE": case "AH": first = 'a'; break;
            case "AO": first = 'o'; longV = stress == 1; break;
            case "AW": first = 'a'; second = 'u'; break;
            case "AY": first = 'a'; second = 'i'; break;
            case "EH": first = 'e'; break;
            case "ER": first = 'a'; longV = stress >= 1; break;
            case "EY": first = 'e'; second = 'i'; break;
            case "IH": first = 'i'; break;
            case "IY": first = 'i'; longV = stress >= 1; break;
            case "OW": first = 'o'; second = stress >= 1 ? 'u' : 0; break;
            case "OY": first = 'o'; second = 'i'; break;
            case "UH": first = 'u'; break;
            case "UW": first = 'u'; longV = stress >= 1; break;
            default: first = 'a'; break;
        }
        if (!KanaTable.exists(row, first)) {
            row = row.length() > 1 ? row.substring(0, 1) : "";
        }
        Mora m = KanaTable.mora(row, first);
        m.stress = Math.max(0, stress);
        m.accentHead = stress >= 1;
        m.longVowel = longV;
        out.add(m);
        if (second != 0) {
            Mora g = KanaTable.mora("", second);
            g.offglide = true;
            g.stress = m.stress;
            out.add(g);
        }
    }

    private static int stress(String phone) {
        return stressDigit(phone);
    }

    private static int stressDigit(String phone) {
        char c = phone.charAt(phone.length() - 1);
        return Character.isDigit(c) ? c - '0' : 0;
    }

    static boolean isVowel(String p) {
        return !p.isEmpty() && "AEIOU".indexOf(p.charAt(0)) >= 0;
    }
}
