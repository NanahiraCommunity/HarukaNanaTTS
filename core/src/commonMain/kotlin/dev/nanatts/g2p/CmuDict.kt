package dev.nanatts.g2p

import dev.nanatts.io.Gzip
import dev.nanatts.io.ResourceSource
import dev.nanatts.util.splitJava

/**
 * CMU Pronouncing Dictionary lookup. The dictionary is kept as one byte array of sorted
 * `word<TAB>PHONES\n` lines plus line offsets and searched by binary search, which keeps memory
 * use around 4 MB (important on Android).
 */
class CmuDict private constructor(private val data: ByteArray) {

    private val lines: IntArray

    init {
        var count = 0
        for (b in data) {
            if (b == NEWLINE) {
                count++
            }
        }
        lines = IntArray(count)
        var n = 0
        var start = 0
        for (i in data.indices) {
            if (data[i] == NEWLINE) {
                lines[n++] = start
                start = i + 1
            }
        }
    }

    val size: Int
        get() = lines.size

    /** ARPAbet phones with stress digits (e.g. `[HH, AH0, L, OW1]`), or null if not found. */
    fun lookup(word: String): Array<String>? {
        val key = word.lowercase().encodeToByteArray()
        var lo = 0
        var hi = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(lines[mid], key)
            if (c == 0) {
                val s = lines[mid] + key.size + 1
                var e = s
                while (data[e] != NEWLINE) {
                    e++
                }
                return data.decodeToString(s, e).splitJava(' ').toTypedArray()
            }
            if (c < 0) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return null
    }

    operator fun contains(word: String): Boolean = lookup(word) != null

    /** Compares the word at [off] (terminated by TAB) with [key], as unsigned bytes. */
    private fun compare(off: Int, key: ByteArray): Int {
        var i = 0
        while (true) {
            val a = data[off + i].toInt() and 0xFF
            val endA = a == '\t'.code
            val endB = i == key.size
            if (endA || endB) {
                return if (endA && endB) 0 else if (endA) -1 else 1
            }
            val b = key[i].toInt() and 0xFF
            if (a != b) {
                return a - b
            }
            i++
        }
    }

    companion object {
        const val RESOURCE = "dev/nanatts/g2p/cmudict.tsv.gz"
        private const val NEWLINE = '\n'.code.toByte()

        /** Loads [RESOURCE] (gzip-compressed) from [source]. */
        fun load(source: ResourceSource): CmuDict = read(source.read(RESOURCE))

        /** Reads the contents of a `cmudict.tsv.gz` file. */
        fun read(gzipped: ByteArray): CmuDict = CmuDict(Gzip.decompress(gzipped))
    }
}
