package dev.nanatts.util

import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Java semantics that the Kotlin standard library does not share on every platform. The synthesis is
// deterministic, so these keep the output identical to earlier Java builds.

/** `Math.round(double)`: ties towards positive infinity, NaN is 0. */
internal fun jround(x: Double): Int = if (x.isNaN()) 0 else x.roundToInt()

/** `Math.round(double)` to long. */
internal fun jroundLong(x: Double): Long = if (x.isNaN()) 0 else x.roundToLong()

/** `Math.round(float)`. */
internal fun jround(x: Float): Int = if (x.isNaN()) 0 else x.roundToInt()

/** `String.hashCode()`. */
internal fun javaHash(s: String): Int {
    var h = 0
    for (c in s) {
        h = 31 * h + c.code
    }
    return h
}

/**
 * `Character.isWhitespace(char)`: unlike Kotlin's `isWhitespace`, non-breaking spaces (U+00A0, U+2007, U+202F)
 * are not whitespace.
 */
internal fun isJavaWhitespace(c: Char): Boolean = when {
    c == '\u00A0' || c == '\u2007' || c == '\u202F' -> false
    c in '\t'..'\r' || c in '\u001C'..'\u001F' -> true
    else -> c.category == CharCategory.SPACE_SEPARATOR || c.category == CharCategory.LINE_SEPARATOR
        || c.category == CharCategory.PARAGRAPH_SEPARATOR
}

/** `String.split(String regex)` with a literal one-character separator: trailing empty strings are removed. */
internal fun String.splitJava(separator: Char): List<String> {
    if (indexOf(separator) < 0) {
        return listOf(this)
    }
    val parts = split(separator)
    var end = parts.size
    while (end > 0 && parts[end - 1].isEmpty()) {
        end--
    }
    return if (end == parts.size) parts else parts.subList(0, end)
}

/** `StrictMath.log`, which `java.util.Random` uses (`Math.log` may differ in the last bit on the JVM). */
internal expect fun strictLn(x: Double): Double

/** `Integer.reverse(int)`. */
internal fun reverseBits(v: Int): Int {
    var x = v
    var r = 0
    for (i in 0 until 32) {
        r = (r shl 1) or (x and 1)
        x = x ushr 1
    }
    return r
}

// Transcendental functions of the synthesis hot loops: kotlin.math on the JVM (CPU intrinsics, and output
// identical to earlier Java builds), PortableMath on WASI (Kotlin/Wasm's own versions are much slower).

internal expect fun fastExp(x: Double): Double

internal expect fun fastLn(x: Double): Double

internal expect fun fastSin(x: Double): Double

internal expect fun fastCos(x: Double): Double

/** 10^x. */
internal expect fun fastPow10(x: Double): Double
