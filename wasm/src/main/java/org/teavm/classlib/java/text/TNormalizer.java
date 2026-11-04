package org.teavm.classlib.java.text;

import dev.nanatts.wasm.JsText;

/**
 * {@code java.text.Normalizer} for TeaVM, which does not ship one: TeaVM maps {@code java.x.Y} to
 * {@code org.teavm.classlib.java.x.TY} (nested types keep their names). Delegates to JavaScript's
 * {@code String.prototype.normalize} via {@link JsText},
 * which implements the same Unicode normalisation forms.
 */
public final class TNormalizer {

    /** Mirrors {@code java.text.Normalizer.Form}. */
    public enum Form {
        NFD, NFC, NFKD, NFKC
    }

    private TNormalizer() {
    }

    public static String normalize(CharSequence src, Form form) {
        return JsText.normalize(src.toString(), form.name());
    }

    public static boolean isNormalized(CharSequence src, Form form) {
        String s = src.toString();
        return s.equals(JsText.normalize(s, form.name()));
    }
}
