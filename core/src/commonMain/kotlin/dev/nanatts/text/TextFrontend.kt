package dev.nanatts.text

/** Converts text of one language into phrases of annotated morae. */
fun interface TextFrontend {

    fun analyze(text: String): List<Phrase>
}
