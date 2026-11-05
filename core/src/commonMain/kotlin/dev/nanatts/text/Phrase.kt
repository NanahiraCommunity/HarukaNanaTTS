package dev.nanatts.text

/** A breath group: morae spoken without a pause, followed by a pause. */
class Phrase {

    /** Intonation type of the phrase ending. */
    enum class Ending {
        /** Declarative fall (period). */
        STATEMENT,

        /** Continuation rise (comma, list item). */
        CONTINUATION,

        /** Yes/no question rise. */
        QUESTION,

        /** Wh-question: falls like a statement but starts higher. */
        WH_QUESTION,

        /** Exclamation: wider range, emphatic fall. */
        EXCLAMATION,
    }

    val morae: MutableList<Mora> = ArrayList()
    var ending = Ending.STATEMENT

    /** Half-sung: pitch targets snap to notes and long vowels get vibrato (`♪`). */
    var sung = false

    /** Extra excitement (`!!`). */
    var excited = false

    /** Silence after the phrase, in seconds (before speed scaling). */
    var pauseAfter = 0.3

    override fun toString(): String {
        val sb = StringBuilder()
        for (m in morae) {
            if (m.wordStart && sb.isNotEmpty()) {
                sb.append(' ')
            }
            sb.append(m)
        }
        sb.append(" [").append(ending)
        if (sung) {
            sb.append(" ♪")
        }
        if (excited) {
            sb.append(" !!")
        }
        return sb.append(']').toString()
    }
}
