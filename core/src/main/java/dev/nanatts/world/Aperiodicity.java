package dev.nanatts.world;

import dev.nanatts.dsp.FFT;
import java.util.Arrays;

/**
 * Band aperiodicity estimation (a simplified stand-in for WORLD's D4C).
 *
 * <p>For voiced frames, a Blackman window spanning eight periods resolves individual harmonics; per harmonic,
 * the sinusoid power (from the peak) and the noise power (from the inter-harmonic valley) are compared.
 * The result per band is the aperiodic power fraction {@code ap^2 = noise / (noise + harmonic)}, which is
 * exactly how {@link WorldSynthesizer} splits the envelope into periodic and aperiodic parts.
 * Not thread-safe.
 */
public final class Aperiodicity {

    /** Default band centre frequencies (Hz). */
    public static final double[] DEFAULT_BANDS = {250, 750, 1500, 2500, 3500, 5000, 7000, 9500, 12000};

    private final int fs;
    private final double[] bands;
    private final double minAp2;
    private double[] re = new double[0];
    private double[] im = new double[0];
    private FFT fft;

    public Aperiodicity(int fs, double[] bands, double minApDb) {
        this.fs = fs;
        this.bands = bands.clone();
        this.minAp2 = Math.pow(10, minApDb / 10);
    }

    public double[] bands() {
        return bands.clone();
    }

    /**
     * Writes {@code ap^2} per band to {@code out}. Unvoiced frames ({@code f0 <= 0}) are fully aperiodic.
     */
    public void estimate(float[] x, double t, double f0, double[] out) {
        if (f0 <= 0) {
            Arrays.fill(out, 1.0);
            return;
        }
        int nw = (int) Math.round(8.0 * fs / f0) | 1;
        int n = FFT.nextPow2(nw * 2);
        if (fft == null || fft.size() != n) {
            fft = new FFT(n);
            re = new double[n];
            im = new double[n];
        }
        Arrays.fill(re, 0);
        Arrays.fill(im, 0);
        int center = (int) Math.round(t * fs);
        int half = nw / 2;
        double s1 = 0;
        double s2 = 0;
        double mean = 0;
        double[] w = new double[nw];
        for (int i = 0; i < nw; i++) {
            double a = 2 * Math.PI * i / (nw - 1);
            w[i] = 0.42 - 0.5 * Math.cos(a) + 0.08 * Math.cos(2 * a);
            int j = Math.min(Math.max(0, center - half + i), x.length - 1);
            re[i] = x[j];
            mean += x[j] * w[i];
            s1 += w[i];
            s2 += w[i] * w[i];
        }
        mean /= s1;
        for (int i = 0; i < nw; i++) {
            re[i] = (re[i] - mean) * w[i];
        }
        fft.forward(re, im);
        int bins = n / 2 + 1;
        double[] p = re; // reuse: power in re[0..bins)
        for (int k = 0; k < bins; k++) {
            p[k] = re[k] * re[k] + im[k] * im[k];
        }
        double binsPerHarmonic = f0 * n / fs;
        double[] noise = new double[bands.length];
        double[] total = new double[bands.length];
        double nyq = Math.min(fs / 2.0, bands[bands.length - 1] + 2000);
        for (int h = 1; h * f0 < nyq - f0; h++) {
            double kc = h * binsPerHarmonic;
            double peak = 0;
            int lo = (int) Math.floor(kc - 0.25 * binsPerHarmonic);
            int hi = (int) Math.ceil(kc + 0.25 * binsPerHarmonic);
            for (int k = Math.max(0, lo); k <= Math.min(bins - 1, hi); k++) {
                peak = Math.max(peak, p[k]);
            }
            double valley = 0;
            int cnt = 0;
            int vlo = (int) Math.round(kc + 0.4 * binsPerHarmonic);
            int vhi = (int) Math.round(kc + 0.6 * binsPerHarmonic);
            for (int k = vlo; k <= Math.min(bins - 1, vhi); k++) {
                valley += p[k];
                cnt++;
            }
            if (cnt == 0) {
                break;
            }
            valley /= cnt;
            double noisePow = valley / s2 * 2.0 * binsPerHarmonic / n;
            double harmPow = Math.max(0, 2.0 * peak / (s1 * s1) - noisePow);
            double freq = h * f0;
            for (int b = 0; b < bands.length; b++) {
                double wgt = triangle(freq, b);
                if (wgt > 0) {
                    noise[b] += wgt * noisePow;
                    total[b] += wgt * (noisePow + harmPow);
                }
            }
        }
        for (int b = 0; b < bands.length; b++) {
            double ap2 = total[b] > 0 ? noise[b] / total[b] : (b == 0 ? minAp2 : 1.0);
            out[b] = Math.max(minAp2, Math.min(1.0, ap2));
        }
        // bands without any harmonic (e.g. below f0) inherit from their neighbour
        for (int b = 0; b < bands.length; b++) {
            if (total[b] <= 0 && b + 1 < bands.length && total[b + 1] > 0) {
                out[b] = out[b + 1];
            }
        }
    }

    private double triangle(double f, int b) {
        double c = bands[b];
        double left = b == 0 ? 0 : bands[b - 1];
        double right = b == bands.length - 1 ? Double.MAX_VALUE : bands[b + 1];
        if (f <= c) {
            return b == 0 ? 1 : Math.max(0, (f - left) / (c - left));
        }
        return b == bands.length - 1 ? 1 : Math.max(0, (right - f) / (right - c));
    }
}
