package dev.nanatts.io;

/** Mono PCM audio as floats in [-1, 1]. */
public final class AudioClip {

    private final float[] samples;
    private final int sampleRate;

    public AudioClip(float[] samples, int sampleRate) {
        this.samples = samples;
        this.sampleRate = sampleRate;
    }

    public float[] samples() {
        return samples;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public double durationSeconds() {
        return samples.length / (double) sampleRate;
    }

    /** 16-bit signed PCM, clipped. Suitable for Android {@code AudioTrack}. */
    public short[] toPcm16() {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = Math.max(-1f, Math.min(1f, samples[i]));
            out[i] = (short) Math.round(v * 32767f);
        }
        return out;
    }

    /** Concatenates clips of the same sample rate. */
    public static AudioClip concat(java.util.List<AudioClip> clips, int sampleRate) {
        int total = 0;
        for (AudioClip c : clips) {
            if (c.sampleRate != sampleRate) {
                throw new IllegalArgumentException("sample rate mismatch");
            }
            total += c.samples.length;
        }
        float[] out = new float[total];
        int pos = 0;
        for (AudioClip c : clips) {
            System.arraycopy(c.samples, 0, out, pos, c.samples.length);
            pos += c.samples.length;
        }
        return new AudioClip(out, sampleRate);
    }
}
