package dev.nanatts.io;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Minimal RIFF/WAVE reader and writer (PCM 8/16/24/32-bit and IEEE float, mixed down to mono). */
public final class WavIO {

    private WavIO() {
    }

    public static AudioClip read(InputStream in) throws IOException {
        byte[] data = readAll(in);
        if (data.length < 12 || !tag(data, 0, "RIFF") || !tag(data, 8, "WAVE")) {
            throw new IOException("not a RIFF/WAVE file");
        }
        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int pos = 12;
        while (pos + 8 <= data.length) {
            int size = le32(data, pos + 4);
            int body = pos + 8;
            if (size < 0 || body + size > data.length) {
                size = data.length - body; // tolerate truncated files
            }
            if (tag(data, pos, "fmt ")) {
                format = le16(data, body);
                channels = le16(data, body + 2);
                sampleRate = le32(data, body + 4);
                bits = le16(data, body + 14);
                if (format == 0xFFFE && size >= 26) {
                    format = le16(data, body + 24); // WAVE_FORMAT_EXTENSIBLE sub-format
                }
            } else if (tag(data, pos, "data")) {
                if (format < 0) {
                    throw new IOException("data chunk before fmt chunk");
                }
                return new AudioClip(decode(data, body, size, format, channels, bits), sampleRate);
            }
            pos = body + size + (size & 1);
        }
        throw new IOException("no data chunk");
    }

    private static float[] decode(byte[] d, int off, int size, int format, int channels, int bits)
            throws IOException {
        int bytesPerSample = bits / 8;
        if (channels < 1 || bytesPerSample < 1) {
            throw new IOException("bad wav format");
        }
        int frames = size / (bytesPerSample * channels);
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                int p = off + (f * channels + c) * bytesPerSample;
                sum += sample(d, p, format, bits);
            }
            out[f] = (float) (sum / channels);
        }
        return out;
    }

    private static double sample(byte[] d, int p, int format, int bits) throws IOException {
        if (format == 3) {
            if (bits == 32) {
                return Float.intBitsToFloat(le32(d, p));
            }
            if (bits == 64) {
                long lo = le32(d, p) & 0xFFFFFFFFL;
                long hi = le32(d, p + 4) & 0xFFFFFFFFL;
                return Double.longBitsToDouble(lo | (hi << 32));
            }
        } else if (format == 1) {
            switch (bits) {
                case 8:
                    return ((d[p] & 0xFF) - 128) / 128.0;
                case 16:
                    return (short) le16(d, p) / 32768.0;
                case 24:
                    return (((d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | (d[p + 2] << 16))) / 8388608.0;
                case 32:
                    return le32(d, p) / 2147483648.0;
                default:
                    break;
            }
        }
        throw new IOException("unsupported wav encoding: format=" + format + " bits=" + bits);
    }

    /** Writes 16-bit PCM mono. */
    public static void write(AudioClip clip, OutputStream out) throws IOException {
        short[] pcm = clip.toPcm16();
        int dataBytes = pcm.length * 2;
        byte[] buf = new byte[44 + dataBytes];
        put(buf, 0, "RIFF");
        putLe32(buf, 4, 36 + dataBytes);
        put(buf, 8, "WAVE");
        put(buf, 12, "fmt ");
        putLe32(buf, 16, 16);
        putLe16(buf, 20, 1);
        putLe16(buf, 22, 1);
        putLe32(buf, 24, clip.sampleRate());
        putLe32(buf, 28, clip.sampleRate() * 2);
        putLe16(buf, 32, 2);
        putLe16(buf, 34, 16);
        put(buf, 36, "data");
        putLe32(buf, 40, dataBytes);
        for (int i = 0; i < pcm.length; i++) {
            putLe16(buf, 44 + 2 * i, pcm[i]);
        }
        out.write(buf);
        out.flush();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) {
            bos.write(b, 0, n);
        }
        if (bos.size() == 0) {
            throw new EOFException("empty stream");
        }
        return bos.toByteArray();
    }

    private static boolean tag(byte[] d, int p, String t) {
        for (int i = 0; i < 4; i++) {
            if (d[p + i] != t.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
    }

    private static void put(byte[] d, int p, String t) {
        for (int i = 0; i < 4; i++) {
            d[p + i] = (byte) t.charAt(i);
        }
    }

    private static void putLe16(byte[] d, int p, int v) {
        d[p] = (byte) v;
        d[p + 1] = (byte) (v >> 8);
    }

    private static void putLe32(byte[] d, int p, int v) {
        putLe16(d, p, v);
        putLe16(d, p + 2, v >> 16);
    }
}
