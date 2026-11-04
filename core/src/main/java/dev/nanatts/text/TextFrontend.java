package dev.nanatts.text;

import java.util.List;

/** Converts text of one language into phrases of annotated morae. */
public interface TextFrontend {

    List<Phrase> analyze(String text);
}
