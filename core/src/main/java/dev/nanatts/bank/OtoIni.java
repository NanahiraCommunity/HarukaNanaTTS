package dev.nanatts.bank;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parser for UTAU {@code oto.ini} files (Shift-JIS / windows-31j encoded). */
public final class OtoIni {

    private OtoIni() {
    }

    public static List<OtoEntry> parse(InputStream in) throws IOException {
        return parse(in, Charset.forName("windows-31j"));
    }

    public static List<OtoEntry> parse(InputStream in, Charset charset) throws IOException {
        List<OtoEntry> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, charset));
        String line;
        int lineNo = 0;
        while ((line = r.readLine()) != null) {
            lineNo++;
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IOException("oto.ini line " + lineNo + ": missing '='");
            }
            String file = line.substring(0, eq);
            String[] f = line.substring(eq + 1).split(",", -1);
            if (f.length < 6) {
                throw new IOException("oto.ini line " + lineNo + ": expected 6 fields");
            }
            String alias = f[0].isEmpty() ? stripExtension(file) : f[0];
            try {
                out.add(new OtoEntry(file, alias, num(f[1]), num(f[2]), num(f[3]), num(f[4]), num(f[5])));
            } catch (NumberFormatException e) {
                throw new IOException("oto.ini line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static double num(String s) {
        s = s.trim();
        return s.isEmpty() ? 0 : Double.parseDouble(s);
    }

    private static String stripExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
