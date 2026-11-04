package dev.nanatts.bankc;

import dev.nanatts.bank.MelGrid;
import dev.nanatts.io.AudioClip;
import dev.nanatts.io.WavIO;
import dev.nanatts.synth.ParamTrack;
import dev.nanatts.world.Aperiodicity;
import dev.nanatts.world.CheapTrick;
import dev.nanatts.world.F0Estimator;
import dev.nanatts.world.WorldSynthesizer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Diagnostic: analyse a wav, resynthesise it through the mel-grid representation and report level and
 * log-spectral distance. Usage: {@code RoundTrip <in.wav> <out.wav> [outRate=24000]}
 */
public final class RoundTrip {

    public static void main(String[] args) throws Exception {
        AudioClip clip;
        try (InputStream in = new FileInputStream(args[0])) {
            clip = WavIO.read(in);
        }
        int outFs = args.length > 2 ? Integer.parseInt(args[2]) : 24000;
        float[] x = clip.samples();
        int fs = clip.sampleRate();
        double fp = VoiceCompiler.FRAME_PERIOD;
        F0Estimator.Result f0r = new F0Estimator(VoiceCompiler.F0_FLOOR, VoiceCompiler.F0_CEIL).estimate(x, fs, fp);
        double median = F0Estimator.median(f0r.f0);
        CheapTrick ct = new CheapTrick(fs, VoiceCompiler.F0_FLOOR);
        Aperiodicity apEst = new Aperiodicity(fs, Aperiodicity.DEFAULT_BANDS, -60);
        MelGrid grid = new MelGrid(80, Math.min(12000, outFs / 2.0));
        int frames = f0r.f0.length;
        ParamTrack track = new ParamTrack(frames, grid.size(), Aperiodicity.DEFAULT_BANDS.length);
        double[] power = new double[ct.fftSize() / 2 + 1];
        double[] ap = new double[Aperiodicity.DEFAULT_BANDS.length];
        int voiced = 0;
        for (int i = 0; i < frames; i++) {
            double f0 = f0r.f0[i];
            track.f0()[i] = f0;
            voiced += f0 > 0 ? 1 : 0;
            ct.estimate(x, i * fp, f0 > 0 ? f0 : median, power);
            grid.fromLinear(power, ct.fftSize(), fs, 2.0 / fs, track.envDb(i));
            apEst.estimate(x, i * fp, f0, ap);
            for (int b = 0; b < ap.length; b++) {
                track.apDb(i)[b] = (float) (10 * Math.log10(ap[b]));
            }
        }
        int fft = 1024;
        WorldSynthesizer syn = new WorldSynthesizer(outFs, fp, fft);
        long t0 = System.nanoTime();
        float[] y = syn.synthesize(track.asFrames(grid, Aperiodicity.DEFAULT_BANDS, outFs, fft), 1);
        double secs = (System.nanoTime() - t0) / 1e9;
        try (OutputStream os = new FileOutputStream(new File(args[1]))) {
            WavIO.write(new AudioClip(y, outFs), os);
        }
        System.out.printf("frames=%d voiced=%.0f%% median f0=%.1f%n", frames, 100.0 * voiced / frames, median);
        System.out.printf("rms in=%.4f out=%.4f (ratio %.2f dB)%n", rms(x), rms(y), 20 * Math.log10(rms(y) / rms(x)));
        System.out.printf("synthesis %.3fs for %.2fs audio (RTF %.3f)%n", secs, y.length / (double) outFs,
                secs / (y.length / (double) outFs));
        // per-frame envelope check: analyse the output again and compare mel envelopes
        CheapTrick ct2 = new CheapTrick(outFs, VoiceCompiler.F0_FLOOR);
        double[] p2 = new double[ct2.fftSize() / 2 + 1];
        float[] e2 = new float[grid.size()];
        double sum = 0;
        int n = 0;
        for (int i = 0; i < frames; i++) {
            if (track.f0()[i] <= 0) {
                continue;
            }
            ct2.estimate(y, i * fp, track.f0()[i], p2);
            grid.fromLinear(p2, ct2.fftSize(), outFs, 2.0 / outFs, e2);
            double s = 0;
            for (int b = 0; b < grid.size(); b++) {
                double d = e2[b] - track.envDb(i)[b];
                s += d * d;
            }
            sum += Math.sqrt(s / grid.size());
            n++;
        }
        System.out.printf("voiced log-spectral distance (re-analysis): %.2f dB%n", sum / Math.max(1, n));
    }

    private static double rms(float[] v) {
        double s = 0;
        for (float f : v) {
            s += f * f;
        }
        return Math.sqrt(s / v.length);
    }
}
