# nana-tts

**Completely vibed offline TTS engine.**

Offline text-to-speech in pure Java, using the **春歌ナナ (Haruka Nana) 連続音 2023** UTAU voicebank in `data/`.
English text is supported now, including hiragana/katakana words mixed into it. Japanese kana input works,
and kanji will come later.

It is a concatenative VCV synthesiser built on a WORLD-style vocoder. There is no neural network, nothing runs
natively, and there are no runtime dependencies. The library targets **Java 17 bytecode** and avoids
`javax.sound`, AWT, `java.nio.file` and Java 9+ collection factories, so it can be dropped into an Android app
as is.

```
text ─► normaliser ─► CMUdict / LTS G2P ─► ARPAbet→kana ─► prosody ─► VCV unit renderer ─► WORLD synthesis ─► PCM
                                                                         ▲
                                                 nana.vpk (pre-analysed voicebank)
```

## Build

```sh
./gradlew :bankc:compileVoice   # analyse data/ → bankc/build/voice/nana.vpk (~11 MB, ~10 s)
./gradlew check                 # self-tests (also compiles the voice pack)
./gradlew :core:jar             # core/build/libs/core-0.1.0.jar (~1 MB, includes CMUdict)
```

The build has no external dependencies and works offline. Tests use a small built-in runner
(`core/src/test/java/dev/nanatts/SelfTests.java`), not JUnit.

## Command line

```sh
./gradlew :cli:run --args='"Hello! How are you today?"'                     # speak through the speakers
./gradlew :cli:run --args='-o out.wav --phonemes "It costs $3.50."'          # write a wav, show kana
./gradlew :cli:run --args='-l ja -p 1.1 "こんにちは、はるかななです。"'
```

Options: `-p` pitch, `-s` speed, `-r` intonation range, `-b` breathiness, `-v` volume, `--rate` sample rate,
`-f FILE` (or `-f -` for stdin), `--style playful|neutral|FILE.properties`, `--set KNOB=VALUE`. Run with
`--help` for the full list, and `--knobs` to print every style knob with its current value.

## Speaking styles and expressive cues

`StyleProfile.PLAYFUL` is the default: bouncy rise-fall accents on stressed words with the strongest on the
last one, alternating heights, stretched accented vowels, cheerful phrase endings, and pitch targets pulled
slightly towards a pentatonic scale. `StyleProfile.NEUTRAL` is calm and newsreader-like.

The text cues below are never pronounced:

| Cue | Effect |
|---|---|
| `*word*` | emphasis: higher, longer and louder accent |
| `word~` (also `〜`) | stretched vowel with a rise-fall glide ("so cute~") |
| `♪` anywhere in a phrase | half-sung: every mora held on a note, with vibrato |
| `!!` | extra excitement |

They also work for Japanese kana input, where a long vowel `ー` at the end of a phrase glides too.

### Japanese words inside English

English text may contain hiragana and katakana (full- or half-width): `I love すし and ラーメン!` speaks the kana
words as Japanese and accents them on their first mora, so they flow with the English intonation. `。` and
`、` work as punctuation. Kanji are not supported yet and are skipped.

**Rhythm.** The `rhythm` knob (0 = natural speech, 1 = strict) pulls syllables onto a beat grid of `beat`
seconds per slot. Each syllable gets a whole number of slots, and its vowel onset lands on the beat, with the
consonant just before it. Inserted vowels (the と of きゃっと), diphthong glides and closing ん share their
syllable's slot. Stressed syllables move onto strong beats. The last syllable is held to the end of the half
bar (`barBeats`), and pauses are sized so the next phrase starts on the beat. Morae before a phrase's first
real syllable (the と of "twinkle") are a pickup. `♪` phrases are always fully rhythmic.

A style is a set of numeric knobs (accent height, density, stretch, scale snapping, rhythm, ending shapes,
pitch cap…).
Tweak them in code with `StyleProfile.PLAYFUL.with("accentHeight", 6)`, or load a `.properties` file on top
of a preset with `StyleProfile.PLAYFUL.load(in)`. `./gradlew :cli:audition` renders the test sentences in
several variants to `samples/tuning/`, so you can compare them by ear.

## Library API

```java
VoicePack pack;
try (InputStream in = new FileInputStream("nana.vpk")) {
    pack = VoicePack.read(in);                       // load once and share; it is immutable
}
Synthesizer tts = Synthesizer.builder(pack)
        .language(Language.ENGLISH)                  // or Language.JAPANESE_KANA
        .sampleRate(24000)
        .build();                                    // thread-safe

AudioClip clip = tts.speak("Hello! How are you *today*?",
        VoiceParams.defaults().style(StyleProfile.PLAYFUL).pitch(1.0).speed(1.1));
WavIO.write(clip, outputStream);                    // or clip.toPcm16()

// low latency: one callback per phrase, so playback can start before the whole text is synthesised
tts.speak(longText, VoiceParams.defaults(), phraseClip -> play(phraseClip.toPcm16()));

tts.analyze("Hello world");                          // → [はろう わーるど [STATEMENT]] for debugging
```

