# android: 春歌ナナ as an Android TTS engine

A system TTS engine app (`dev.nanatts.android`) that speaks with `:core`. Once installed,
select it in Settings → Text-to-speech output, and every app that uses the system TTS speaks
with the 春歌ナナ voice. Voices: `nana-en-US` (English, inline kana) and `nana-ja-JP` (kana).

## Layout

- `NanaTtsService`: `TextToSpeechService`. It handles ISO-3 locale negotiation, voices,
  buffer chunking, stop requests, and word ranges on API 26+.
- `engine/SpeechEngine`: a small interface. `engine/NanaEngine` is the implementation. It
  loads `nana.vpk` from assets on first use and streams 24 kHz PCM16 phrase by phrase.
- `CheckVoiceDataActivity`, `GetSampleTextActivity`, `InstallVoiceDataActivity` and
  `SettingsActivity`: the framework's engine intents.

## Building

The module is part of the root build whenever an Android SDK is configured: `sdk.dir` in the root
`local.properties` (gitignored), or `ANDROID_HOME` / `ANDROID_SDK_ROOT`. Without an SDK, Gradle leaves it
out, and the other modules build without downloading anything Android-related.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # once, from the repo root
./gradlew :android:assembleDebug     # → android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:assembleRelease   # → android/build/outputs/apk/release/android-release-unsigned.apk
adb install android/build/outputs/apk/debug/android-debug.apk
```

- **SDK:** the module needs platform `android-37` and build-tools `37.0.0`. `buildToolsVersion` is pinned,
  so AGP does not try to install a different version into the SDK.
- **AGP:** version 9.5.0 is declared in the root build (`apply false`) next to the Kotlin Gradle plugin. AGP 9 has
  built-in Kotlin, and with both plugins on different class loaders AGP fails to create its Kotlin target.
- **Compiler settings:** the root `subprojects {}` block (`jvmTarget = 17`, warnings as errors) skips this module.
  AGP configures its own compile tasks.
- **Voice pack:** `copyVoicePack` depends on `:bankc:compileVoice` and adds `nana.vpk` to the
  generated assets uncompressed. CMUdict comes in through `:core`'s Java resources.
- **Release signing:** the release APK is unsigned. Sign it with `apksigner`, or add a
  `signingConfig`.

## Why R8 is on for debug builds

`core` used Java records before it was ported to Kotlin. With minSdk < 34, D8 rewrites records to extend the synthetic
`com.android.tools.r8.RecordTag`. When D8 dexed core incrementally as a dependency, that
class was missing from the APK, and the app crashed with `NoClassDefFoundError` on the first
`VoicePack` load. R8 dexes the whole program at once and generates it, and `-dontobfuscate`
keeps stack traces readable. `core` has no records any more, so this could be revisited.
