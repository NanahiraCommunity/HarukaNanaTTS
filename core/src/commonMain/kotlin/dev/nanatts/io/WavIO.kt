package dev.nanatts.io

/** Minimal RIFF/WAVE reader and writer (PCM 8/16/24/32-bit and IEEE float, mixed down to mono). */
object WavIO {

    fun read(data: ByteArray): AudioClip {
        if (data.size < 12 || !tag(data, 0, "RIFF") || !tag(data, 8, "WAVE")) {
            throw InvalidDataException("not a RIFF/WAVE file")
        }
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var pos = 12
        while (pos + 8 <= data.size) {
            var size = le32(data, pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > data.size) {
                size = data.size - body // tolerate truncated files
            }
            if (tag(data, pos, "fmt ")) {
                format = le16(data, body)
                channels = le16(data, body + 2)
                sampleRate = le32(data, body + 4)
                bits = le16(data, body + 14)
                if (format == 0xFFFE && size >= 26) {
                    format = le16(data, body + 24) // WAVE_FORMAT_EXTENSIBLE sub-format
                }
            } else if (tag(data, pos, "data")) {
                if (format < 0) {
                    throw InvalidDataException("data chunk before fmt chunk")
                }
                return AudioClip(decode(data, body, size, format, channels, bits), sampleRate)
            }
            pos = body + size + (size and 1)
        }
        throw InvalidDataException("no data chunk")
    }

    private fun decode(d: ByteArray, off: Int, size: Int, format: Int, channels: Int, bits: Int): FloatArray {
        val bytesPerSample = bits / 8
        if (channels < 1 || bytesPerSample < 1) {
            throw InvalidDataException("bad wav format")
        }
        val frames = size / (bytesPerSample * channels)
        return FloatArray(frames) { f ->
            var sum = 0.0
            for (c in 0 until channels) {
                sum += sample(d, off + (f * channels + c) * bytesPerSample, format, bits)
            }
            (sum / channels).toFloat()
        }
    }

    private fun sample(d: ByteArray, p: Int, format: Int, bits: Int): Double {
        if (format == 3) {
            if (bits == 32) {
                return Float.fromBits(le32(d, p)).toDouble()
            }
            if (bits == 64) {
                val lo = le32(d, p).toLong() and 0xFFFFFFFFL
                val hi = le32(d, p + 4).toLong() and 0xFFFFFFFFL
                return Double.fromBits(lo or (hi shl 32))
            }
        } else if (format == 1) {
            when (bits) {
                8 -> return ((d[p].toInt() and 0xFF) - 128) / 128.0
                16 -> return le16(d, p).toShort() / 32768.0
                24 -> return ((d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() shl 16)) /
                    8388608.0
                32 -> return le32(d, p) / 2147483648.0
            }
        }
        throw InvalidDataException("unsupported wav encoding: format=$format bits=$bits")
    }

    /** 16-bit PCM mono WAV file contents. */
    fun encode(clip: AudioClip): ByteArray {
        val pcm = clip.toPcm16()
        val dataBytes = pcm.size * 2
        val buf = ByteArray(44 + dataBytes)
        put(buf, 0, "RIFF")
        putLe32(buf, 4, 36 + dataBytes)
        put(buf, 8, "WAVE")
        put(buf, 12, "fmt ")
        putLe32(buf, 16, 16)
        putLe16(buf, 20, 1)
        putLe16(buf, 22, 1)
        putLe32(buf, 24, clip.sampleRate)
        putLe32(buf, 28, clip.sampleRate * 2)
        putLe16(buf, 32, 2)
        putLe16(buf, 34, 16)
        put(buf, 36, "data")
        putLe32(buf, 40, dataBytes)
        for (i in pcm.indices) {
            putLe16(buf, 44 + 2 * i, pcm[i].toInt())
        }
        return buf
    }

    private fun tag(d: ByteArray, p: Int, t: String): Boolean {
        for (i in 0 until 4) {
            if (d[p + i].toInt() != t[i].code) {
                return false
            }
        }
        return true
    }

    private fun le16(d: ByteArray, p: Int): Int = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)

    private fun le32(d: ByteArray, p: Int): Int =
        (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) or
            ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 3].toInt() and 0xFF) shl 24)

    private fun put(d: ByteArray, p: Int, t: String) {
        for (i in 0 until 4) {
            d[p + i] = t[i].code.toByte()
        }
    }

    private fun putLe16(d: ByteArray, p: Int, v: Int) {
        d[p] = v.toByte()
        d[p + 1] = (v shr 8).toByte()
    }

    private fun putLe32(d: ByteArray, p: Int, v: Int) {
        putLe16(d, p, v)
        putLe16(d, p + 2, v shr 16)
    }
}
