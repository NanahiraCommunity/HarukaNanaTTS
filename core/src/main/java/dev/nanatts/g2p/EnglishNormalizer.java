package dev.nanatts.g2p;

import dev.nanatts.text.Phrase;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw English text into phrases of plain lower-case words: expands numbers, currency, ordinals,
 * abbreviations and symbols, and splits at punctuation into breath groups.
 */
public final class EnglishNormalizer {

    /** A normalised phrase: words plus the intonation of its ending and expressive cues. */
    public static final class Chunk {
        public final List<String> words = new ArrayList<>();
        /** Indices of {@code *emphasised*} words. */
        public final Set<Integer> emphasized = new HashSet<>();
        /** Indices of words followed by {@code ~}. */
        public final Set<Integer> glides = new HashSet<>();
        /** Contains {@code ♪}. */
        public boolean sung;
        /** Ended with {@code !!}. */
        public boolean excited;
        public Phrase.Ending ending = Phrase.Ending.STATEMENT;
        public double pause = 0.3;

        @Override
        public String toString() {
            return String.join(" ", words) + " [" + ending + "]";
        }
    }

    private static final Map<String, String> ABBREVIATIONS = Tables.map(
            "mr", "mister", "mrs", "missus", "ms", "miz", "dr", "doctor", "prof", "professor", "st", "saint",
            "jr", "junior", "sr", "senior", "vs", "versus", "etc", "et cetera", "approx", "approximately",
            "dept", "department", "e.g", "for example", "i.e", "that is", "no", "number", "ave", "avenue",
            "mt", "mount", "ft", "feet", "min", "minutes", "sec", "seconds", "hr", "hours",
            "km", "kilometers", "kg", "kilograms", "cm", "centimeters", "mm", "millimeters", "lbs", "pounds",
            "oz", "ounces");

    private static final Map<String, String> SYMBOLS = Tables.map(
            "&", "and", "@", "at", "+", "plus", "=", "equals", "%", "percent", "#", "number", "°", "degrees",
            "€", "euros", "£", "pounds", "¥", "yen", "×", "times");

    private static final Set<String> WH_WORDS = Tables.set(
            "what", "where", "when", "why", "who", "whom", "whose", "which", "how");

    /** Same as {@code \\R}, which older regex engines (TeaVM/WebAssembly) do not support. */
    private static final String LINE_BREAK = "\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]";

    private static final Pattern TOKEN = Pattern.compile(
            "(?<![\\w.])-(?=\\d)"                // minus sign
            + "|\\$\\d[\\d,]*(?:\\.\\d+)?"         // currency
            + "|\\d+(?:st|nd|rd|th)\\b"             // ordinal
            + "|\\d{1,2}:\\d{2}"                    // time
            + "|\\d[\\d,]*(?:\\.\\d+)?%?"           // number / percentage
            + "|[A-Za-z]+(?:\\.[A-Za-z])+\\.?"      // dotted abbreviation (e.g. U.S.A.)
            + "|[A-Za-z]+(?:['’][A-Za-z]+)*\\.?"    // word (with contraction, maybe abbreviation dot)
            + "|[\\u3041-\\u3096\\u30A1-\\u30FAー\\u30FC]+"  // hiragana / katakana word
            + "|\\.\\.\\.|!+|[.,;:?…—–()\"。、]"     // punctuation
            + "|" + LINE_BREAK
            + "|[*~〜～♪♫]"                         // expressive cues (never spoken)
            + "|[&@+=%#°€£¥×]"                      // symbols
    );

