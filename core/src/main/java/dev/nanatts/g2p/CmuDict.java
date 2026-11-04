package dev.nanatts.g2p;

import dev.nanatts.io.ResourceSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;

/**
 * CMU Pronouncing Dictionary lookup. The dictionary is kept as one byte array of sorted
 * {@code word<TAB>PHONES\n} lines plus line offsets and searched by binary search, which keeps memory
 * use around 4 MB (important on Android).
 */
public final class CmuDict {

    public static final String RESOURCE = "dev/nanatts/g2p/cmudict.tsv.gz";

    private final byte[] data;
    private final int[] lines;

    private CmuDict(byte[] data) {
        this.data = data;
        int count = 0;
        for (byte b : data) {
            if (b == '\n') {
                count++;
            }
        }
        lines = new int[count];
        int n = 0;
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines[n++] = start;
                start = i + 1;
            }
        }
    }

    public static CmuDict load(ResourceSource source) {
        try (InputStream in = new GZIPInputStream(source.open(RESOURCE), 1 << 16)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4 << 20);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            return new CmuDict(bos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load " + RESOURCE, e);
        }
    }

    public int size() {
        return lines.length;
    }

    /** ARPAbet phones with stress digits (e.g. {@code [HH, AH0, L, OW1]}), or null if not found. */
    public String[] lookup(String word) {
        byte[] key = word.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int lo = 0;
        int hi = lines.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = compare(lines[mid], key);
            if (c == 0) {
                int s = lines[mid] + key.length + 1;
                int e = s;
                while (data[e] != '\n') {
                    e++;
                }
                return new String(data, s, e - s, java.nio.charset.StandardCharsets.US_ASCII).split(" ");
            }
            if (c < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return null;
    }

    public boolean contains(String word) {
        return lookup(word) != null;
    }

    /** Compares the word at {@code off} (terminated by TAB) with {@code key}, as unsigned bytes. */
    private int compare(int off, byte[] key) {
        int i = 0;
        while (true) {
            int a = data[off + i] & 0xFF;
            boolean endA = a == '\t';
            boolean endB = i == key.length;
            if (endA || endB) {
                return endA && endB ? 0 : endA ? -1 : 1;
            }
            int b = key[i] & 0xFF;
            if (a != b) {
                return a - b;
            }
            i++;
        }
    }
}
