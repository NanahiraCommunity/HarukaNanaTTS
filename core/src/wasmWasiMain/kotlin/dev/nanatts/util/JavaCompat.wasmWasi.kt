package dev.nanatts.util

import kotlin.math.ln

// Kotlin/Wasm's ln is a port of fdlibm, the algorithm behind StrictMath.log
internal actual fun strictLn(x: Double): Double = ln(x)

internal actual fun fastExp(x: Double): Double = PortableMath.exp(x)

internal actual fun fastLn(x: Double): Double = PortableMath.ln(x)

internal actual fun fastSin(x: Double): Double = PortableMath.sin(x)

internal actual fun fastCos(x: Double): Double = PortableMath.cos(x)

private const val LN10 = 2.302585092994045684

internal actual fun fastPow10(x: Double): Double = PortableMath.exp(x * LN10)
