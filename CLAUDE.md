# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An offline, pure-Java TTS library that speaks English (plus hiragana/katakana, inline or as pure Japanese
kana input) with the 春歌ナナ UTAU voicebank in `data/`. It concatenates Japanese VCV units and synthesises
them with a WORLD-style vocoder. Target: embeddable in an Android APK. See `README.md` for user-facing usage.

## Commands

```sh
gradle :bankc:compileVoice      # data/ → bankc/build/voice/nana.vpk (re-run after changing analysis code in bankc or core/world)
gradle check                    # all self-tests (depends on compileVoice)
gradle :core:selfTest -Ponly=kana   # tests whose name contains "kana"
gradle :cli:run --args='-o out.wav --phonemes "Hello *world*~"'   # synthesise; --phonemes prints the kana plan
gradle :cli:run --args='--knobs'    # list style knobs
gradle :cli:audition            # render the style-comparison set to samples/tuning/<variant>/
```

- There is no JUnit. Tests are plain methods registered with `run("name", ...)` in
  `core/src/test/java/dev/nanatts/SelfTests.java`, executed by the `selfTest` JavaExec task (`test` delegates to it).
  Add a test by writing a method and registering it in `main`.
- The build has no external dependencies, on purpose: it must work offline. In the Claude sandbox, Maven Central and
  services.gradle.org are unreachable, so `./gradlew` cannot bootstrap. Use the system `gradle` with
  `--offline` and `GRADLE_USER_HOME=$PWD/.gradle-home` (without that, Gradle fails to load its native library).
- `bankc` also has a `RoundTrip` diagnostic (analyse → resynthesise one wav, reports level and spectral distance).

## Architecture

Modules: `core` (the library, Android-safe), `bankc` (desktop-only voicebank compiler), `cli` (desktop demo,
`Say` and `Audition`).

**Offline compile step (`bankc/VoiceCompiler`):** parses `oto.ini` (windows-31j). Per wav:
- f0 via YIN constrained to ±1.6× the file median (the bank is sung at a level pitch);
- `CheapTrick` envelope and `Aperiodicity` bands at a 5 ms frame period;
- `repairVoicing`: loud frames dominated by energy below 3 kHz that YIN rejected are re-voiced;
- loudness normalisation, and `detectConsonantStart` per unit.

Results are stored quantised in a `VoicePack`. Devices never analyse audio.

**Runtime pipeline (`Synthesizer.speak`):**
`TextFrontend` (`g2p/EnglishFrontend` or `text/KanaFrontend`) → `List<Phrase>` of `Mora`
→ `prosody/ProsodyModel.plan` (durations, gains, pitch knots, driven by a `StyleProfile`)
→ `synth/Renderer.render` (VCV unit selection, time-mapping, cross-fades → `ParamTrack`)
→ `world/WorldSynthesizer` → PCM. Synthesis runs phrase by phrase, and the streaming and batch APIs must
produce identical audio (tested).

Key cross-file facts:
- **Mora is the interface between language and voice.** Frontends only emit morae (kana that must exist in the bank,
  see `text/KanaTable`), plus linguistic flags (stress, accentHead, epenthetic, glide, emphasis…). `ProsodyModel`
  interprets the flags; `Renderer` only needs kana + plan. Unit alias = previous vowel (`a i u e o n`, or `-` after
  silence) + `" "` + kana; the bank has every combination (tested).
- **English path:** `EnglishNormalizer` (numbers, cues `* ~ ♪ !!`, kana runs kept as words, NFKC/diacritics with
  dakuten preserved) → `EnglishG2P` (CMUdict byte-array binary search → suffix/compound decomposition → spelling →
  `LetterToSound`) → `ArpabetToKana` (Japanese-accent style: voiced epenthetic vowels, `cat` → きゃっと).
- **Envelope units:** packs store PSD in dB on an 80-point `MelGrid` (`power · 2/fs`); `ParamTrack.asFrames` converts
  back with `· fs/2`, so packs are sample-rate independent. `CheapTrick` and `WorldSynthesizer` share the normalisation
  "flat envelope p ⇒ mean power p".
- **Renderer time-mapping (`place`):** consonant region = 30 ms decay kept 1:1, compressed middle, 40 ms release
  kept 1:1. Then the vowel, 1:1 or stretched. Neighbouring units cross-fade inside their shared vowel. Post-passes
  matter for pop-free output: the lone-unit fade weight (phrase starts), `cleanVoicing` (bridges short loud unvoiced
  gaps), `softenWeakVoicing`, smoothed `applyGain`. The `parameterContinuity` and `suddenOnsets` tests guard these.
- **Prosody:** pitch is a list of knots `{mora, fraction of vowel, semitones}`. `Renderer.contour` places, smooths and
  vibratos them, then soft-limits to `capLow`/`capHigh`. Variation uses a fixed seed (`ProsodyModel.SEED`); output is
  deterministic. Timing can be pulled onto a beat grid (`rhythm` knob, `ProsodyModel.rhythm`). Beat units are
  syllables (`beatHeads`), and the fit adjusts vowel lengths only. `Synthesizer` plans all phrases before rendering,
  because rhythmic pauses depend on where the next phrase's first beat falls (`Renderer.firstBeat`).
  `StyleProfile` knobs live in the `KNOBS` table, and every preset must define every knob (the
  constructor throws otherwise). `PLAYFUL` is the default style.

## Constraints to preserve

- **Android-safe `core`:** Java 17 bytecode (`options.release = 17`), `-XDstringConcat=inline`, and none of
  `java.nio.file`, `javax.sound`, `java.awt`, or Java 9+ API such as `List.of`/`Map.of`/`Set.of`/`String.isBlank`
  (use `g2p/Tables`). The `android-safe bytecode` self-test scans the class files and enforces this. Desktop-only code
  belongs in `bankc`/`cli`.
- Resources are loaded through `io/ResourceSource` (Android supplies `AssetManager::open`), never via file paths.
- Changing `Segment`/`VoicePack` serialisation requires bumping `VoicePack.VERSION` and recompiling the pack.
- `-Werror` with `-Xlint:all` is on for all modules.

## Verifying audio changes

Nobody can listen inside the sandbox, so verify with measurements and treat listening as the user's job:
- spectrograms (`ffmpeg -i x.wav -lavfi showspectrumpic=s=1000x400:legend=0:scale=log x.png`, then read the image);
- frame-level checks on `ParamTrack` (level steps, voicing flips, f0 in semitones vs `pack.medianF0()`).

Do not judge pops by counting audio-domain clicks: that metric counts ordinary glottal pulses and depends on pitch.
Samples for the user go in `samples/`.
