package dev.nanatts.bank

/**
 * A concatenation unit (one oto alias) located inside a [Segment]. Times are seconds from the
 * segment's frame 0.
 *
 * @param alias          e.g. `"a か"`
 * @param prev           preceding context: `'-'` (silence), `a i u e o` or `n`
 * @param kana           the CV/V/N kana this unit leads into
 * @param segment        index into [VoicePack.segment]
 * @param start          unit start (oto offset)
 * @param overlap        end of the cross-fade region (oto overlap)
 * @param consonantStart detected start of the consonant (between overlap and onset)
 * @param onset          vowel onset (oto preutterance)
 * @param fixedEnd       end of the fixed region (oto consonant)
 * @param end            end of usable material (oto cutoff)
 */
data class VoiceUnit(
    val alias: String,
    val prev: Char,
    val kana: String,
    val segment: Int,
    val start: Double,
    val overlap: Double,
    val consonantStart: Double,
    val onset: Double,
    val fixedEnd: Double,
    val end: Double,
)
