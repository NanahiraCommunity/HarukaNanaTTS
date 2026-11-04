package dev.nanatts.bank;

import dev.nanatts.io.ResourceSource;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A compiled voice: pre-analysed vocoder parameters for every unit of a voicebank. Produced offline by the
 * {@code bankc} tool from an UTAU voicebank, so devices never analyse audio themselves.
 */
public final class VoicePack {

    private static final int MAGIC = 0x4E56504B; // "NVPK"
    private static final int VERSION = 1;

    private final Map<String, String> metadata;
    private final double framePeriod;
    private final MelGrid grid;
    private final double[] apBands;
    private final double medianF0;
    private final List<Segment> segments;
    private final Map<String, List<Unit>> units;

    public VoicePack(Map<String, String> metadata, double framePeriod, MelGrid grid, double[] apBands,
                     double medianF0, List<Segment> segments, List<Unit> unitList) {
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.framePeriod = framePeriod;
        this.grid = grid;
        this.apBands = apBands.clone();
        this.medianF0 = medianF0;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        Map<String, List<Unit>> m = new HashMap<>();
        for (Unit u : unitList) {
            m.computeIfAbsent(u.alias(), k -> new ArrayList<>()).add(u);
        }
        this.units = m;
    }

    /** Free-form metadata, e.g. {@code name}, {@code author}, {@code license}. */
    public Map<String, String> metadata() {
        return metadata;
    }

    /** Analysis frame period in seconds. */
    public double framePeriod() {
        return framePeriod;
    }

    public MelGrid grid() {
        return grid;
    }

    public double[] apBands() {
        return apBands.clone();
    }

    /** Median F0 (Hz) of all voiced frames: the singer's recorded pitch. */
    public double medianF0() {
        return medianF0;
    }

    public Segment segment(int i) {
        return segments.get(i);
    }

    public int segmentCount() {
        return segments.size();
    }

    /** All candidate units for an alias (empty if none). */
    public List<Unit> units(String alias) {
        List<Unit> l = units.get(alias);
        return l == null ? Collections.<Unit>emptyList() : Collections.unmodifiableList(l);
    }

    public boolean has(String alias) {
        return units.containsKey(alias);
    }

    public int unitCount() {
        int n = 0;
        for (List<Unit> l : units.values()) {
            n += l.size();
        }
        return n;
    }

    public static VoicePack load(ResourceSource source, String name) throws IOException {
        try (InputStream in = source.open(name)) {
            return read(in);
        }
    }

    public static VoicePack read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16));
        if (in.readInt() != MAGIC) {
            throw new IOException("not a voice pack");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported voice pack version " + version);
        }
        int metaCount = in.readInt();
        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 0; i < metaCount; i++) {
            meta.put(in.readUTF(), in.readUTF());
        }
        double framePeriod = in.readDouble();
        MelGrid grid = new MelGrid(in.readInt(), in.readDouble());
        double[] bands = new double[in.readInt()];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = in.readDouble();
        }
        double medianF0 = in.readDouble();
        int segCount = in.readInt();
        List<Segment> segs = new ArrayList<>(segCount);
        for (int s = 0; s < segCount; s++) {
            String file = in.readUTF();
            double start = in.readDouble();
            int frames = in.readInt();
            char[] f0 = new char[frames];
            short[] top = new short[frames];
            for (int i = 0; i < frames; i++) {
                f0[i] = in.readChar();
            }
            for (int i = 0; i < frames; i++) {
                top[i] = in.readShort();
            }
            byte[] env = new byte[frames * grid.size()];
            in.readFully(env);
            byte[] ap = new byte[frames * bands.length];
            in.readFully(ap);
            segs.add(new Segment(file, start, frames, grid.size(), bands.length, f0, top, env, ap));
        }
        int unitCount = in.readInt();
        List<Unit> units = new ArrayList<>(unitCount);
        for (int i = 0; i < unitCount; i++) {
            units.add(new Unit(in.readUTF(), in.readChar(), in.readUTF(), in.readInt(), in.readDouble(),
                    in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
        }
        return new VoicePack(meta, framePeriod, grid, bands, medianF0, segs, units);
    }

    public void write(OutputStream raw) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(raw, 1 << 16);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gz, 1 << 16));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(metadata.size());
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeUTF(e.getValue());
        }
        out.writeDouble(framePeriod);
        out.writeInt(grid.size());
        out.writeDouble(grid.maxHz());
        out.writeInt(apBands.length);
        for (double b : apBands) {
            out.writeDouble(b);
        }
        out.writeDouble(medianF0);
        out.writeInt(segments.size());
        for (Segment s : segments) {
            out.writeUTF(s.file);
            out.writeDouble(s.startSec);
            out.writeInt(s.frames);
            for (char c : s.f0q) {
                out.writeChar(c);
            }
            for (short t : s.envTop) {
                out.writeShort(t);
            }
            out.write(s.env);
            out.write(s.ap);
        }
        List<Unit> all = new ArrayList<>();
        units.values().forEach(all::addAll);
        all.sort((a, b) -> a.segment() != b.segment() ? Integer.compare(a.segment(), b.segment())
                : Double.compare(a.start(), b.start()));
        out.writeInt(all.size());
        for (Unit u : all) {
            out.writeUTF(u.alias());
            out.writeChar(u.prev());
            out.writeUTF(u.kana());
            out.writeInt(u.segment());
            out.writeDouble(u.start());
            out.writeDouble(u.overlap());
            out.writeDouble(u.consonantStart());
            out.writeDouble(u.onset());
            out.writeDouble(u.fixedEnd());
            out.writeDouble(u.end());
        }
        out.flush();
        gz.finish();
    }
}
