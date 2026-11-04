package dev.nanatts.world;

import dev.nanatts.dsp.FFT;
import java.util.Random;

/**
 * Pulse-synchronous source-filter synthesis after WORLD's {@code Synthesis}: each pulse gets a minimum-phase
 * periodic response (envelope * (1 - ap^2)) plus filtered white noise (envelope * ap^2) spanning the pulse
 * interval, overlap-added at sub-sample accurate pulse positions.
 *
 * <p>Envelope scaling matches {@link CheapTrick}: a flat envelope of value {@code p} synthesises a signal of
 * mean power {@code p}. Not thread-safe.
 */
public final class WorldSynthesizer {

    /** Frame-wise parameters supplied lazily, so long utterances need not be fully decoded in memory. */
    public interface Frames {
        int count();

        /** F0 in Hz; values {@code <= 0} mark unvoiced frames. */
        double f0(int frame);

        /** Linear power envelope, {@code fftSize/2+1} bins. */
        void spectrum(int frame, double[] out);

        /** Aperiodic power fraction {@code ap^2} in [0, 1] per bin, {@code fftSize/2+1} bins. */
        void aperiodicity(int frame, double[] out);
    }

    private static final double DEFAULT_F0 = 500;
    private static final double EPS = 1e-30;

    private final int fs;
    private final double framePeriod;
    private final int n;
    private final int bins;
    private final FFT fft;
    private final double[] re;
    private final double[] im;
    private final double[] nre;
    private final double[] nim;
    private final double[] specA;
    private final double[] specB;
    private final double[] apA;
    private final double[] apB;
    private final double[] spec;
    private final double[] ap;
    private final double[] outRe;
    private final double[] outIm;
    private int cachedA = -1;
    private int cachedB = -1;

    public WorldSynthesizer(int fs, double framePeriod, int fftSize) {
        this.fs = fs;
        this.framePeriod = framePeriod;
        this.n = fftSize;
        this.bins = fftSize / 2 + 1;
        this.fft = new FFT(fftSize);
        this.re = new double[fftSize];
        this.im = new double[fftSize];
        this.nre = new double[fftSize];
        this.nim = new double[fftSize];
        this.specA = new double[bins];
        this.specB = new double[bins];
        this.apA = new double[bins];
        this.apB = new double[bins];
        this.spec = new double[bins];
        this.ap = new double[bins];
        this.outRe = new double[bins];
        this.outIm = new double[bins];
    }

    public int fftSize() {
        return n;
    }


    public float[] synthesize(Frames frames, long seed) {
        int count = frames.count();
        if (count == 0) {
            return new float[0];
        }
        resetCache();
        Random rnd = new Random(seed);
        double samplesPerFrame = framePeriod * fs;
        int total = (int) Math.round(count * samplesPerFrame);
        double[] out = new double[total + n + 1];

        // 1. pulse positions from the interpolated f0 contour
        double[] pulses = new double[Math.max(16, (int) (total * 1.2 * DEFAULT_F0 / fs) + 16)];
        int np = 0;
        double phase = 0;
        for (int s = 0; s < total; s++) {
            double f = interpolatedF0(frames, s / samplesPerFrame, count);
            double inc = f / fs;
            double next = phase + inc;
            if (next >= 1) {
                if (np == pulses.length) {
                    pulses = java.util.Arrays.copyOf(pulses, pulses.length * 2);
                }
                pulses[np++] = s + (1 - phase) / inc;
                next -= 1;
            }
            phase = next;
        }

        // 2. one response per pulse
        for (int p = 0; p < np; p++) {
            double tp = pulses[p];
            int pos = (int) Math.floor(tp);
            double frac = tp - pos;
            int len = p + 1 < np ? Math.max(1, (int) Math.round(pulses[p + 1] - tp)) : (int) Math.round(fs / DEFAULT_F0);
            len = Math.min(len, n / 2);
            double fpos = tp / samplesPerFrame;
            int nearest = Math.min(count - 1, (int) Math.round(fpos));
            boolean voiced = frames.f0(nearest) > 0;
            interpolateFrame(frames, fpos, count);
            pulseResponse(voiced, frac, len, rnd);
            for (int i = 0; i < n; i++) {
                int o = pos + i;
                if (o >= 0 && o < out.length) {
                    out[o] += re[i];
                }
            }
        }

        // 3. DC blocker and conversion
        float[] result = new float[total];
        double prevIn = 0;
        double prevOut = 0;
        double r = Math.exp(-2 * Math.PI * 30.0 / fs);
        for (int i = 0; i < total; i++) {
            double y = out[i] - prevIn + r * prevOut;
            prevIn = out[i];
            prevOut = y;
            result[i] = (float) y;
        }
        return result;
    }

