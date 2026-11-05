# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An offline, pure-Kotlin TTS library that speaks English (plus hiragana/katakana, inline or as pure Japanese
kana input) with the 春歌ナナ UTAU voicebank in `data/`. It concatenates Japanese VCV units and synthesises
them with a WORLD-style vocoder. `core` is Kotlin Multiplatform: a JVM target (embeddable in an Android APK) and a
standalone WebAssembly (WASI) target, which produce bit-identical audio. See `README.md` for user-facing usage.

## Commands

```sh
gradle :bankc:compileVoice      # data/ → bankc/build/voice/nana.vpk (re-run after changing analysis code in bankc or core/world)
gradle check                    # all self-tests (depends on compileVoice)
gradle :core:selfTest -Ponly=kana   # tests whose name contains "kana"
gradle :cli:run --args='-o out.wav --phonemes "Hello *world*~"'   # synthesise; --phonemes prints the kana plan
gradle :cli:run --args='--knobs'    # list style knobs
gradle :cli:audition            # render the style-comparison set to samples/tuning/<variant>/
gradle :android:assembleDebug   # TTS engine APK → android/build/outputs/apk/debug/ (needs local.properties sdk.dir)
gradle :wasi:assembleWasi       # WASI build → wasi/build/dist/; try: wasmtime run --dir wasi/build/dist::/ wasi/build/dist/nana-tts.wasm -o /x.wav "Hi"
gradle :core:generateUnicodeData   # regenerate core/.../text/UnicodeData.kt from the build JDK's java.text.Normalizer
```

- There is no JUnit. Tests are plain functions registered with `run("name", ...)` in
  `core/src/jvmTest/kotlin/dev/nanatts/SelfTests.kt`, executed on the JVM by the `selfTest` JavaExec task (`jvmTest`
  delegates to it). Add a test by writing a function and registering it in `main`. The WASI build has no test runner;
  check it by comparing its output with the JVM build (it must be bit-identical).
- The modules have no library dependencies besides the Kotlin standard library. The Kotlin Gradle plugin (2.4.21) comes
  from Maven Central; offline builds need it in the Gradle cache.
- `android` (AGP 9.5, built-in Kotlin) is only included when `local.properties` has `sdk.dir` or `ANDROID_HOME` /
  `ANDROID_SDK_ROOT` is set. The SDK here is `/opt/android-sdk` (read-only, so `buildToolsVersion` is pinned to the
  installed 37.0.0). AGP and lint come from Google Maven (cached in `.gradle-home` now). Debug signing fails inside
  the sandbox because AGP cannot lock `~/.android/debug.keystore`, so APK builds have to run outside it. `gradle check` also runs Android lint.
- In the Claude sandbox, Maven Central and services.gradle.org are unreachable, so `./gradlew` cannot bootstrap. Use the
  system `gradle` with `--offline` and `GRADLE_USER_HOME=$PWD/.gradle-home` (without that, Gradle fails to load its
  native library).
- `bankc` also has a `RoundTrip` diagnostic (analyse → resynthesise one wav, reports level and spectral distance).

## Architecture

Modules:
- `core`: the library (Kotlin Multiplatform). Almost everything is in `commonMain`; `jvmMain` has the classpath
  dictionary, `ResourceSource` helpers, pack writing and the `oto.ini` parser (windows-31j, bankc only);
  `wasmWasiMain` has minimal WASI bindings (`wasi/Wasi`: args, stdio, files in preopened directories, exit).
- `bankc`: desktop-only voicebank compiler (Kotlin/JVM).
- `cli`: desktop demo (`Say` and `Audition`, Kotlin/JVM).
- `wasi`: the WASI command line (`Main.kt`), built with `:wasi:assembleWasi` into `wasi/build/dist` together with the
  voice pack and dictionary.
- `android`: system TTS engine app (`TextToSpeechService`) in Kotlin on top of `core`; see `android/README.md`.
  R8 is enabled for debug too: incremental D8 dropped the `RecordTag` synthetic the former Java records needed.
  AGP is declared in the root build next to the Kotlin plugin so both share a class loader.

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

- **Android-safe JVM target:** Java 17 bytecode and API (`jvmTarget = 17`, `-Xjdk-release=17`),
  `-Xstring-concat=inline`, and none of `java.nio.file`, `javax.sound`, `java.awt`, or Java 9+ API such as
  `List.of`/`String.isBlank`. The `android-safe bytecode` self-test scans the class files and enforces this.
  Desktop-only code belongs in `bankc`/`cli`.
- **Platform-neutral `commonMain`:** no JDK API. The pieces that replace it must keep JVM and WASI output identical:
  - `util/JavaRandom` is `java.util.Random` (seeded variation must not change); `util/JavaCompat` has Java's
    `Math.round`, `String.hashCode` (it seeds each phrase), `Character.isWhitespace` and `String.split` semantics.
  - `text/Normalizer` implements NFC/NFD/NFKC/NFKD with tables generated from the JDK
    (`gradle :core:generateUnicodeData`); a self-test compares every code point with `java.text.Normalizer`.
  - `io/Gzip` inflates voice packs and the dictionary; `io/ByteReader` reads the `DataOutputStream` format.
  - Keep Java's float/double arithmetic order (e.g. `Segment.f0` divides in float) when porting or refactoring.
- Kotlin/Wasm computes `exp`/`ln`/`sin`/`cos` in software (about 17× slower than the JVM), which dominates WASI
  synthesis time.
- Resources are loaded through `io/ResourceSource` (Android: `ResourceSource.streams(assets::open)`), never via file
  paths. On WASI, `bundledDictionary()` reads `cmudict.tsv.gz` from the preopened directory.
- Changing `Segment`/`VoicePack` serialisation requires bumping `VoicePack.VERSION` and recompiling the pack.
- `allWarningsAsErrors` is on for all Kotlin modules except `android`.

## Verifying audio changes

Nobody can listen inside the sandbox, so verify with measurements and treat listening as the user's job:
- spectrograms (`ffmpeg -i x.wav -lavfi showspectrumpic=s=1000x400:legend=0:scale=log x.png`, then read the image);
- frame-level checks on `ParamTrack` (level steps, voicing flips, f0 in semitones vs `pack.medianF0`).

Do not judge pops by counting audio-domain clicks: that metric counts ordinary glottal pulses and depends on pitch.
Samples for the user go in `samples/`.
