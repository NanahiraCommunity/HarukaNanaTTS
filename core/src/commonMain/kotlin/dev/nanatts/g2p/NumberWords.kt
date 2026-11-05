package dev.nanatts.g2p

/** Spells out numbers in English words. */
internal object NumberWords {

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES = arrayOf("", "thousand", "million", "billion", "trillion", "quadrillion")

    fun cardinal(value: Long): String {
        if (value < 0) {
            return "minus " + cardinal(-value)
        }
        if (value < 20) {
            return ONES[value.toInt()]
        }
        var n = value
        var scale = 0
        var result = ""
        while (n > 0) {
            val chunk = (n % 1000).toInt()
            if (chunk > 0) {
                var words = below1000(chunk)
                if (SCALES[scale].isNotEmpty()) {
                    words += " " + SCALES[scale]
                }
                result = if (result.isEmpty()) words else "$words $result"
            }
            n /= 1000
            scale++
        }
        return result
    }

    private fun below1000(value: Int): String {
        var n = value
        val sb = StringBuilder()
        if (n >= 100) {
            sb.append(ONES[n / 100]).append(" hundred")
            n %= 100
            if (n > 0) {
                sb.append(' ')
            }
        }
        if (n >= 20) {
            sb.append(TENS[n / 10])
            if (n % 10 > 0) {
                sb.append(' ').append(ONES[n % 10])
            }
        } else if (n > 0) {
            sb.append(ONES[n])
        }
        return sb.toString()
    }

    /** "1984" -> "nineteen eighty four", "2005" -> "two thousand five", "1900" -> "nineteen hundred". */
    fun year(y: Int): String {
        if (y in 2000..2009) {
            return cardinal(y.toLong())
        }
        val hi = y / 100
        val lo = y % 100
        if (lo == 0) {
            return cardinal(hi.toLong()) + " hundred"
        }
        return cardinal(hi.toLong()) + " " + if (lo < 10) "oh " + cardinal(lo.toLong()) else cardinal(lo.toLong())
    }

    fun ordinal(n: Long): String {
        val c = cardinal(n)
        val sp = c.lastIndexOf(' ')
        val head = if (sp < 0) "" else c.substring(0, sp + 1)
        val last = if (sp < 0) c else c.substring(sp + 1)
        return when (last) {
            "one" -> head + "first"
            "two" -> head + "second"
            "three" -> head + "third"
            "five" -> head + "fifth"
            "eight" -> head + "eighth"
            "nine" -> head + "ninth"
            "twelve" -> head + "twelfth"
            else -> if (last.endsWith("y")) head + last.substring(0, last.length - 1) + "ieth" else head + last + "th"
        }
    }

    /** Reads each digit: "0042" -> "zero zero four two". */
    fun digits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in '0'..'9') {
                if (sb.isNotEmpty()) {
                    sb.append(' ')
                }
                sb.append(ONES[c - '0'])
            }
        }
        return sb.toString()
    }
}
