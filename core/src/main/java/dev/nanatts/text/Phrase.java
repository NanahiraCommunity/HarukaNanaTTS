package dev.nanatts.text;

import java.util.ArrayList;
import java.util.List;

/** A breath group: morae spoken without a pause, followed by a pause. */
public final class Phrase {

    /** Intonation type of the phrase ending. */
    public enum Ending {
        /** Declarative fall (period). */
        STATEMENT,
        /** Continuation rise (comma, list item). */
        CONTINUATION,
        /** Yes/no question rise. */
        QUESTION,
        /** Wh-question: falls like a statement but starts higher. */
        WH_QUESTION,
        /** Exclamation: wider range, emphatic fall. */
        EXCLAMATION
    }

    public final List<Mora> morae = new ArrayList<>();
    public Ending ending = Ending.STATEMENT;
    /** Half-sung: pitch targets snap to notes and long vowels get vibrato ({@code ♪}). */
    public boolean sung;
    /** Extra excitement ({@code !!}). */
    public boolean excited;
    /** Silence after the phrase, in seconds (before speed scaling). */
    public double pauseAfter = 0.3;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Mora m : morae) {
            if (m.wordStart && sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(m);
        }
        sb.append(" [").append(ending);
        if (sung) {
            sb.append(" ♪");
        }
        if (excited) {
            sb.append(" !!");
        }
        return sb.append(']').toString();
    }
}
