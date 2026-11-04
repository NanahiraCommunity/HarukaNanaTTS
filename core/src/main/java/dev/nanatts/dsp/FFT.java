package dev.nanatts.dsp;

/**
 * In-place iterative radix-2 complex FFT with precomputed twiddles.
 * Forward transform is unnormalised; {@link #inverse} scales by 1/n.
 * Instances are immutable and thread-safe.
 */
public final class FFT {

    private final int n;
    private final int[] rev;
    private final double[] cos;
    private final double[] sin;

    public FFT(int n) {
        if (n < 2 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        }
        this.n = n;
        int bits = Integer.numberOfTrailingZeros(n);
        rev = new int[n];
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2 * Math.PI * i / n;
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
        }
    }

    public int size() {
        return n;
    }

    public void forward(double[] re, double[] im) {
        transform(re, im, false);
    }

    public void inverse(double[] re, double[] im) {
        transform(re, im, true);
        double s = 1.0 / n;
        for (int i = 0; i < n; i++) {
            re[i] *= s;
            im[i] *= s;
        }
    }

    private void transform(double[] re, double[] im, boolean inv) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int start = 0; start < n; start += len) {
                for (int k = 0; k < half; k++) {
                    double wr = cos[k * step];
                    double wi = inv ? -sin[k * step] : sin[k * step];
                    int a = start + k;
                    int b = a + half;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    /** Smallest power of two >= v. */
    public static int nextPow2(int v) {
        int n = 1;
        while (n < v) {
            n <<= 1;
        }
        return n;
    }
}
