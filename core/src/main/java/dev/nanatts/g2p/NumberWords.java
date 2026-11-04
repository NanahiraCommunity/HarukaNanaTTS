package dev.nanatts.g2p;

/** Spells out numbers in English words. */
final class NumberWords {

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
            "ninety"};
    private static final String[] SCALES = {"", "thousand", "million", "billion", "trillion", "quadrillion"};

    private NumberWords() {
    }

    static String cardinal(long n) {
        if (n < 0) {
            return "minus " + cardinal(-n);
        }
        if (n < 20) {
            return ONES[(int) n];
        }
        StringBuilder sb = new StringBuilder();
        int scale = 0;
        String result = "";
        while (n > 0) {
            int chunk = (int) (n % 1000);
            if (chunk > 0) {
                String words = below1000(chunk);
                if (!SCALES[scale].isEmpty()) {
                    words += " " + SCALES[scale];
                }
                result = result.isEmpty() ? words : words + " " + result;
            }
            n /= 1000;
            scale++;
        }
        sb.append(result);
        return sb.toString();
    }

    private static String below1000(int n) {
        StringBuilder sb = new StringBuilder();
        if (n >= 100) {
            sb.append(ONES[n / 100]).append(" hundred");
            n %= 100;
            if (n > 0) {
                sb.append(' ');
            }
        }
        if (n >= 20) {
            sb.append(TENS[n / 10]);
            if (n % 10 > 0) {
                sb.append(' ').append(ONES[n % 10]);
            }
        } else if (n > 0) {
            sb.append(ONES[n]);
        }
        return sb.toString();
    }

    /** "1984" -> "nineteen eighty four", "2005" -> "two thousand five", "1900" -> "nineteen hundred". */
    static String year(int y) {
        if (y >= 2000 && y < 2010) {
            return cardinal(y);
        }
        int hi = y / 100;
        int lo = y % 100;
        if (lo == 0) {
            return cardinal(hi) + " hundred";
        }
        return cardinal(hi) + " " + (lo < 10 ? "oh " + cardinal(lo) : cardinal(lo));
    }

    static String ordinal(long n) {
        String c = cardinal(n);
        int sp = c.lastIndexOf(' ');
        String head = sp < 0 ? "" : c.substring(0, sp + 1);
        String last = sp < 0 ? c : c.substring(sp + 1);
        switch (last) {
            case "one": return head + "first";
            case "two": return head + "second";
            case "three": return head + "third";
            case "five": return head + "fifth";
            case "eight": return head + "eighth";
            case "nine": return head + "ninth";
            case "twelve": return head + "twelfth";
            default:
                if (last.endsWith("y")) {
                    return head + last.substring(0, last.length() - 1) + "ieth";
                }
                return head + last + "th";
        }
    }

    /** Reads each digit: "0042" -> "zero zero four two". */
    static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(ONES[c - '0']);
            }
        }
        return sb.toString();
    }
}
