package dev.nanatts.synth;

import dev.nanatts.bank.MelGrid;
import dev.nanatts.world.WorldSynthesizer;

/**
 * Frame-wise vocoder parameters of an utterance at the pack's frame period: f0 (Hz, 0 = unvoiced),
 * envelope (PSD in dB on the mel grid) and band aperiodicity (dB).
 */
public final class ParamTrack {

    final double[] f0;
    final float[][] envDb;
    final float[][] apDb;

    public ParamTrack(int frames, int melBins, int apBands) {
        f0 = new double[frames];
        envDb = new float[frames][melBins];
        apDb = new float[frames][apBands];
    }

    public int frames() {
        return f0.length;
    }

    public double[] f0() {
        return f0;
    }

    public float[] envDb(int i) {
        return envDb[i];
    }

    public float[] apDb(int i) {
        return apDb[i];
    }

    /** Adapts this track to the synthesizer's linear-frequency frame interface. */
    public WorldSynthesizer.Frames asFrames(MelGrid grid, double[] apBands, int fs, int fftSize) {
        MelGrid.Mapper mapper = grid.mapper(fftSize, fs);
        int bins = fftSize / 2 + 1;
        int[] bandIdx = new int[bins];
        float[] bandFrac = new float[bins];
        for (int k = 0; k < bins; k++) {
            double f = k * fs / (double) fftSize;
            if (f <= apBands[0]) {
                bandIdx[k] = 0;
                bandFrac[k] = 0;
            } else if (f >= apBands[apBands.length - 1]) {
                bandIdx[k] = apBands.length - 2;
                bandFrac[k] = 1;
            } else {
                int b = 0;
                while (apBands[b + 1] < f) {
                    b++;
                }
                bandIdx[k] = b;
                bandFrac[k] = (float) ((f - apBands[b]) / (apBands[b + 1] - apBands[b]));
            }
        }
        // PSD (one-sided, per Hz) -> synthesizer power normalisation at this sample rate
        double toPower = fs / 2.0;
        double maxHz = grid.maxHz();
        return new WorldSynthesizer.Frames() {
            @Override
            public int count() {
                return f0.length;
            }

            @Override
            public double f0(int frame) {
                return f0[frame];
            }

            @Override
            public void spectrum(int frame, double[] out) {
                mapper.map(envDb[frame], out);
                for (int k = 0; k < out.length; k++) {
                    double f = k * fs / (double) fftSize;
                    double db = out[k];
                    if (f > maxHz) {
                        db -= 24 * (f - maxHz) / 1000.0; // roll off beyond the stored range
                    }
                    out[k] = Math.pow(10, db / 10.0) * toPower;
                }
            }

            @Override
            public void aperiodicity(int frame, double[] out) {
                float[] ap = apDb[frame];
                for (int k = 0; k < out.length; k++) {
                    int b = bandIdx[k];
                    double db = ap[b] + (ap[b + 1] - ap[b]) * bandFrac[k];
                    out[k] = Math.pow(10, db / 10.0);
                }
            }
        };
    }
}