    public List<Chunk> normalize(String text) {
        List<Chunk> chunks = new ArrayList<>();
        Chunk cur = new Chunk();
        text = stripDiacritics(text);
        Matcher m = TOKEN.matcher(text);
        boolean emphasis = false;
        while (m.find()) {
            String tok = m.group();
            char c0 = tok.charAt(0);
            if (tok.equals("*")) {
                emphasis = !emphasis;
                continue;
            }
            if ("~〜～♪♫".indexOf(c0) >= 0) {
                // cues attach to the current phrase, or to the one just closed ("Hi!~")
                Chunk target = cur.words.isEmpty() && !chunks.isEmpty() ? chunks.get(chunks.size() - 1) : cur;
                if (c0 == '♪' || c0 == '♫') {
                    target.sung = true;
                } else if (!target.words.isEmpty()) {
                    target.glides.add(target.words.size() - 1);
                }
                continue;
            }
            if (tok.startsWith("!") || tok.length() == 1 && ".,;:?…—–()\"。、".indexOf(c0) >= 0 || tok.equals("...")
                    || isLineBreak(tok)) {
                Phrase.Ending ending = tok.startsWith("!") ? Phrase.Ending.EXCLAMATION : endingFor(tok);
                if (ending != null && !cur.words.isEmpty()) {
                    finish(cur, ending, tok);
                    cur.excited = tok.length() >= 2 && tok.startsWith("!");
                    chunks.add(cur);
                    cur = new Chunk();
                }
                continue;
            }
            int before = cur.words.size();
            if (tok.equals("-")) {
                cur.words.add("minus");
            } else if (c0 == '$') {
                currency(tok.substring(1).replace(",", ""), cur.words);
            } else if (Character.isDigit(c0)) {
                number(tok, cur.words);
            } else if (isKana(c0)) {
                cur.words.add(tok); // pronounced by the frontend as Japanese
            } else if (tok.length() == 1 && SYMBOLS.containsKey(tok)) {
                cur.words.add(SYMBOLS.get(tok));
            } else {
                word(tok, cur.words, m, text);
                markEmphasis(cur, before, emphasis);
                // a sentence-final period glued to a word ("end.") ends the phrase
                if (tok.endsWith(".") && !isAbbreviation(tok) && !cur.words.isEmpty()) {
                    finish(cur, Phrase.Ending.STATEMENT, ".");
                    chunks.add(cur);
                    cur = new Chunk();
                }
            }
            markEmphasis(cur, before, emphasis);
            if (cur.words.size() >= 14 && isConjunction(cur.words.get(cur.words.size() - 1))) {
                // break very long phrases before a conjunction
                String conj = cur.words.remove(cur.words.size() - 1);
                finish(cur, Phrase.Ending.CONTINUATION, ",");
                cur.pause = 0.12;
                chunks.add(cur);
                cur = new Chunk();
                cur.words.add(conj);
            }
        }
        if (!cur.words.isEmpty()) {
            finish(cur, Phrase.Ending.STATEMENT, ".");
            chunks.add(cur);
        }
        return chunks;
    }

    private static void markEmphasis(Chunk c, int from, boolean on) {
        if (on) {
            for (int i = from; i < c.words.size(); i++) {
                c.emphasized.add(i);
            }
        }
    }

    /** True for hiragana and katakana (including ー). */
    public static boolean isKana(char c) {
        return c >= '\u3041' && c <= '\u30FF';
    }

