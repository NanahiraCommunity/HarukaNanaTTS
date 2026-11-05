package dev.nanatts.bank

/**
 * One line of an UTAU `oto.ini`. All times in milliseconds.
 *
 * @param file        wav file name
 * @param alias       alias such as `"a か"` or `"- あ"`
 * @param offset      start of the unit, from the beginning of the file
 * @param consonant   length of the fixed (non-stretchable) region, from [offset]
 * @param cutoff      UTAU cutoff: negative = end measured from [offset], positive = from end of file
 * @param preutterance point aligned with the note onset (vowel onset), from [offset]
 * @param overlap     end of the cross-fade with the previous unit, from [offset]
 */
data class OtoEntry(
    val file: String,
    val alias: String,
    val offset: Double,
    val consonant: Double,
    val cutoff: Double,
    val preutterance: Double,
    val overlap: Double,
) {
    /** End of the unit measured from the start of the file. */
    fun endMs(fileLengthMs: Double): Double = if (cutoff < 0) offset - cutoff else fileLengthMs - cutoff
}