    private double interpolatedF0(Frames frames, double fpos, int count) {
        int i0 = Math.min(count - 1, (int) Math.floor(fpos));
        int i1 = Math.min(count - 1, i0 + 1);
        double a = frames.f0(i0);
        double b = frames.f0(i1);
        if (a <= 0 && b <= 0) {
            return DEFAULT_F0;
        }
        if (a <= 0) {
            return b;
        }
        if (b <= 0) {
            return a;
        }
        double t = fpos - i0;
        return a + (b - a) * t;
    }

    private void resetCache() {
        cachedA = -1;
        cachedB = -1;
    }

    private void interpolateFrame(Frames frames, double fpos, int count) {
        int i0 = Math.min(count - 1, (int) Math.floor(fpos));
        int i1 = Math.min(count - 1, i0 + 1);
        double t = Math.min(1, Math.max(0, fpos - i0));
        load(frames, i0, i1);
        for (int k = 0; k < bins; k++) {
            spec[k] = specA[k] * (1 - t) + specB[k] * t;
            ap[k] = apA[k] * (1 - t) + apB[k] * t;
        }
    }

    private void load(Frames frames, int i0, int i1) {
        if (cachedA == i0 && cachedB == i1) {
            return;
        }
        if (cachedB == i0) {
            System.arraycopy(specB, 0, specA, 0, bins);
            System.arraycopy(apB, 0, apA, 0, bins);
        } else {
            frames.spectrum(i0, specA);
            frames.aperiodicity(i0, apA);
        }
        frames.spectrum(i1, specB);
        frames.aperiodicity(i1, apB);
        cachedA = i0;
        cachedB = i1;
    }

    /** Leaves the time-domain response of one pulse in {@code re[0..n)}. */
    private void pulseResponse(boolean voiced, double frac, int len, Random rnd) {
        // log magnitudes: periodic in re, aperiodic in im (both real & even -> one complex FFT for both cepstra)
        for (int k = 0; k < bins; k++) {
            double a2 = Math.min(1, Math.max(0, ap[k]));
            double p = Math.max(0, spec[k]);
            if (voiced) {
                re[k] = 0.5 * Math.log(p * (1 - a2) + EPS);
                im[k] = 0.5 * Math.log(p * a2 + EPS);
            } else {
                re[k] = 0.5 * Math.log(EPS);
                im[k] = 0.5 * Math.log(p + EPS);
            }
        }
        for (int k = bins; k < n; k++) {
            re[k] = re[n - k];
            im[k] = im[n - k];
        }
        fft.inverse(re, im);
        for (int q = 1; q < n / 2; q++) {
            re[q] *= 2;
            im[q] *= 2;
        }
        for (int q = n / 2 + 1; q < n; q++) {
            re[q] = 0;
            im[q] = 0;
        }
        fft.forward(re, im);

        // white noise for the aperiodic part
        java.util.Arrays.fill(nre, 0);
        java.util.Arrays.fill(nim, 0);
        for (int i = 0; i < len; i++) {
            nre[i] = rnd.nextGaussian();
        }
        fft.forward(nre, nim);

        double sqrtLen = Math.sqrt(len);
        // separate the two minimum-phase log spectra and build the combined spectrum (bins 0..n/2)
        for (int k = 0; k < bins; k++) {
            int m = (n - k) % n;
            double xr = re[k];
            double xi = im[k];
            double yr = re[m];
            double yi = im[m];
            double aRe = 0.5 * (xr + yr);
            double aIm = 0.5 * (xi - yi);
            double bRe = 0.5 * (xi + yi);
            double bIm = -0.5 * (xr - yr);
            double cr = 0;
            double ci = 0;
            if (voiced) {
                double mag = Math.exp(aRe) * sqrtLen;
                double ph = aIm - 2 * Math.PI * k * frac / n;
                cr += mag * Math.cos(ph);
                ci += mag * Math.sin(ph);
            }
            double magB = Math.exp(bRe);
            double hr = magB * Math.cos(bIm);
            double hi = magB * Math.sin(bIm);
            cr += hr * nre[k] - hi * nim[k];
            ci += hr * nim[k] + hi * nre[k];
            outRe[k] = cr;
            outIm[k] = ci;
        }
        outRe[0] = 0;
        outIm[0] = 0;
        outIm[n / 2] = 0;
        for (int k = 0; k < bins; k++) {
            re[k] = outRe[k];
            im[k] = outIm[k];
        }
        for (int k = bins; k < n; k++) {
            re[k] = outRe[n - k];
            im[k] = -outIm[n - k];
        }
        fft.inverse(re, im);
    }
}
