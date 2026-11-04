package dev.nanatts.bank;

/**
 * One line of an UTAU {@code oto.ini}. All times in milliseconds.
 *
 * @param file        wav file name
 * @param alias       alias such as {@code "a か"} or {@code "- あ"}
 * @param offset      start of the unit, from the beginning of the file
 * @param consonant   length of the fixed (non-stretchable) region, from {@code offset}
 * @param cutoff      UTAU cutoff: negative = end measured from {@code offset}, positive = from end of file
 * @param preutterance point aligned with the note onset (vowel onset), from {@code offset}
 * @param overlap     end of the cross-fade with the previous unit, from {@code offset}
 */
public record OtoEntry(String file, String alias, double offset, double consonant, double cutoff,
                       double preutterance, double overlap) {

    /** End of the unit measured from the start of the file. */
    public double endMs(double fileLengthMs) {
        return cutoff < 0 ? offset - cutoff : fileLengthMs - cutoff;
    }
}
