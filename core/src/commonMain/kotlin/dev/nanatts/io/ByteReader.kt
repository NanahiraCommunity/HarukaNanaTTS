package dev.nanatts.io

/** Big-endian reader with the semantics of `java.io.DataInputStream` (the voice pack format). */
internal class ByteReader(private val data: ByteArray) {

    private var pos = 0

    private fun need(n: Int) {
        if (pos + n > data.size) {
            throw InvalidDataException("unexpected end of data")
        }
    }

    fun readUnsignedByte(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun readUnsignedShort(): Int {
        need(2)
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun readShort(): Short = readUnsignedShort().toShort()

    fun readChar(): Char = readUnsignedShort().toChar()

    fun readInt(): Int {
        need(4)
        var v = 0
        for (i in 0 until 4) {
            v = (v shl 8) or (data[pos + i].toInt() and 0xFF)
        }
        pos += 4
        return v
    }

    fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xFFFFFFFFL)

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readFully(out: ByteArray) {
        need(out.size)
        data.copyInto(out, 0, pos, pos + out.size)
        pos += out.size
    }

    /** `DataInputStream.readUTF`: a 2-byte length followed by modified UTF-8. */
    fun readUTF(): String {
        val len = readUnsignedShort()
        need(len)
        val end = pos + len
        val sb = StringBuilder(len)
        while (pos < end) {
            val a = data[pos++].toInt() and 0xFF
            when (a shr 4) {
                in 0..7 -> sb.append(a.toChar())
                12, 13 -> {
                    val b = continuation(end)
                    sb.append((((a and 0x1F) shl 6) or b).toChar())
                }
                14 -> {
                    val b = continuation(end)
                    val c = continuation(end)
                    sb.append((((a and 0x0F) shl 12) or (b shl 6) or c).toChar())
                }
                else -> throw InvalidDataException("malformed modified UTF-8")
            }
        }
        return sb.toString()
    }

    private fun continuation(end: Int): Int {
        if (pos >= end) {
            throw InvalidDataException("malformed modified UTF-8")
        }
        val b = data[pos++].toInt() and 0xFF
        if (b and 0xC0 != 0x80) {
            throw InvalidDataException("malformed modified UTF-8")
        }
        return b and 0x3F
    }
}
