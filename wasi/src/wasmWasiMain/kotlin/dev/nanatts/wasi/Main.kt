package dev.nanatts.wasi

import dev.nanatts.Language
import dev.nanatts.Synthesizer
import dev.nanatts.VoiceParams
import dev.nanatts.bank.VoicePack
import dev.nanatts.io.WavIO
import dev.nanatts.prosody.StyleProfile
import kotlin.math.roundToLong

private val USAGE = """
    usage: nana-tts [options] [--] <text...>
      -o, --out FILE       write the wav to FILE (default '-' = standard output)
      -f, --file FILE      read the text from FILE ('-' = standard input)
      -l, --lang en|ja     input language (ja = kana only), default en
      -p, --pitch X        pitch factor (default 1.0)
      -s, --speed X        speaking rate factor (default 1.0)
      -r, --range X        intonation range factor (default 1.0)
      -b, --breath X       breathiness 0..1 (default 0)
      -v, --volume X       volume factor (default 1.0)
          --style S        playful (default), neutral, or a .properties file of knobs
          --set KNOB=X     override one style knob (repeatable; see --knobs)
          --knobs          list the style knobs with their playful values
          --rate HZ        output sample rate (default 24000)
          --pack FILE      voice pack (default nana.vpk)
          --phonemes       print the kana transcription (to standard error when writing the wav to standard output)
      -h, --help

    Files are resolved in the preopened directories, e.g.
      wasmtime run --dir dist::/ dist/nana-tts.wasm -o /hello.wav "Hello!"
      echo "Hello!" | wasmtime run --dir dist::/ dist/nana-tts.wasm -f - > hello.wav
""".trimIndent()

private class UsageException(message: String) : Exception(message)

fun main() {
    try {
        run(Wasi.args())
    } catch (e: UsageException) {
        err(e.message + "\n" + USAGE)
        Wasi.exit(2)
    } catch (e: Throwable) {
        err("nana-tts: " + (e.message ?: e.toString()))
        Wasi.exit(1)
    }
}

private fun run(args: List<String>) {
    var out = "-"
    var file: String? = null
    var pack = "nana.vpk"
    var lang = Language.ENGLISH
    var vp = VoiceParams.defaults()
    var rate = 24000
    var phonemes = false
    val words = ArrayList<String>()
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: throw UsageException("missing value for ${args[i - 1]}")
    fun number(): Double = value().let { it.toDoubleOrNull() ?: throw UsageException("not a number: $it") }
    var options = true
    while (i < args.size) {
        val a = args[i]
        if (!options || !a.startsWith("-") || a == "-") {
            words.add(a)
            i++
            continue
        }
        try {
            when (a) {
                "--" -> options = false
                "-o", "--out" -> out = value()
                "-f", "--file" -> file = value()
                "-l", "--lang" -> lang = when (value().lowercase()) {
                    "en", "english" -> Language.ENGLISH
                    "ja", "jp", "japanese" -> Language.JAPANESE_KANA
                    else -> throw UsageException("unknown language ${args[i]}")
                }
                "-p", "--pitch" -> vp = vp.pitch(number())
                "-s", "--speed" -> vp = vp.speed(number())
                "-r", "--range" -> vp = vp.range(number())
                "-b", "--breath" -> vp = vp.breathiness(number())
                "-v", "--volume" -> vp = vp.volume(number())
                "--style" -> vp = vp.style(
                    when (val s = value()) {
                        "playful" -> StyleProfile.PLAYFUL
                        "neutral" -> StyleProfile.NEUTRAL
                        else -> StyleProfile.PLAYFUL.load(Wasi.readFile(s).decodeToString())
                    },
                )
                "--set" -> {
                    val kv = value().split("=", limit = 2)
                    if (kv.size != 2) {
                        throw UsageException("--set expects KNOB=VALUE")
                    }
                    val v = kv[1].trim().toDoubleOrNull() ?: throw UsageException("not a number: ${kv[1]}")
                    vp = vp.style(vp.style.with(kv[0].trim(), v))
                }
                "--knobs" -> {
                    Wasi.write(Wasi.STDOUT, StyleProfile.PLAYFUL.toString().encodeToByteArray())
                    return
                }
                "--rate" -> rate = value().let { it.toIntOrNull() ?: throw UsageException("not a number: $it") }
                "--pack" -> pack = value()
                "--phonemes" -> phonemes = true
                "-h", "--help" -> {
                    Wasi.write(Wasi.STDOUT, (USAGE + "\n").encodeToByteArray())
                    return
                }
                else -> throw UsageException("unknown option $a")
            }
        } catch (e: IllegalArgumentException) {
            throw UsageException(e.message ?: "invalid option value")
        }
        i++
    }
    val text = when (file) {
        null -> words.joinToString(" ")
        "-" -> Wasi.readAll(Wasi.STDIN).decodeToString()
        else -> Wasi.readFile(file).decodeToString()
    }
    if (text.isBlank()) {
        throw UsageException("no text given")
    }

    val tts = Synthesizer.builder(VoicePack.read(Wasi.readFile(pack))).language(lang).sampleRate(rate).build()
    val info = if (out == "-") Wasi.STDERR else Wasi.STDOUT
    if (phonemes) {
        Wasi.write(info, tts.analyze(text).joinToString("") { "$it\n" }.encodeToByteArray())
    }
    val clip = tts.speak(text, vp)
    val wav = WavIO.encode(clip)
    if (out == "-") {
        Wasi.write(Wasi.STDOUT, wav)
    } else {
        Wasi.writeFile(out, wav)
        Wasi.write(info, "wrote $out (${twoDecimals(clip.durationSeconds)} s)\n".encodeToByteArray())
    }
}

private fun err(message: String) {
    Wasi.write(Wasi.STDERR, (message + "\n").encodeToByteArray())
}

private fun twoDecimals(v: Double): String {
    val hundredths = (v * 100).roundToLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