## Android

1. Add the library, either as a module (`implementation(project(":core"))`) or as the jar. The jar already
   contains CMUdict as a Java resource.
2. Copy `bankc/build/voice/nana.vpk` to `app/src/main/assets/`. It is already gzip-compressed, so add
   `androidResources { noCompress += "vpk" }`.
3. Load the pack once, off the main thread:

```java
VoicePack pack = VoicePack.load(name -> context.getAssets().open(name), "nana.vpk");
Synthesizer tts = Synthesizer.builder(pack).build();

AudioTrack track = new AudioTrack.Builder()
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(tts.sampleRate())
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).build();
track.play();
tts.speak(text, VoiceParams.defaults(), clip -> {
    short[] pcm = clip.toPcm16();
    track.write(pcm, 0, pcm.length);
});
```

Expected resource use:
- **Memory:** about 19 MB for the pack (frames stay quantised and are decoded on demand) plus about 5 MB for
  the dictionary.
- **Speed:** about 0.03× real-time on a desktop JVM; expect 0.2–0.5× on phones.

## How it works

| Stage | Where | Notes |
|---|---|---|
| Voice compilation (offline, desktop) | `bankc/VoiceCompiler` | Parses `oto.ini` (Shift-JIS). For each wav: f0 via YIN (constrained around the sung pitch to avoid octave errors), CheapTrick spectral envelope, harmonic/noise band aperiodicity. Repairs voicing in nasal/glottal transitions, normalises loudness, detects where each consonant starts. Envelopes are stored as 80 mel-spaced dB values (byte-quantised) at a 5 ms frame rate. |
| Text normalisation | `g2p/EnglishNormalizer` | Numbers, years, ordinals, money, %, time, abbreviations, diacritics, full/half-width forms, phrase breaks (including 。、) and question/exclamation types. Hiragana/katakana words are kept and spoken as Japanese. |
| G2P | `g2p/EnglishG2P`, `CmuDict`, `LetterToSound` | CMUdict (126k words, binary search over a byte array), then suffix and compound decomposition, then spelling for acronyms, then letter-to-sound rules. |
| Phones → morae | `g2p/ArpabetToKana` | Katakana-loanword style (a Japanese accent): short voiced epenthetic vowels (`street` → すとりーと, `cat` → きゃっと), diphthong glides, non-rhotic r, palatalised rows (`cute` → きゅ), stress kept per mora. |
| Prosody | `prosody/ProsodyModel`, `StyleProfile` | Style-driven. Mora durations come from the consonant class, accents, emphasis and glides. The pitch contour is built from knots: declination, rise-fall accents (density, alternation, final boost), dips, glides, sung notes and phrase endings, with optional pentatonic snapping, seeded variation, vibrato and a soft range cap. |
| Concatenation | `synth/Renderer` | VCV unit per mora (`a か`, `n あ`, `- す`…). In the consonant region, the decay out of the previous vowel (30 ms) and the release into the next one (40 ms) keep their natural speed, and only the closure or frication in between is compressed. Vowels are cut or stretched. Neighbouring units cross-fade inside their shared vowel in the parameter domain. Short voicing flips are bridged and gains are smoothed, so there are no crackles or level steps. |
| Vocoder | `world/WorldSynthesizer` | WORLD-style pulse-synchronous synthesis: minimum-phase periodic response plus shaped noise, sub-sample pulse positions, and two min-phase responses computed in one complex FFT. |

## Limitations

- English is spoken with a Japanese-style accent. The bank only contains Japanese morae, so consonant
  clusters and final consonants get a short vowel (`cat` → きゃっと), and /l/ vs /r/ or /θ/ vs /s/ are not
  distinguished.
- The prosody is rule-based: emphasis only comes from `*cues*`, there is no emotion detection, and homographs
  ("read") always get CMUdict's first pronunciation.
- Japanese input is kana only and uses a flat (heiban-like) accent. A kanji reader and pitch-accent dictionary
  would plug in through `TextFrontend`.

## Licences

- **Voicebank:** 春歌ナナ by ななひら. Read the voicebank's terms of use before redistributing `nana.vpk`
  (which is derived from the recordings) in an app. Credits from `character.txt` are kept in
  `VoicePack.metadata()`.
- **CMUdict:** BSD-style licence, included as `core/src/main/resources/dev/nanatts/g2p/CMUDICT-LICENSE.txt`.
