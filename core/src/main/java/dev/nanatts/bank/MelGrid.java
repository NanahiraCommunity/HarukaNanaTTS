package dev.nanatts.bank;

/** Log-spectral envelope sampled on a mel-spaced frequency grid from 0 Hz to {@code maxHz}. */
public final class MelGrid {

    private final int size;
    private final double maxHz;
    private final double[] hz;

    public MelGrid(int size, double maxHz) {
        this.size = size;
        this.maxHz = maxHz;
        this.hz = new double[size];
        double melMax = mel(maxHz);
        for (int i = 0; i < size; i++) {
            hz[i] = hzFromMel(melMax * i / (size - 1));
        }
    }

    public int size() {
        return size;
    }

    public double maxHz() {
        return maxHz;
    }

    public double hz(int i) {
        return hz[i];
    }

    public static double mel(double f) {
        return 1127.0 * Math.log(1 + f / 700.0);
    }

    public static double hzFromMel(double m) {
        return 700.0 * (Math.exp(m / 1127.0) - 1);
    }

    /**
     * Samples a linear-frequency power spectrum (bins {@code 0..fftSize/2}) at the grid points.
     * {@code scale} is applied before converting to dB.
     */
    public void fromLinear(double[] power, int fftSize, int fs, double scale, float[] outDb) {
        double df = fs / (double) fftSize;
        int last = fftSize / 2;
        for (int i = 0; i < size; i++) {
            double pos = Math.min(last, hz[i] / df);
            int k = (int) Math.floor(pos);
            double t = pos - k;
            double a = Math.log(Math.max(power[k], 1e-300));
            double b = Math.log(Math.max(power[Math.min(last, k + 1)], 1e-300));
            double ln = a + (b - a) * t;
            outDb[i] = (float) (10.0 / Math.log(10) * ln + 10 * Math.log10(scale));
        }
    }

    /** Precomputed interpolation from this grid to the linear bins of an FFT. */
    public Mapper mapper(int fftSize, int fs) {
        return new Mapper(fftSize, fs);
    }

    public final class Mapper {
        private final int[] idx;
        private final float[] frac;

        Mapper(int fftSize, int fs) {
            int bins = fftSize / 2 + 1;
            idx = new int[bins];
            frac = new float[bins];
            double melMax = mel(maxHz);
            for (int k = 0; k < bins; k++) {
                double f = k * fs / (double) fftSize;
                double pos = Math.min(size - 1.0, mel(f) / melMax * (size - 1));
                int i = Math.min(size - 2, (int) Math.floor(pos));
                idx[k] = i;
                frac[k] = (float) (pos - i);
            }
        }

        public int bins() {
            return idx.length;
        }

        /** Interpolates grid values (any unit, e.g. dB) to linear bins. */
        public void map(float[] grid, double[] out) {
            for (int k = 0; k < idx.length; k++) {
                int i = idx[k];
                out[k] = grid[i] + (grid[i + 1] - grid[i]) * frac[k];
            }
        }
    }
}
