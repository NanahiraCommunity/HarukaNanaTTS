package dev.nanatts.io

/** Gzip decompression (RFC 1952 / RFC 1951), for voice packs and the dictionary on every platform. */
object Gzip {

    /** Decompresses all members of a gzip file, checking their CRC and length. */
    fun decompress(data: ByteArray): ByteArray {
        val expected = if (data.size >= 4) le32(data, data.size - 4).toLong() and 0xFFFFFFFFL else 0L
        val out = Output(if (expected in 1..Int.MAX_VALUE.toLong()) expected.toInt() else 1 shl 16)
        var pos = 0
        do {
            pos = member(data, pos, out)
        } while (pos < data.size && data[pos] == 0x1F.toByte())
        return out.toByteArray()
    }

    private const val FHCRC = 2
    private const val FEXTRA = 4
    private const val FNAME = 8
    private const val FCOMMENT = 16

    private fun member(data: ByteArray, start: Int, out: Output): Int {
        if (data.size - start < 18 || data[start] != 0x1F.toByte() || data[start + 1] != 0x8B.toByte()) {
            throw InvalidDataException("not in gzip format")
        }
        if (data[start + 2].toInt() != 8) {
            throw InvalidDataException("unsupported gzip compression method")
        }
        val flags = data[start + 3].toInt()
        var pos = start + 10
        if (flags and FEXTRA != 0) {
            pos += 2 + ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8))
        }
        if (flags and FNAME != 0) {
            while (data[pos++].toInt() != 0) {
                // skip the zero-terminated file name
            }
        }
        if (flags and FCOMMENT != 0) {
            while (data[pos++].toInt() != 0) {
                // skip the zero-terminated comment
            }
        }
        if (flags and FHCRC != 0) {
            pos += 2
        }
        val from = out.size
        pos = Inflater(data, pos, out).inflate()
        if (pos + 8 > data.size) {
            throw InvalidDataException("truncated gzip data")
        }
        if (le32(data, pos) != out.crc32(from) || le32(data, pos + 4) != out.size - from) {
            throw InvalidDataException("corrupt gzip data (CRC or length mismatch)")
        }
        return pos + 8
    }

    private fun le32(d: ByteArray, p: Int): Int =
        (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or
            ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)

    private class Output(capacity: Int) {
        var buf = ByteArray(maxOf(capacity, 16))
        var size = 0

        fun ensure(extra: Int) {
            if (size + extra > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
            }
        }

        fun crc32(from: Int): Int {
            var c = -1
            for (i in from until size) {
                c = CRC_TABLE[(c xor buf[i].toInt()) and 0xFF] xor (c ushr 8)
            }
            return c.inv()
        }

        fun toByteArray(): ByteArray = if (size == buf.size) buf else buf.copyOf(size)
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        c
    }

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
        6145, 8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** Canonical Huffman code as a lookup table indexed by the next [bits] input bits (LSB first). */
    private class Huffman(lengths: IntArray, count: Int) {
        val bits: Int
        /** `symbol shl 4 or length`, 0 for bit patterns that are not a code. */
        val table: IntArray

        init {
            var max = 0
            for (i in 0 until count) {
                max = maxOf(max, lengths[i])
            }
            bits = max
            table = IntArray(1 shl max)
            val perLength = IntArray(16)
            for (i in 0 until count) {
                perLength[lengths[i]]++
            }
            perLength[0] = 0
            val next = IntArray(16)
            var code = 0
            for (len in 1..15) {
                code = (code + perLength[len - 1]) shl 1
                next[len] = code
            }
            for (sym in 0 until count) {
                val len = lengths[sym]
                if (len == 0) {
                    continue
                }
                val c = next[len]++
                if (c >= 1 shl len) {
                    throw InvalidDataException("invalid deflate code lengths")
                }
                var rev = 0
                for (i in 0 until len) {
                    rev = rev or (((c ushr i) and 1) shl (len - 1 - i))
                }
                var k = rev
                while (k < table.size) {
                    table[k] = (sym shl 4) or len
                    k += 1 shl len
                }
            }
        }
    }

    private val FIXED_LITERALS = Huffman(IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 }, 288)
    private val FIXED_DISTANCES = Huffman(IntArray(30) { 5 }, 30)

    private class Inflater(private val data: ByteArray, private var pos: Int, private val out: Output) {
        private var bitBuf = 0
        private var bitCount = 0

        private fun need(n: Int) {
            while (bitCount < n) {
                // past the end, pad with zeros (a code peek may look further than the stream ends)
                val b = if (pos < data.size) data[pos].toInt() and 0xFF else 0
                pos++
                bitBuf = bitBuf or (b shl bitCount)
                bitCount += 8
            }
        }

        private fun bits(n: Int): Int {
            if (n == 0) {
                return 0
            }
            need(n)
            val v = bitBuf and ((1 shl n) - 1)
            bitBuf = bitBuf ushr n
            bitCount -= n
            return v
        }

        private fun decode(h: Huffman): Int {
            need(h.bits)
            val e = h.table[bitBuf and ((1 shl h.bits) - 1)]
            if (e == 0) {
                throw InvalidDataException("invalid deflate code")
            }
            val len = e and 15
            bitBuf = bitBuf ushr len
            bitCount -= len
            return e ushr 4
        }

        /** Inflates one deflate stream; returns the position of the first byte after it. */
        fun inflate(): Int {
            do {
                val last = bits(1) == 1
                when (bits(2)) {
                    0 -> stored()
                    1 -> codes(FIXED_LITERALS, FIXED_DISTANCES)
                    2 -> dynamic()
                    else -> throw InvalidDataException("invalid deflate block type")
                }
            } while (!last)
            // return unused whole bytes of the bit buffer
            val end = pos - bitCount / 8
            if (end > data.size) {
                throw InvalidDataException("truncated deflate data")
            }
            return end
        }

        private fun stored() {
            bits(bitCount % 8)
            val len = bits(16)
            val nlen = bits(16)
            if (len != nlen.inv() and 0xFFFF) {
                throw InvalidDataException("corrupt stored deflate block")
            }
            pos -= bitCount / 8
            bitBuf = 0
            bitCount = 0
            if (pos + len > data.size) {
                throw InvalidDataException("truncated deflate data")
            }
            out.ensure(len)
            data.copyInto(out.buf, out.size, pos, pos + len)
            out.size += len
            pos += len
        }

        private fun dynamic() {
            val nlen = bits(5) + 257
            val ndist = bits(5) + 1
            val ncode = bits(4) + 4
            val codeLengths = IntArray(19)
            for (i in 0 until ncode) {
                codeLengths[CODE_LENGTH_ORDER[i]] = bits(3)
            }
            val lengthCode = Huffman(codeLengths, 19)
            val lengths = IntArray(nlen + ndist)
            var i = 0
            while (i < nlen + ndist) {
                val sym = decode(lengthCode)
                if (sym < 16) {
                    lengths[i++] = sym
                    continue
                }
                val (value, repeat) = when (sym) {
                    16 -> {
                        if (i == 0) {
                            throw InvalidDataException("deflate length repeat without a previous length")
                        }
                        lengths[i - 1] to 3 + bits(2)
                    }
                    17 -> 0 to 3 + bits(3)
                    else -> 0 to 11 + bits(7)
                }
                if (i + repeat > lengths.size) {
                    throw InvalidDataException("too many deflate code lengths")
                }
                repeat(repeat) { lengths[i++] = value }
            }
            if (lengths[256] == 0) {
                throw InvalidDataException("deflate block without end code")
            }
            codes(Huffman(lengths, nlen), Huffman(lengths.copyOfRange(nlen, nlen + ndist), ndist))
        }

        private fun codes(literals: Huffman, distances: Huffman) {
            while (true) {
                val sym = decode(literals)
                if (sym < 256) {
                    out.ensure(1)
                    out.buf[out.size++] = sym.toByte()
                    continue
                }
                if (sym == 256) {
                    return
                }
                val li = sym - 257
                if (li >= 29) {
                    throw InvalidDataException("invalid deflate length code")
                }
                val len = LENGTH_BASE[li] + bits(LENGTH_EXTRA[li])
                if (distances.bits == 0) {
                    throw InvalidDataException("deflate distance without distance codes")
                }
                val di = decode(distances)
                if (di >= 30) {
                    throw InvalidDataException("invalid deflate distance code")
                }
                val dist = DIST_BASE[di] + bits(DIST_EXTRA[di])
                if (dist > out.size) {
                    throw InvalidDataException("deflate distance too far back")
                }
                out.ensure(len)
                val buf = out.buf
                var from = out.size - dist
                var to = out.size
                repeat(len) { buf[to++] = buf[from++] }
                out.size = to
            }
        }
    }
}
