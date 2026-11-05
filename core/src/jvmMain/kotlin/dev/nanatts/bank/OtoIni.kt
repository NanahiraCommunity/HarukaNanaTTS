package dev.nanatts.bank

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/** Parser for UTAU `oto.ini` files (Shift-JIS / windows-31j encoded). */
object OtoIni {

    fun parse(input: InputStream, charset: Charset = Charset.forName("windows-31j")): List<OtoEntry> {
        val out = ArrayList<OtoEntry>()
        var lineNo = 0
        input.bufferedReader(charset).forEachLine { raw ->
            lineNo++
            val line = raw.trim()
            if (line.isEmpty()) {
                return@forEachLine
            }
            val eq = line.indexOf('=')
            if (eq <= 0) {
                throw IOException("oto.ini line $lineNo: missing '='")
            }
            val file = line.substring(0, eq)
            val f = line.substring(eq + 1).split(',')
            if (f.size < 6) {
                throw IOException("oto.ini line $lineNo: expected 6 fields")
            }
            val alias = f[0].ifEmpty { stripExtension(file) }
            try {
                out.add(OtoEntry(file, alias, num(f[1]), num(f[2]), num(f[3]), num(f[4]), num(f[5])))
            } catch (e: NumberFormatException) {
                throw IOException("oto.ini line $lineNo: ${e.message}", e)
            }
        }
        return out
    }

    private fun num(s: String): Double {
        val t = s.trim()
        return if (t.isEmpty()) 0.0 else t.toDouble()
    }

    private fun stripExtension(file: String): String {
        val dot = file.lastIndexOf('.')
        return if (dot > 0) file.substring(0, dot) else file
    }
}
