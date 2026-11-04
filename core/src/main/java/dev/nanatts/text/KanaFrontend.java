package dev.nanatts.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Frontend for Japanese written in kana (hiragana or katakana); kanji are not supported yet.
 * Uses a simple Tokyo-style flat accent (low first mora, high rest) as the default intonation.
 * Expressive cues: {@code *emphasis*}, {@code 〜} glide, {@code ♪} half-sung, {@code ！！} excited;
 * a phrase-final {@code ー} also glides.
 */
public final class KanaFrontend implements TextFrontend {

    @Override
    public List<Phrase> analyze(String text) {
        List<Phrase> phrases = new ArrayList<>();
        Phrase current = new Phrase();
        boolean wordStart = true;
        boolean geminateNext = false;
        boolean emphasis = false;
        int i = 0;
        String s = toHiragana(text);
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 'ー') {
                if (!current.morae.isEmpty()) {
                    last(current).longVowel = true;
                }
                i++;
                continue;
            }
            if (c == '〜' || c == '～' || c == '~') {
                Mora target = lastMora(current, phrases);
                if (target != null) {
                    target.glide = true;
                }
                i++;
                continue;
            }
            if (c == '♪' || c == '♫') {
                (current.morae.isEmpty() && !phrases.isEmpty() ? phrases.get(phrases.size() - 1) : current).sung = true;
                i++;
                continue;
            }
            if (c == '*' || c == '＊') {
                emphasis = !emphasis;
                i++;
                continue;
            }
            if (c == 'っ') {
                geminateNext = true;
                i++;
                continue;
            }
            Phrase.Ending ending = ending(c);
            if (ending != null) {
                int bangs = 0;
                while (i < s.length() && ending == Phrase.Ending.EXCLAMATION && ending(s.charAt(i)) == ending) {
                    bangs++;
                    i++;
                }
                if (bangs == 0) {
                    i++;
                }
                if (!current.morae.isEmpty()) {
                    current.ending = ending;
                    current.excited = bangs >= 2;
                    current.pauseAfter = ending == Phrase.Ending.CONTINUATION ? 0.18 : 0.35;
                    phrases.add(finish(current));
                    current = new Phrase();
                }
                wordStart = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                wordStart = true;
                i++;
                continue;
            }
            int[] used = new int[1];
            Mora m = parseMora(s, i, used);
            i += used[0];
            if (m == null) {
                continue; // unsupported character (e.g. kanji)
            }
            if (wordStart && !current.morae.isEmpty()) {
                last(current).wordEnd = true;
            }
            m.wordStart = wordStart;
            m.geminate = geminateNext;
            m.emphasis = emphasis;
            wordStart = false;
            geminateNext = false;
            current.morae.add(m);
        }
        if (!current.morae.isEmpty()) {
            phrases.add(finish(current));
        }
        return phrases;
    }

    private static Mora lastMora(Phrase current, List<Phrase> done) {
        if (!current.morae.isEmpty()) {
            return last(current);
        }
        return done.isEmpty() ? null : last(done.get(done.size() - 1));
    }

    /**
     * Converts one run of kana (no punctuation) into morae, handling small kana, ー (long vowel) and っ
     * (geminate). Used for kana words inside English text.
     */
    public static List<Mora> word(String kana) {
        List<Mora> out = new ArrayList<>();
        String s = toHiragana(kana);
        boolean geminateNext = false;
        int[] used = new int[1];
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 'ー') {
                if (!out.isEmpty()) {
                    out.get(out.size() - 1).longVowel = true;
                }
                i++;
                continue;
            }
            if (c == 'っ') {
                geminateNext = true;
                i++;
                continue;
            }
            Mora m = parseMora(s, i, used);
            i += used[0];
            if (m != null) {
                m.geminate = geminateNext;
                geminateNext = false;
                out.add(m);
            }
        }
        return out;
    }

    /** Parses the mora at {@code i} (two-character combinations first); {@code used[0]} = chars consumed. */
    private static Mora parseMora(String s, int i, int[] used) {
        if (i + 1 < s.length()) {
            Mora m = KanaTable.parse(s.substring(i, i + 2));
            if (m != null) {
                used[0] = 2;
                return m;
            }
        }
        used[0] = 1;
        return KanaTable.parse(s.substring(i, i + 1));
    }

    private static Phrase finish(Phrase p) {
        last(p).wordEnd = true;
        // a long vowel at the very end of a phrase (ねー, よー) is expressive: glide it
        if (last(p).longVowel) {
            last(p).glide = true;
        }
        for (int i = 0; i < p.morae.size(); i++) {
            p.morae.get(i).pitch = i == 0 && p.morae.size() > 1 ? -1.5 : 0.0;
        }
        return p;
    }

    private static Mora last(Phrase p) {
        return p.morae.get(p.morae.size() - 1);
    }

    private static Phrase.Ending ending(char c) {
        switch (c) {
            case '。':
            case '.':
                return Phrase.Ending.STATEMENT;
            case '、':
            case ',':
            case '，':
                return Phrase.Ending.CONTINUATION;
            case '？':
            case '?':
                return Phrase.Ending.QUESTION;
            case '！':
            case '!':
                return Phrase.Ending.EXCLAMATION;
            default:
                return null;
        }
    }

    /** Katakana to hiragana, keeping ヴ (the voicebank spells the v-row with katakana ヴ). */
    static String toHiragana(String s) {
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC); // half-width ｶﾅ -> カナ
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'ヴ' || c == 'ゔ') {
                sb.append('ヴ');
            } else if (c >= 'ァ' && c <= 'ヶ') {
                sb.append((char) (c - 0x60));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
