package dev.nanatts.world;

import java.util.Arrays;

/**
 * Robust F0 estimator (YIN on a decimated signal, two-pass range restriction around the median).
 * Stands in for WORLD's Harvest/DIO: voicebank recordings are sung at a level pitch, which makes a
 * median-constrained YIN both fast and reliable.
 */
public final class F0Estimator {

    /** Result of an estimation: f0 per frame (0 = unvoiced) and the YIN aperiodicity per frame. */
    public static final class Result {
        public final double[] f0;
        public final double[] yinMin;

        Result(double[] f0, double[] yinMin) {
            this.f0 = f0;
            this.yinMin = yinMin;
        }
    }

    private final double f0Floor;
    private final double f0Ceil;
    private double threshold = 0.18;
    private double silenceDb = -45;
    private double medianSpread = 2.0;

    public F0Estimator(double f0Floor, double f0Ceil) {
        this.f0Floor = f0Floor;
        this.f0Ceil = f0Ceil;
    }

    public F0Estimator threshold(double t) {
        this.threshold = t;
        return this;
    }

    /**
     * Second-pass search range around the first-pass median, as a ratio (default 2 = one octave each way).
     * Recordings sung at a level pitch can use a tighter range to rule out octave errors.
     */
    public F0Estimator medianSpread(double ratio) {
        this.medianSpread = ratio;
        return this;
    }

    /** Frames whose RMS is this many dB below the loudest frame are treated as unvoiced. */
    public F0Estimator silenceDb(double db) {
        this.silenceDb = db;
        return this;
    }

    /**
     * Estimates f0 for frames centred at {@code i * framePeriod} seconds.
     */
    public Result estimate(float[] x, int fs, double framePeriod) {
        int factor = Math.max(1, fs / 11025);
        double fsd = fs / (double) factor;
        double[] y = decimate(x, factor);
        int frames = (int) (x.length / (double) fs / framePeriod) + 1;

        double[] rms = new double[frames];
        int w = (int) Math.ceil(fsd * 2.0 / f0Floor);
        double maxRms = 1e-12;
        for (int i = 0; i < frames; i++) {
            int c = (int) Math.round(i * framePeriod * fsd);
            double s = 0;
            for (int j = c - w / 2; j < c + w / 2; j++) {
                double v = j >= 0 && j < y.length ? y[j] : 0;
                s += v * v;
            }
            rms[i] = Math.sqrt(s / w);
            maxRms = Math.max(maxRms, rms[i]);
        }
        double silence = maxRms * Math.pow(10, silenceDb / 20);

        double[] f0 = new double[frames];
        double[] ap = new double[frames];
        pass(y, fsd, framePeriod, rms, silence, f0Floor, f0Ceil, f0, ap);
        double median = median(f0);
        if (median > 0) {
            double lo = Math.max(f0Floor, median / medianSpread);
            double hi = Math.min(f0Ceil, median * medianSpread);
            pass(y, fsd, framePeriod, rms, silence, lo, hi, f0, ap);
        }
        cleanup(f0);
        return new Result(f0, ap);
    }

    private void pass(double[] y, double fsd, double framePeriod, double[] rms, double silence,
                      double lo, double hi, double[] f0, double[] ap) {
        int tauMin = Math.max(2, (int) Math.floor(fsd / hi));
        int tauMax = (int) Math.ceil(fsd / lo);
        int w = (int) Math.ceil(fsd * 2.0 / lo);
        double[] d = new double[tauMax + 2];
        double[] cm = new double[tauMax + 2];
        for (int i = 0; i < f0.length; i++) {
            f0[i] = 0;
            ap[i] = 1;
            if (rms[i] < silence) {
                continue;
            }
            int start = (int) Math.round(i * framePeriod * fsd) - (w + tauMax) / 2;
            for (int tau = 1; tau <= tauMax + 1; tau++) {
                double s = 0;
                for (int j = 0; j < w; j++) {
                    double diff = at(y, start + j) - at(y, start + j + tau);
                    s += diff * diff;
                }
                d[tau] = s;
            }
            double running = 0;
            cm[0] = 1;
            for (int tau = 1; tau <= tauMax + 1; tau++) {
                running += d[tau];
                cm[tau] = running > 0 ? d[tau] * tau / running : 1;
            }
            int best = -1;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (cm[tau] < threshold) {
                    while (tau + 1 <= tauMax && cm[tau + 1] < cm[tau]) {
                        tau++;
                    }
                    best = tau;
                    break;
                }
            }
            if (best < 0) {
                double m = Double.MAX_VALUE;
                for (int tau = tauMin; tau <= tauMax; tau++) {
                    if (cm[tau] < m) {
                        m = cm[tau];
                        best = tau;
                    }
                }
            }
            double a = cm[best - 1];
            double b = cm[best];
            double c = cm[best + 1];
            double denom = a - 2 * b + c;
            double shift = denom > 1e-12 ? 0.5 * (a - c) / denom : 0;
            shift = Math.max(-1, Math.min(1, shift));
            ap[i] = b;
            if (b < threshold * 2) {
                f0[i] = fsd / (best + shift);
            }
        }
    }

    private static void cleanup(double[] f0) {
        // drop very short voiced runs
        int n = f0.length;
        int i = 0;
        while (i < n) {
            if (f0[i] <= 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && f0[j] > 0) {
                j++;
            }
            if (j - i < 3) {
                Arrays.fill(f0, i, j, 0);
            } else {
                // 3-point median inside the run
                double[] copy = Arrays.copyOfRange(f0, i, j);
                for (int k = 1; k < copy.length - 1; k++) {
                    f0[i + k] = med3(copy[k - 1], copy[k], copy[k + 1]);
                }
            }
            i = j;
        }
    }

    private static double med3(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /** Median of the voiced (positive) values, or 0 if there are none. */
    public static double median(double[] f0) {
        double[] v = Arrays.stream(f0).filter(x -> x > 0).sorted().toArray();
        return v.length == 0 ? 0 : v[v.length / 2];
    }

    private static double at(double[] y, int i) {
        return i >= 0 && i < y.length ? y[i] : 0;
    }

    /** Low-pass (windowed sinc) and decimate by an integer factor. */
    static double[] decimate(float[] x, int factor) {
        if (factor == 1) {
            double[] y = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                y[i] = x[i];
            }
            return y;
        }
        int half = 16 * factor;
        double[] h = new double[2 * half + 1];
        double cutoff = 0.9 / (2.0 * factor);
        double sum = 0;
        for (int k = -half; k <= half; k++) {
            double sinc = k == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * k) / (Math.PI * k);
            double win = 0.42 + 0.5 * Math.cos(Math.PI * k / half) + 0.08 * Math.cos(2 * Math.PI * k / half);
            h[k + half] = sinc * win;
            sum += h[k + half];
        }
        int n = x.length / factor;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            int c = i * factor;
            double s = 0;
            for (int k = -half; k <= half; k++) {
                int j = c + k;
                if (j >= 0 && j < x.length) {
                    s += x[j] * h[k + half];
                }
            }
            y[i] = s / sum;
        }
        return y;
    }
}
