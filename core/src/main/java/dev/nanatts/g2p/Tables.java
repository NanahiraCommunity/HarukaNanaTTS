package dev.nanatts.g2p;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable lookup tables without Java 9+ collection factories (unavailable on older Android). */
final class Tables {

    private Tables() {
    }

    /** {@code map("k1", "v1", "k2", "v2", ...)} */
    static Map<String, String> map(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("odd number of arguments");
        }
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
