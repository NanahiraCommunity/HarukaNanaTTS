package dev.nanatts.world;

import dev.nanatts.dsp.FFT;

/**
 * F0-adaptive spectral envelope estimation after WORLD's CheapTrick
 * (pitch-synchronous Hann window, DC correction, linear smoothing, cepstral liftering with q1 compensation).
 *
 * <p>Output is a power spectrum normalised so that white noise of variance s^2 yields a flat envelope of
 * value s^2, i.e. independent of window length and FFT size. Not thread-safe; use one instance per thread.
 */
public final class CheapTrick {

    private static final double Q1 = -0.15;

    private final int fs;
    private final int fftSize;
    private final FFT fft;
    private final double[] re;
    private final double[] im;
    private final double[] work;

    public CheapTrick(int fs, double f0Floor) {
        this.fs = fs;
        this.fftSize = FFT.nextPow2((int) Math.ceil(3.0 * fs / f0Floor) + 1);
        this.fft = new FFT(fftSize);
        this.re = new double[fftSize];
        this.im = new double[fftSize];
        this.work = new double[fftSize / 2 + 1];
    }

    public int fftSize() {
        return fftSize;
    }

    /** Estimates the envelope at {@code t} seconds with fundamental {@code f0} (must be > 0). */
    public void estimate(float[] x, double t, double f0, double[] out) {
        int half = (int) Math.round(1.5 * fs / f0);
        int center = (int) Math.round(t * fs);
        double sumSeg = 0;
        double sumWin = 0;
        double sumWin2 = 0;
        java.util.Arrays.fill(re, 0);
        java.util.Arrays.fill(im, 0);
        double[] win = new double[2 * half + 1];
        for (int i = -half; i <= half; i++) {
            double w = 0.5 * Math.cos(Math.PI * i / (double) fs * f0 / 1.5) + 0.5;
            int j = Math.min(Math.max(0, center + i), x.length - 1);
            double v = x[j] * w;
            win[i + half] = w;
            re[i + half] = v;
            sumSeg += v;
            sumWin += w;
            sumWin2 += w * w;
        }
        double dc = sumSeg / sumWin;
        for (int i = 0; i < win.length; i++) {
            re[i] -= win[i] * dc;
        }
        fft.forward(re, im);
        int bins = fftSize / 2 + 1;
        double[] power = out;
        for (int k = 0; k < bins; k++) {
            power[k] = (re[k] * re[k] + im[k] * im[k]) / sumWin2;
        }
        dcCorrection(power, f0);
        linearSmoothing(power, f0 * 2.0 / 3.0);
        for (int k = 0; k < bins; k++) {
            power[k] += 1e-16;
        }
        cepstralSmoothing(power, f0);
    }

    /** Folds energy below f0 back so that the DC region is not underestimated. */
    private void dcCorrection(double[] power, double f0) {
        int bins = fftSize / 2 + 1;
        double df = fs / (double) fftSize;
        int limit = (int) (f0 / df) + 1;
        System.arraycopy(power, 0, work, 0, bins);
        for (int k = 0; k < Math.min(limit, bins); k++) {
            double pos = (f0 - k * df) / df;
            int i0 = (int) Math.floor(pos);
            double frac = pos - i0;
            if (i0 + 1 < bins && i0 >= 0) {
                power[k] = work[k] + work[i0] * (1 - frac) + work[i0 + 1] * frac;
            }
        }
    }

    /** Rectangular smoothing of the given width (Hz) using an integrated, mirrored spectrum. */
    private void linearSmoothing(double[] power, double width) {
        int bins = fftSize / 2 + 1;
        double df = fs / (double) fftSize;
        int pad = (int) (width / df) + 2;
        int len = bins + 2 * pad;
        double[] ext = new double[len];
        for (int i = 0; i < len; i++) {
            int k = i - pad;
            if (k < 0) {
                k = -k;
            } else if (k >= bins) {
                k = 2 * (bins - 1) - k;
            }
            ext[i] = power[Math.max(0, Math.min(bins - 1, k))];
        }
        double[] cum = new double[len + 1];
        for (int i = 0; i < len; i++) {
            cum[i + 1] = cum[i] + ext[i];
        }
        double halfW = width / df / 2.0;
        for (int k = 0; k < bins; k++) {
            double a = k + pad - halfW + 0.5;
            double b = k + pad + halfW + 0.5;
            power[k] = (interp(cum, b) - interp(cum, a)) / (b - a);
        }
    }

    private static double interp(double[] arr, double pos) {
        int i = (int) Math.floor(pos);
        if (i < 0) {
            return arr[0];
        }
        if (i >= arr.length - 1) {
            return arr[arr.length - 1];
        }
        double f = pos - i;
        return arr[i] * (1 - f) + arr[i + 1] * f;
    }

    private void cepstralSmoothing(double[] power, double f0) {
        int bins = fftSize / 2 + 1;
        for (int k = 0; k < bins; k++) {
            re[k] = Math.log(power[k]);
            im[k] = 0;
        }
        for (int k = bins; k < fftSize; k++) {
            re[k] = re[fftSize - k];
            im[k] = 0;
        }
        fft.inverse(re, im);
        for (int q = 1; q <= fftSize / 2; q++) {
            double quef = q / (double) fs;
            double x = Math.PI * f0 * quef;
            double smoothing = Math.sin(x) / x;
            double compensation = (1.0 - 2.0 * Q1) + 2.0 * Q1 * Math.cos(2.0 * Math.PI * quef * f0);
            double l = smoothing * compensation;
            re[q] *= l;
            if (q < fftSize / 2) {
                re[fftSize - q] *= l;
            }
        }
        java.util.Arrays.fill(im, 0);
        fft.forward(re, im);
        for (int k = 0; k < bins; k++) {
            power[k] = Math.exp(re[k]);
        }
    }
}
