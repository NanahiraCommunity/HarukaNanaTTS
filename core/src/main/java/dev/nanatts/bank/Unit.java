package dev.nanatts.bank;

/**
 * A concatenation unit (one oto alias) located inside a {@link Segment}. Times are seconds from the
 * segment's frame 0.
 *
 * @param alias          e.g. {@code "a か"}
 * @param prev           preceding context: {@code '-'} (silence), {@code a i u e o} or {@code n}
 * @param kana           the CV/V/N kana this unit leads into
 * @param segment        index into {@link VoicePack#segment(int)}
 * @param start          unit start (oto offset)
 * @param overlap        end of the cross-fade region (oto overlap)
 * @param consonantStart detected start of the consonant (between overlap and onset)
 * @param onset          vowel onset (oto preutterance)
 * @param fixedEnd       end of the fixed region (oto consonant)
 * @param end            end of usable material (oto cutoff)
 */
public record Unit(String alias, char prev, String kana, int segment, double start, double overlap,
                   double consonantStart, double onset, double fixedEnd, double end) {
}
