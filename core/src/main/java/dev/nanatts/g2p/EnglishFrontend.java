package dev.nanatts.g2p;

import dev.nanatts.io.ResourceSource;
import dev.nanatts.text.KanaFrontend;
import dev.nanatts.text.Mora;
import dev.nanatts.text.Phrase;
import dev.nanatts.text.TextFrontend;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * English text frontend: normalisation, CMUdict-based G2P and mapping onto the voicebank's morae.
 * Hiragana/katakana words inside the text are pronounced as Japanese.
 */
public final class EnglishFrontend implements TextFrontend {

    private static final Set<String> FUNCTION_WORDS = Tables.set(
            "a", "an", "the", "of", "to", "in", "on", "at", "for", "with", "by", "from", "into", "and", "or", "but",
            "is", "are", "was", "were", "be", "been", "am", "as", "it", "its", "that", "than", "then", "if", "so",
            "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "our",
            "their", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "shall",
            "should", "may", "might", "must", "there", "this", "these", "those", "some", "just", "i'm", "it's",
            "you're", "we're", "they're", "i'll", "you'll", "i've", "what's", "that's", "there's");

    private static volatile EnglishFrontend shared;

    private final EnglishNormalizer normalizer = new EnglishNormalizer();
    private final EnglishG2P g2p;

    public EnglishFrontend(CmuDict dict) {
        this.g2p = new EnglishG2P(dict);
    }

    /** Lazily created instance backed by the bundled dictionary on the classpath. */
    public static EnglishFrontend shared() {
        EnglishFrontend s = shared;
        if (s == null) {
            synchronized (EnglishFrontend.class) {
                s = shared;
                if (s == null) {
                    s = new EnglishFrontend(CmuDict.load(ResourceSource.classpath(EnglishFrontend.class.getClassLoader())));
                    shared = s;
                }
            }
        }
        return s;
    }

    public EnglishG2P g2p() {
        return g2p;
    }

    /**
     * A kana word inside English text. It is treated like a content word with the accent on its first
     * mora, so it joins the English intonation instead of sounding flat.
     */
    private static List<Mora> japaneseWord(String kana) {
        List<Mora> morae = KanaFrontend.word(kana);
        if (!morae.isEmpty()) {
            Mora head = morae.get(0);
            head.accentHead = true;
            for (Mora m : morae) {
                m.stress = m == head ? 1 : 0;
            }
        }
        return morae;
    }

    @Override
    public List<Phrase> analyze(String text) {
        List<Phrase> phrases = new ArrayList<>();
        for (EnglishNormalizer.Chunk chunk : normalizer.normalize(text)) {
            Phrase phrase = new Phrase();
            phrase.ending = chunk.ending;
            phrase.pauseAfter = chunk.pause;
            phrase.sung = chunk.sung;
            phrase.excited = chunk.excited;
            for (int w = 0; w < chunk.words.size(); w++) {
                String word = chunk.words.get(w);
                boolean japanese = EnglishNormalizer.isKana(word.charAt(0));
                List<Mora> morae = japanese ? japaneseWord(word) : ArpabetToKana.convert(g2p.phones(word));
                if (morae.isEmpty()) {
                    continue;
                }
                boolean function = FUNCTION_WORDS.contains(word);
                boolean emphasis = chunk.emphasized.contains(w);
                for (Mora m : morae) {
                    m.functionWord = function && !emphasis;
                    m.emphasis = emphasis;
                }
                if (chunk.glides.contains(w)) {
                    // glide on the last real vowel, not on an epenthetic one (cute~ -> きゅ~と)
                    int g = morae.size() - 1;
                    while (g > 0 && morae.get(g).epenthetic) {
                        g--;
                    }
                    morae.get(g).glide = true;
                }
                morae.get(0).wordStart = true;
                morae.get(morae.size() - 1).wordEnd = true;
                phrase.morae.addAll(morae);
            }
            if (!phrase.morae.isEmpty()) {
                phrases.add(phrase);
            }
        }
        return phrases;
    }
}
