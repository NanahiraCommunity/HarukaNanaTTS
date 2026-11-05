package dev.nanatts.util

internal actual fun strictLn(x: Double): Double = StrictMath.log(x)

internal actual fun fastExp(x: Double): Double = kotlin.math.exp(x)

internal actual fun fastLn(x: Double): Double = kotlin.math.ln(x)

internal actual fun fastSin(x: Double): Double = kotlin.math.sin(x)

internal actual fun fastCos(x: Double): Double = kotlin.math.cos(x)

internal actual fun fastPow10(x: Double): Double = Math.pow(10.0, x)
