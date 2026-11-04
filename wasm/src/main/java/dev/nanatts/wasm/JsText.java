package dev.nanatts.wasm;

import org.teavm.jso.JSBody;

/** JavaScript string helpers (kept outside the TeaVM class-library emulation, where @JSBody is not processed). */
public final class JsText {

    private JsText() {
    }

    /** {@code String.prototype.normalize}: form is "NFC", "NFD", "NFKC" or "NFKD". */
    @JSBody(params = {"s", "form"}, script = "return s.normalize(form);")
    public static native String normalize(String s, String form);
}
