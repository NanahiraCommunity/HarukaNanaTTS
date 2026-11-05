package dev.nanatts.text

/**
 * Unicode normalisation (UAX #15) on every platform, with the same results as `java.text.Normalizer` of the JDK
 * the tables in [UnicodeData] were generated from.
 */
object Normalizer {

    enum class Form { NFC, NFD, NFKC, NFKD }

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    private class Tables(
        val combiningClass: HashMap<Int, Int>,
        val canonical: HashMap<Int, IntArray>,
        val compatibility: HashMap<Int, IntArray>,
        /** (first shl 21 or second) to composite. */
        val compositions: HashMap<Long, Int>,
    )

    private val tables: Tables by lazy {
        val ccc = HashMap<Int, Int>()
        parse(UnicodeData.COMBINING_CLASSES) { cp, v -> ccc[cp] = v[0] }
        val canonical = HashMap<Int, IntArray>()
        parse(UnicodeData.CANONICAL) { cp, v -> canonical[cp] = v }
        val compat = HashMap<Int, IntArray>()
        parse(UnicodeData.COMPATIBILITY) { cp, v -> compat[cp] = v }
        val comp = HashMap<Long, Int>()
        parse(UnicodeData.COMPOSITIONS) { cp, v -> comp[key(v[0], v[1])] = cp }
        Tables(ccc, canonical, compat, comp)
    }

    private fun parse(chunks: Array<String>, put: (Int, IntArray) -> Unit) {
        for (chunk in chunks) {
            for (entry in chunk.split(';')) {
                if (entry.isEmpty()) {
                    continue // chunks end with a separator
                }
                val colon = entry.indexOf(':')
                val values = entry.substring(colon + 1).split(' ')
                put(entry.substring(0, colon).toInt(16), IntArray(values.size) { values[it].toInt(16) })
            }
        }
    }

    private fun key(first: Int, second: Int): Long = (first.toLong() shl 21) or second.toLong()

    fun normalize(text: CharSequence, form: Form): String {
        if (isAscii(text)) {
            return text.toString() // ASCII is invariant under every form
        }
        val compatibility = form == Form.NFKC || form == Form.NFKD
        var cps = decompose(text, compatibility)
        if (form == Form.NFC || form == Form.NFKC) {
            cps = compose(cps)
        }
        val sb = StringBuilder(cps.size)
        for (cp in cps) {
            appendCodePoint(sb, cp)
        }
        return sb.toString()
    }

    fun combiningClass(cp: Int): Int = tables.combiningClass[cp] ?: 0

    private fun isAscii(s: CharSequence): Boolean {
        for (i in s.indices) {
            if (s[i].code >= 0x80) {
                return false
            }
        }
        return true
    }

    private fun decompose(text: CharSequence, compatibility: Boolean): IntArray {
        val t = tables
        val out = IntBuffer(text.length + 8)
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            i += if (cp >= 0x10000) 2 else 1
            val s = cp - S_BASE
            if (s in 0 until S_COUNT) {
                out.add(L_BASE + s / N_COUNT)
                out.add(V_BASE + (s % N_COUNT) / T_COUNT)
                if (s % T_COUNT != 0) {
                    out.add(T_BASE + s % T_COUNT)
                }
                continue
            }
            val d = (if (compatibility) t.compatibility[cp] else null) ?: t.canonical[cp]
            if (d == null) {
                out.add(cp)
            } else {
                for (c in d) {
                    out.add(c)
                }
            }
        }
        val cps = out.toArray()
        reorder(cps)
        return cps
    }

    /** Canonical ordering: stable sort of each run of non-starters by combining class. */
    private fun reorder(cps: IntArray) {
        var i = 0
        while (i < cps.size) {
            if (combiningClass(cps[i]) == 0) {
                i++
                continue
            }
            var j = i
            while (j < cps.size && combiningClass(cps[j]) != 0) {
                j++
            }
            // insertion sort keeps equal classes in order
            for (a in i + 1 until j) {
                val v = cps[a]
                val cv = combiningClass(v)
                var b = a - 1
                while (b >= i && combiningClass(cps[b]) > cv) {
                    cps[b + 1] = cps[b]
                    b--
                }
                cps[b + 1] = v
            }
            i = j
        }
    }

    private fun compose(cps: IntArray): IntArray {
        if (cps.isEmpty()) {
            return cps
        }
        val t = tables
        var starterPos = 0
        var starter = cps[0]
        var lastClass = combiningClass(starter)
        if (lastClass != 0) {
            lastClass = 256 // a leading non-starter blocks everything after it
        }
        var out = 1
        for (i in 1 until cps.size) {
            val ch = cps[i]
            val chClass = combiningClass(ch)
            val composite = composePair(t, starter, ch)
            if (composite >= 0 && (lastClass < chClass || lastClass == 0)) {
                cps[starterPos] = composite
                starter = composite
                continue
            }
            if (chClass == 0) {
                starterPos = out
                starter = ch
            }
            lastClass = chClass
            cps[out++] = ch
        }
        return cps.copyOf(out)
    }

    private fun composePair(t: Tables, a: Int, b: Int): Int {
        val l = a - L_BASE
        if (l in 0 until L_COUNT) {
            val v = b - V_BASE
            return if (v in 0 until V_COUNT) S_BASE + (l * V_COUNT + v) * T_COUNT else -1
        }
        val s = a - S_BASE
        if (s in 0 until S_COUNT && s % T_COUNT == 0) {
            val tIndex = b - T_BASE
            return if (tIndex in 1 until T_COUNT) a + tIndex else -1
        }
        return t.compositions[key(a, b)] ?: -1
    }

    private fun codePointAt(s: CharSequence, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        }
        return c.code
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp < 0x10000) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    private class IntBuffer(capacity: Int) {
        private var data = IntArray(capacity)
        private var size = 0

        fun add(v: Int) {
            if (size == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = v
        }

        fun toArray(): IntArray = data.copyOf(size)
    }
}