    /**
     * "café naïve" -> "cafe naive"; ligatures and letters without decomposition are mapped by hand.
     * Full-width / half-width forms are folded (ﾗｰﾒﾝ -> ラーメン, ！ -> !), and the kana voicing marks
     * (dakuten, handakuten) are kept so that が stays が.
     */
    static String stripDiacritics(String text) {
        String d = Normalizer.normalize(Normalizer.normalize(text, Normalizer.Form.NFKC), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK && c != '\u3099' && c != '\u309A') {
                continue;
            }
            switch (c) {
                case 'æ': sb.append("ae"); break;
                case 'Æ': sb.append("AE"); break;
                case 'ø': sb.append('o'); break;
                case 'Ø': sb.append('O'); break;
                case 'œ': sb.append("oe"); break;
                case 'Œ': sb.append("OE"); break;
                case 'ß': sb.append("ss"); break;
                case 'ł': sb.append('l'); break;
                case 'Ł': sb.append('L'); break;
                default: sb.append(c);
            }
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC);
    }

    private static boolean isLineBreak(String tok) {
        return tok.equals("\r\n") || tok.length() == 1 && "\n\r\u000B\u000C\u0085\u2028\u2029".indexOf(tok.charAt(0)) >= 0;
    }

    private static boolean isConjunction(String w) {
        return w.equals("and") || w.equals("but") || w.equals("because") || w.equals("so") || w.equals("or");
    }

    private static Phrase.Ending endingFor(String p) {
        switch (p) {
            case ".":
            case "。":
            case "…":
            case "...":
                return Phrase.Ending.STATEMENT;
            case "!":
                return Phrase.Ending.EXCLAMATION;
            case "?":
                return Phrase.Ending.QUESTION;
            case ",":
            case "、":
            case ";":
            case ":":
            case "—":
            case "–":
            case "(":
            case ")":
                return Phrase.Ending.CONTINUATION;
            default:
                return isLineBreak(p) ? Phrase.Ending.STATEMENT : null;
        }
    }

    private static void finish(Chunk c, Phrase.Ending ending, String punct) {
        if (ending == Phrase.Ending.QUESTION && WH_WORDS.contains(c.words.get(0))) {
            ending = Phrase.Ending.WH_QUESTION;
        }
        c.ending = ending;
        switch (ending) {
            case CONTINUATION:
                c.pause = punct.equals(",") ? 0.16 : 0.22;
                break;
            case STATEMENT:
                c.pause = punct.startsWith(".") || punct.equals("…") ? 0.4 : 0.3;
                break;
            default:
                c.pause = 0.4;
                break;
        }
    }

    private static boolean isAbbreviation(String tok) {
        String core = tok.substring(0, tok.length() - 1).toLowerCase(Locale.ROOT);
        return ABBREVIATIONS.containsKey(core) || core.contains(".");
    }

    private void word(String tok, List<String> out, Matcher m, String text) {
        String t = tok.replace('’', '\'');
        boolean dot = t.endsWith(".");
        String core = dot ? t.substring(0, t.length() - 1) : t;
        String lower = core.toLowerCase(Locale.ROOT);
        if (dot && ABBREVIATIONS.containsKey(lower)) {
            // "St." before a capitalised word is "Saint", otherwise "street"
            if (lower.equals("st") && !nextWordCapitalised(text, m.end())) {
                out.add("street");
            } else if (lower.equals("no") && !nextIsDigit(text, m.end())) {
                out.add("no");
            } else {
                split(ABBREVIATIONS.get(lower), out);
            }
            return;
        }
        if (core.indexOf('.') > 0) {
            // dotted abbreviation: U.S.A. -> u s a
            for (String part : core.split("\\.")) {
                if (!part.isEmpty()) {
                    out.add(part.toLowerCase(Locale.ROOT));
                }
            }
            return;
        }
        out.add(core.equals(core.toUpperCase(Locale.ROOT)) && core.length() > 1 ? "#" + core : lower);
    }

    private static boolean nextWordCapitalised(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                return Character.isUpperCase(c);
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return false;
    }

    private static boolean nextIsDigit(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return Character.isDigit(c);
            }
        }
        return false;
    }

    private static void currency(String amount, List<String> out) {
        String[] parts = amount.split("\\.");
        long dollars = parse(parts[0]);
        split(NumberWords.cardinal(dollars), out);
        out.add(dollars == 1 ? "dollar" : "dollars");
        if (parts.length > 1 && !parts[1].isEmpty()) {
            String c = (parts[1] + "0").substring(0, 2);
            long cents = Long.parseLong(c);
            if (cents > 0) {
                out.add("and");
                split(NumberWords.cardinal(cents), out);
                out.add(cents == 1 ? "cent" : "cents");
            }
        }
    }

    private static void number(String tok, List<String> out) {
        if (tok.matches("\\d+(st|nd|rd|th)")) {
            split(NumberWords.ordinal(parse(tok.replaceAll("\\D", ""))), out);
            return;
        }
        if (tok.matches("\\d{1,2}:\\d{2}")) {
            String[] hm = tok.split(":");
            split(NumberWords.cardinal(Long.parseLong(hm[0])), out);
            int min = Integer.parseInt(hm[1]);
            if (min == 0) {
                out.add("o'clock");
            } else if (min < 10) {
                out.add("oh");
                split(NumberWords.cardinal(min), out);
            } else {
                split(NumberWords.cardinal(min), out);
            }
            return;
        }
        boolean percent = tok.endsWith("%");
        String s = percent ? tok.substring(0, tok.length() - 1) : tok;
        String[] parts = s.split("\\.");
        String intPart = parts[0].replace(",", "");
        if (parts.length == 1 && !percent && intPart.length() == 4 && !parts[0].contains(",")) {
            int y = Integer.parseInt(intPart);
            if (y >= 1100 && y <= 2099) {
                split(NumberWords.year(y), out);
                return;
            }
        }
        if (intPart.length() > 15) {
            split(NumberWords.digits(intPart), out);
        } else {
            split(NumberWords.cardinal(parse(intPart)), out);
        }
        if (parts.length > 1) {
            out.add("point");
            split(NumberWords.digits(parts[1]), out);
        }
        if (percent) {
            out.add("percent");
        }
    }

    private static long parse(String digits) {
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }

    private static void split(String words, List<String> out) {
        for (String w : words.split(" ")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
    }
}
