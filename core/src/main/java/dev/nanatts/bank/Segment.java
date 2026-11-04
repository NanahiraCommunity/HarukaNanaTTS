package dev.nanatts.bank;

/**
 * Analysed frames of one region of a voicebank recording, stored quantised to keep packs small
 * and memory use low on mobile devices. Frames are decoded on demand.
 */
public final class Segment {

    /** Envelope quantisation step in dB (one byte per grid point below the per-frame maximum). */
    static final float ENV_STEP_DB = 0.5f;
    static final float ENV_TOP_SCALE = 50f;
    static final float AP_STEP_DB = 0.25f;
    static final float F0_SCALE = 16f;

    final String file;
    final double startSec;
    final int frames;
    final int melBins;
    final int apBands;
    final char[] f0q;
    final short[] envTop;
    final byte[] env;
    final byte[] ap;

    Segment(String file, double startSec, int frames, int melBins, int apBands,
            char[] f0q, short[] envTop, byte[] env, byte[] ap) {
        this.file = file;
        this.startSec = startSec;
        this.frames = frames;
        this.melBins = melBins;
        this.apBands = apBands;
        this.f0q = f0q;
        this.envTop = envTop;
        this.env = env;
        this.ap = ap;
    }

    /** Quantises analysed frames. {@code envDb[frame][melBin]}, {@code apDb[frame][band]} (&lt;= 0). */
    public static Segment encode(String file, double startSec, double[] f0, float[][] envDb, float[][] apDb) {
        int frames = f0.length;
        int mel = envDb[0].length;
        int bands = apDb[0].length;
        char[] f0q = new char[frames];
        short[] top = new short[frames];
        byte[] env = new byte[frames * mel];
        byte[] ap = new byte[frames * bands];
        for (int i = 0; i < frames; i++) {
            f0q[i] = (char) Math.max(0, Math.min(65535, Math.round(f0[i] * F0_SCALE)));
            float max = -Float.MAX_VALUE;
            for (float v : envDb[i]) {
                max = Math.max(max, v);
            }
            short t = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(max * ENV_TOP_SCALE)));
            top[i] = t;
            float topDb = t / ENV_TOP_SCALE;
            for (int b = 0; b < mel; b++) {
                int q = Math.round((topDb - envDb[i][b]) / ENV_STEP_DB);
                env[i * mel + b] = (byte) Math.max(0, Math.min(255, q));
            }
            for (int b = 0; b < bands; b++) {
                int q = Math.round(-apDb[i][b] / AP_STEP_DB);
                ap[i * bands + b] = (byte) Math.max(0, Math.min(255, q));
            }
        }
        return new Segment(file, startSec, frames, mel, bands, f0q, top, env, ap);
    }

    public String file() {
        return file;
    }

    /** Time of frame 0 in the original recording (seconds). */
    public double startSec() {
        return startSec;
    }

    public int frames() {
        return frames;
    }

    public double f0(int i) {
        return f0q[clamp(i)] / F0_SCALE;
    }

    public void envDb(int i, float[] out) {
        i = clamp(i);
        float topDb = envTop[i] / ENV_TOP_SCALE;
        int base = i * melBins;
        for (int b = 0; b < melBins; b++) {
            out[b] = topDb - (env[base + b] & 0xFF) * ENV_STEP_DB;
        }
    }

    public void apDb(int i, float[] out) {
        i = clamp(i);
        int base = i * apBands;
        for (int b = 0; b < apBands; b++) {
            out[b] = -(ap[base + b] & 0xFF) * AP_STEP_DB;
        }
    }

    /** Mean envelope level in dB over the grid (a cheap loudness proxy). */
    public float levelDb(int i) {
        i = clamp(i);
        float topDb = envTop[i] / ENV_TOP_SCALE;
        int base = i * melBins;
        double sum = 0;
        for (int b = 0; b < melBins; b++) {
            sum += Math.pow(10, (topDb - (env[base + b] & 0xFF) * ENV_STEP_DB) / 10.0);
        }
        return (float) (10 * Math.log10(sum / melBins + 1e-30));
    }

    private int clamp(int i) {
        return Math.max(0, Math.min(frames - 1, i));
    }
}
