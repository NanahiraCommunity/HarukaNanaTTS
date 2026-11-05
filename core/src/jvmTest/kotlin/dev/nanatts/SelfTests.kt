package dev.nanatts

import dev.nanatts.bank.MelGrid
import dev.nanatts.bank.OtoIni
import dev.nanatts.bank.VoicePack
import dev.nanatts.bank.read
import dev.nanatts.bank.write
import dev.nanatts.dsp.FFT
import dev.nanatts.g2p.ArpabetToKana
import dev.nanatts.g2p.CmuDict
import dev.nanatts.g2p.EnglishFrontend
import dev.nanatts.g2p.EnglishG2P
import dev.nanatts.g2p.EnglishNormalizer
import dev.nanatts.io.AudioClip
import dev.nanatts.io.Gzip
import dev.nanatts.io.InvalidDataException
import dev.nanatts.io.ResourceSource
import dev.nanatts.io.WavIO
import dev.nanatts.io.classpath
import dev.nanatts.prosody.ProsodyModel
import dev.nanatts.prosody.StyleProfile
import dev.nanatts.synth.Renderer
import dev.nanatts.text.KanaFrontend
import dev.nanatts.text.KanaTable
import dev.nanatts.text.Mora
import dev.nanatts.text.Normalizer
import dev.nanatts.text.Phrase
import dev.nanatts.util.JavaRandom
import dev.nanatts.util.PortableMath
import dev.nanatts.world.Aperiodicity
import dev.nanatts.world.CheapTrick
import dev.nanatts.world.F0Estimator
import dev.nanatts.world.WorldSynthesizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/** Dependency-free test runner (no JUnit). Run with `gradle check`. */
object SelfTests {

    private var passed = 0
    private val failures = ArrayList<String>()

    private val DATA = File(System.getProperty("nanatts.dataDir", "data"))
    private val PACK = File(System.getProperty("nanatts.pack", "bankc/build/voice/nana.vpk"))
    private val OUT = File(System.getProperty("nanatts.outDir", "build/selftest"))

    /** Optional substring filter on test names (`gradle :core:selfTest -Ponly=kana`). */
    private val ONLY = System.getProperty("nanatts.only", "")

    private lateinit var dict: CmuDict
    private var otoAliases: Set<String>? = null

    @JvmStatic
    fun main(args: Array<String>) {
        dict = CmuDict.load(ResourceSource.classpath())
        run("fft round trip", ::fftRoundTrip)
        run("wav write/read", ::wavRoundTrip)
        run("gzip matches java.util.zip", ::gzip)
        run("java.util.Random port", ::javaRandom)
        run("portable exp/ln/sin/cos accuracy", ::portableMath)
        run("unicode normalisation matches java.text", ::unicodeNormalisation)
        run("world analysis/synthesis round trip", ::worldRoundTrip)
        run("oto.ini parsing", ::otoParsing)
        run("kana table covered by voicebank", ::kanaCoverage)
        run("kana frontend", ::kanaFrontend)
        run("cmudict lookup", ::cmuDict)
        run("g2p fallbacks", ::g2pFallbacks)
        run("text normalisation", ::normalisation)
        run("arpabet to kana", ::arpabetToKana)
        run("all dictionary words map to bank kana", ::dictionaryCoverage)
        run("voice pack round trip", ::packRoundTrip)
        run("english synthesis", ::englishSynthesis)
        run("no pops: parameter continuity", ::parameterContinuity)
        run("japanese kana synthesis", ::japaneseSynthesis)
        run("style profiles", ::styleProfiles)
        run("expressive text cues", ::textCues)
        run("kana inside english text", ::kanaInEnglish)
        run("playful style moves more than neutral", ::playfulVsNeutral)
        run("pitch stays inside the style's cap", ::pitchCap)
        run("questions rise, statements fall", ::endings)
        run("rhythm: syllables and phrases on the beat grid", ::rhythmGrid)
        run("android-safe bytecode", ::androidSafety)
        System.out.printf("%n%d passed, %d failed%n", passed, failures.size)
        for (f in failures) {
            println("  FAIL $f")
        }
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    private fun run(name: String, test: () -> Unit) {
        if (ONLY.isNotEmpty() && !name.contains(ONLY)) {
            return
        }
        val t0 = System.nanoTime()
        try {
            test()
            passed++
            System.out.printf("ok   %-45s %6.0f ms%n", name, (System.nanoTime() - t0) / 1e6)
        } catch (e: Throwable) {
            failures.add("$name: $e")
            System.out.printf("FAIL %-45s %s%n", name, e)
            if (e !is AssertionError) {
                e.printStackTrace(System.out)
            }
        }
    }

    private fun check(cond: Boolean, msg: String) {
        if (!cond) {
            throw AssertionError(msg)
        }
    }

    private fun eq(expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionError("expected <$expected> but was <$actual>")
        }
    }

    // ---------------------------------------------------------------- DSP / IO / platform

    private fun fftRoundTrip() {
        val fft = FFT(256)
        val r = Random(1)
        val re = DoubleArray(256)
        val im = DoubleArray(256)
        val orig = DoubleArray(256)
        for (i in 0 until 256) {
            orig[i] = r.nextGaussian()
            re[i] = orig[i]
        }
        fft.forward(re, im)
        // Parseval
        var et = 0.0
        var ef = 0.0
        for (i in 0 until 256) {
            et += orig[i] * orig[i]
            ef += re[i] * re[i] + im[i] * im[i]
        }
        check(abs(ef / 256 - et) < 1e-9 * et, "Parseval")
        fft.inverse(re, im)
        for (i in 0 until 256) {
            check(abs(re[i] - orig[i]) < 1e-12 && abs(im[i]) < 1e-12, "inverse mismatch at $i")
        }
    }

    private fun wavRoundTrip() {
        val x = FloatArray(1000) { (sin(it * 0.05) * 0.5f).toFloat() }
        val back = WavIO.read(WavIO.encode(AudioClip(x, 22050)))
        eq(22050, back.sampleRate)
        eq(1000, back.samples.size)
        for (i in x.indices) {
            check(abs(back.samples[i] - x[i]) < 1e-4, "sample $i")
        }
    }

    private fun gzip() {
        val rnd = Random(7)
        val text = (0 until 20000).joinToString(" ") { "word" + rnd.nextInt(500) }.encodeToByteArray()
        val noise = ByteArray(300_000).also { rnd.nextBytes(it) }
        for (data in listOf(ByteArray(0), text, noise, text + noise + text)) {
            for (level in intArrayOf(Deflater.NO_COMPRESSION, Deflater.BEST_SPEED, Deflater.DEFAULT_COMPRESSION, 9)) {
                val gz = gzipped(data, level)
                check(Gzip.decompress(gz).contentEquals(data), "level $level, ${data.size} bytes")
            }
        }
        // concatenated members
        check(Gzip.decompress(gzipped(text, 6) + gzipped(noise, 1)).contentEquals(text + noise), "multi-member")
        // corruption is detected
        val bad = gzipped(text, 6)
        bad[bad.size - 6] = (bad[bad.size - 6] + 1).toByte()
        try {
            Gzip.decompress(bad)
            throw AssertionError("corrupt CRC accepted")
        } catch (expected: InvalidDataException) {
            // ok
        }
    }

    private fun gzipped(data: ByteArray, level: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        object : GZIPOutputStream(bos) {
            init {
                def.setLevel(level)
            }
        }.use { it.write(data) }
        return bos.toByteArray()
    }

    private fun javaRandom() {
        for (seed in longArrayOf(0, 1, 42, -7, 0x5EED, Long.MAX_VALUE)) {
            val a = Random(seed)
            val b = JavaRandom(seed)
            repeat(2000) {
                when (it % 3) {
                    0 -> eq(a.nextDouble(), b.nextDouble())
                    1 -> eq(a.nextGaussian(), b.nextGaussian())
                    else -> eq(a.nextInt(), b.nextInt())
                }
            }
        }
    }

    /** The WASI build's exp/ln/sin/cos against StrictMath (errors in ulp of the exact result). */
    private fun portableMath() {
        val rnd = Random(11)
        fun ulps(ours: Double, exact: Double): Double =
            if (ours == exact) 0.0 else abs(ours - exact) / Math.ulp(exact)
        var worstExp = 0.0
        var worstLn = 0.0
        var worstTrig = 0.0
        repeat(2_000_000) {
            val e = rnd.nextDouble() * 1450 - 740
            worstExp = maxOf(worstExp, ulps(PortableMath.exp(e), StrictMath.exp(e)))
            val l = Math.pow(10.0, rnd.nextDouble() * 600 - 300) * (0.5 + rnd.nextDouble())
            worstLn = maxOf(worstLn, ulps(PortableMath.ln(l), StrictMath.log(l)))
            // the vocoder's phases are within a few hundred radians; absolute error relative to the argument
            val t = (rnd.nextDouble() * 2 - 1) * Math.pow(10.0, rnd.nextDouble() * 4 - 1)
            val scale = Math.ulp(maxOf(1.0, abs(t)))
            worstTrig = maxOf(worstTrig, abs(PortableMath.sin(t) - StrictMath.sin(t)) / scale,
                abs(PortableMath.cos(t) - StrictMath.cos(t)) / scale)
        }
        check(worstExp <= 2 && worstLn <= 2 && worstTrig <= 2, "worst errors: exp $worstExp, ln $worstLn, sin/cos $worstTrig ulp")
        eq(Double.POSITIVE_INFINITY, PortableMath.exp(710.0))
        eq(0.0, PortableMath.exp(-746.0))
        eq(Double.NEGATIVE_INFINITY, PortableMath.ln(0.0))
        check(PortableMath.ln(-1.0).isNaN() && PortableMath.exp(Double.NaN).isNaN(), "NaN handling")
        check(abs(PortableMath.ln(Double.MIN_VALUE) - StrictMath.log(Double.MIN_VALUE)) < 1e-12, "subnormal ln")
        eq(1.0, PortableMath.exp(0.0))
        eq(0.0, PortableMath.ln(1.0))
    }

    /**
     * Every code point in every form, plus random strings of starters, combining marks and Hangul jamo.
     * A failure after a JDK upgrade means the tables are outdated: run `gradle :core:generateUnicodeData`.
     */
    private fun unicodeNormalisation() {
        val forms = mapOf(
            Normalizer.Form.NFC to java.text.Normalizer.Form.NFC,
            Normalizer.Form.NFD to java.text.Normalizer.Form.NFD,
            Normalizer.Form.NFKC to java.text.Normalizer.Form.NFKC,
            Normalizer.Form.NFKD to java.text.Normalizer.Form.NFKD,
        )
        val bad = ArrayList<String>()
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) {
                continue
            }
            val s = String(Character.toChars(cp))
            for ((ours, theirs) in forms) {
                if (Normalizer.normalize(s, ours) != java.text.Normalizer.normalize(s, theirs) && bad.size < 10) {
                    bad.add("U+%04X %s".format(cp, ours))
                }
            }
        }
        check(bad.isEmpty(), "differs from java.text.Normalizer (regenerate the tables?): $bad")
        val pool = "aeouAEOU\u0300\u0301\u0302\u0303\u0308\u0323\u0327\u031B\u0345\u05B0\u0591각" +
            "가각\u3099\u309AかカｶﾞÅÅḊ\u0F73\u0F81שּׁ"
        val rnd = Random(3)
        repeat(20000) {
            val s = String(CharArray(1 + rnd.nextInt(8)) { pool[rnd.nextInt(pool.length)] })
            for ((ours, theirs) in forms) {
                eq(java.text.Normalizer.normalize(s, theirs), Normalizer.normalize(s, ours))
            }
        }
    }

    /** Analyse a voicebank recording, resynthesise it at 24 kHz and compare level and envelope. */
    private fun worldRoundTrip() {
        val clip = WavIO.read(File(DATA, "_ああいあう.wav").readBytes())
        val x = clip.samples
        val fs = clip.sampleRate
        val fp = 0.005
        val f0 = F0Estimator(80.0, 1200.0).medianSpread(1.6).estimate(x, fs, fp).f0
        val median = F0Estimator.median(f0)
        check(median > 250 && median < 500, "median f0 $median")
        val ct = CheapTrick(fs, 80.0)
        val apEst = Aperiodicity(fs, Aperiodicity.DEFAULT_BANDS, -60.0)
        val grid = MelGrid(80, 12000.0)
        val track = dev.nanatts.synth.ParamTrack(f0.size, grid.size, Aperiodicity.DEFAULT_BANDS.size)
        val power = DoubleArray(ct.fftSize / 2 + 1)
        val ap = DoubleArray(Aperiodicity.DEFAULT_BANDS.size)
        for (i in f0.indices) {
            track.f0[i] = f0[i]
            ct.estimate(x, i * fp, if (f0[i] > 0) f0[i] else median, power)
            grid.fromLinear(power, ct.fftSize, fs, 2.0 / fs, track.envDb(i))
            apEst.estimate(x, i * fp, f0[i], ap)
            for (b in ap.indices) {
                track.apDb(i)[b] = (10 * log10(ap[b])).toFloat()
            }
        }
        val outFs = 24000
        val y = WorldSynthesizer(outFs, fp, 1024).synthesize(track.asFrames(grid, Aperiodicity.DEFAULT_BANDS, outFs, 1024), 1)
        val levelDb = 20 * log10(rms(y) / rms(x))
        check(abs(levelDb) < 3, "level differs by $levelDb dB")
        val ct2 = CheapTrick(outFs, 80.0)
        val p2 = DoubleArray(ct2.fftSize / 2 + 1)
        val e2 = FloatArray(grid.size)
        var dist = 0.0
        var n = 0
        for (i in f0.indices) {
            if (f0[i] <= 0) {
                continue
            }
            ct2.estimate(y, i * fp, f0[i], p2)
            grid.fromLinear(p2, ct2.fftSize, outFs, 2.0 / outFs, e2)
            var s = 0.0
            for (b in 0 until grid.size) {
                val d = (e2[b] - track.envDb(i)[b]).toDouble()
                s += d * d
            }
            dist += sqrt(s / grid.size)
            n++
        }
        check(dist / n < 4, "log-spectral distance ${dist / n} dB")
    }

    private fun rms(v: FloatArray): Double {
        var s = 0.0
        for (f in v) {
            s += f * f
        }
        return sqrt(s / v.size)
    }

    // ---------------------------------------------------------------- voicebank & kana

    private fun otoAliases(): Set<String> {
        otoAliases?.let { return it }
        val aliases = File(DATA, "oto.ini").inputStream().use { input -> OtoIni.parse(input).map { it.alias }.toHashSet() }
        otoAliases = aliases
        return aliases
    }

    private fun otoParsing() {
        val entries = File(DATA, "oto.ini").inputStream().use { OtoIni.parse(it) }
        eq(1270, entries.size)
        val first = entries[0]
        eq("_ああいあう.wav", first.file)
        eq("- あ", first.alias)
        check(abs(first.offset - 701.023) < 1e-9, "offset")
        check("a か" in otoAliases() && "n ん" in otoAliases() && "o ヴぁ" in otoAliases(), "expected aliases missing")
    }

    private fun kanaCoverage() {
        val aliases = otoAliases()
        val missing = ArrayList<String>()
        val cons = "k g s z sh j t d ts ch n h f b p m y r w v ky gy ny hy by py my ry kw gw".split(" ") + ""
        for (c in cons) {
            for (v in "aiueo") {
                if (!KanaTable.exists(c, v)) {
                    continue
                }
                val kana = KanaTable.mora(c, v).kana
                for (prev in arrayOf("-", "a", "i", "u", "e", "o", "n")) {
                    if ("$prev $kana" !in aliases) {
                        missing.add("$prev $kana")
                    }
                }
            }
        }
        check(missing.isEmpty(), "aliases missing from voicebank: $missing")
    }

    private fun kanaFrontend() {
        val p = KanaFrontend().analyze("コンニチハ、ティーです！")
        eq(2, p.size)
        eq("[こ, ん, に, ち, は]", p[0].morae.toString())
        eq(Phrase.Ending.CONTINUATION, p[0].ending)
        eq("てぃ", p[1].morae[0].kana)
        check(p[1].morae[0].longVowel, "ー lengthens the previous mora")
        eq(Phrase.Ending.EXCLAMATION, p[1].ending)
        val g = KanaFrontend().analyze("きって")
        check(g[0].morae[1].geminate, "っ marks the next mora")
    }

    // ---------------------------------------------------------------- English frontend

    private fun cmuDict() {
        check(dict.size > 120000, "dictionary size ${dict.size}")
        eq("[HH, AH0, L, OW1]", dict.lookup("hello").contentToString())
        eq("[HH, AH0, L, OW1]", dict.lookup("Hello").contentToString())
        eq("[D, OW1, N, T]", dict.lookup("don't").contentToString())
        check(dict.lookup("a") != null && dict.lookup("zygote") != null, "first/last entries")
        check(dict.lookup("qwxzv") == null, "unknown word")
    }

    private fun g2pFallbacks() {
        val g = EnglishG2P(dict)
        // spelled acronym
        eq("[EY1, B, IY1, S, IY1]", g.phones("#ABC").contentToString())
        // dictionary word in caps is read as a word
        eq(dict.lookup("hello").contentToString(), g.phones("#HELLO").contentToString())
        // morphology: unknown derived forms use the stem
        check(g.phones("blorpingly").isNotEmpty(), "letter-to-sound fallback produced nothing")
        eq("[K, AE1, T, D, AO2, G, Z]", g.phones("catdogs").contentToString())
        for (w in arrayOf("zorbitron", "kawaii", "nanahira", "xyzzy", "brrr")) {
            check(g.phones(w).isNotEmpty(), "no phones for $w")
        }
    }

    private fun normalisation() {
        val n = EnglishNormalizer()
        eq("[it costs three dollars and fifty cents [STATEMENT]]", n.normalize("It costs $3.50.").toString())
        eq("[the twenty first of march nineteen eighty four [STATEMENT]]", n.normalize("The 21st of March 1984").toString())
        eq("[doctor smith lives on baker street [STATEMENT]]", n.normalize("Dr. Smith lives on Baker St.").toString())
        eq(Phrase.Ending.WH_QUESTION, n.normalize("What is it?")[0].ending)
        eq(Phrase.Ending.QUESTION, n.normalize("Is it?")[0].ending)
        eq("[hello [CONTINUATION], world [EXCLAMATION]]", n.normalize("Hello, world!").toString())
        eq("[fifty percent of one thousand two hundred fifty point five [STATEMENT]]", n.normalize("50% of 1,250.5").toString())
        eq("[at three oh five you and me [STATEMENT]]", n.normalize("at 3:05 you & me").toString())
        eq("[minus five degrees in a cafe [STATEMENT]]", n.normalize("-5 degrees in a café").toString())
        eq("[well known [STATEMENT]]", n.normalize("well-known").toString())
    }

    private fun kana(phones: Array<String>?): String = ArpabetToKana.convert(phones!!).joinToString("") { it.kana }

    private fun arpabetToKana() {
        eq("はろう", kana(dict.lookup("hello")))
        eq("きゃと", kana(dict.lookup("cat")))
        eq("すとりと", kana(dict.lookup("street")))
        eq("きゅと", kana(dict.lookup("cute")))
        val cat = ArpabetToKana.convert(dict.lookup("cat")!!)
        check(cat[1].geminate && !cat[1].devoiced && cat[1].epenthetic, "cat: held, voiced と ending")
        check(cat[0].accentHead && cat[0].stress == 1, "cat: stressed head")
        val car = ArpabetToKana.convert(dict.lookup("car")!!)
        eq(1, car.size)
        check(car[0].longVowel, "non-rhotic r lengthens the vowel")
        eq("うぃん", kana(dict.lookup("win")))
        eq("くぃく", kana(dict.lookup("quick")))
    }

    /** Every mora produced for a large sample of dictionary words must exist in the voicebank. */
    private fun dictionaryCoverage() {
        val aliases = otoAliases()
        val bad = HashSet<String>()
        val fe = EnglishFrontend(dict)
        val words = ("the quick brown fox jumps over the lazy dog while seven thoughtful zebras vanish " +
            "through rhythm and texture of judge measure vision usual beautiful music young yesterday " +
            "queue anxious twelfth strengths world girl squirrel heard february library thousand " +
            "photograph language pleasure chocolate computer synthesizer television february").split(" ")
        for (w in words) {
            for (p in fe.analyze(w)) {
                for (m in p.morae) {
                    if ("- ${m.kana}" !in aliases) {
                        bad.add("$w:${m.kana}")
                    }
                }
            }
        }
        // unusual phone sequences and letter-to-sound output
        for (w in arrayOf("a", "about", "zoo", "rhythms", "xylophone", "ngo", "tsunami", "pneumonia")) {
            for (m in ArpabetToKana.convert(fe.g2p.phones(w))) {
                if ("- ${m.kana}" !in aliases) {
                    bad.add("$w:${m.kana}")
                }
            }
        }
        check(bad.isEmpty(), "kana not in voicebank: $bad")
    }

    // ---------------------------------------------------------------- synthesis (needs the compiled pack)

    private val pack: VoicePack by lazy {
        check(PACK.isFile, "voice pack missing (run gradle :bankc:compileVoice): $PACK")
        PACK.inputStream().use { VoicePack.read(it) }
    }

    private fun packRoundTrip() {
        val p = pack
        eq(1270, p.unitCount)
        check(p.medianF0 > 200 && p.medianF0 < 600, "median f0 ${p.medianF0}")
        val bos = ByteArrayOutputStream()
        p.write(bos)
        val q = VoicePack.read(ByteArrayInputStream(bos.toByteArray()))
        eq(p.unitCount, q.unitCount)
        eq(p.units("a か")[0], q.units("a か")[0])
        val a = FloatArray(p.grid.size)
        val b = FloatArray(q.grid.size)
        p.segment(10).envDb(20, a)
        q.segment(10).envDb(20, b)
        check(a.contentEquals(b), "decoded envelopes differ")
        eq(p.metadata, q.metadata)
    }

    private fun checkedSpeech(tts: Synthesizer, text: String, file: String): AudioClip {
        val c = tts.speak(text)
        var sum = 0.0
        var peak = 0f
        for (v in c.samples) {
            check(v.isFinite(), "non-finite sample")
            sum += v * v
            peak = maxOf(peak, abs(v))
        }
        val rms = sqrt(sum / c.samples.size)
        check(rms > 0.01, "too quiet: rms $rms")
        check(peak <= 1.0f, "clipping: $peak")
        if (OUT.isDirectory || OUT.mkdirs()) {
            File(OUT, file).writeBytes(WavIO.encode(c))
        }
        return c
    }

    private fun englishSynthesis() {
        val tts = Synthesizer.builder(pack).language(Language.ENGLISH).build()
        val text = "Hello world! This is a test of the speech synthesizer. Can you understand me?"
        val c = checkedSpeech(tts, text, "english.wav")
        check(c.durationSeconds > 3 && c.durationSeconds < 10, "duration ${c.durationSeconds}")
        // streaming yields one clip per phrase and the same audio
        val parts = ArrayList<AudioClip>()
        tts.speak(text, VoiceParams.defaults()) { parts.add(it) }
        eq(3, parts.size)
        val joined = AudioClip.concat(parts, tts.sampleRate)
        check(joined.samples.contentEquals(c.samples), "streamed audio differs from batch")
        // faster speech is shorter, pitch changes keep the duration
        val fast = tts.speak(text, VoiceParams.defaults().speed(1.5))
        check(fast.durationSeconds < c.durationSeconds * 0.8, "speed has no effect")
        val high = tts.speak(text, VoiceParams.defaults().pitch(1.2))
        check(abs(high.durationSeconds - c.durationSeconds) < 0.05, "pitch changed duration")
        // deterministic
        check(tts.speak(text).samples.contentEquals(c.samples), "output is not deterministic")
        // no pops: no sudden loud onsets in the audio (phrase starts used to jump ~60 dB in 1 ms)
        val jumps = suddenOnsets(c)
        check(jumps <= 2, "$jumps sudden loud onsets (pops)")
    }

    /**
     * Pops come from discontinuities in the rendered parameters: loud level steps between frames, or
     * voicing flipping on and off inside loud sounds (noise bursts in vowels). Both are checked on the
     * parameter track, which, unlike click counting on audio, does not depend on the pitch.
     */
    private fun parameterContinuity() {
        val p = pack
        val text = "Hello world! This is a test of the speech synthesizer. Can you understand me? " +
            "I really like chocolate cake, and *so* do you~ Let's sing ♪ Wow!!"
        for (style in arrayOf(StyleProfile.PLAYFUL, StyleProfile.NEUTRAL, StyleProfile.PLAYFUL.with("rhythm", 1.0).with("snap", 0.8))) {
            val vp = VoiceParams.defaults().style(style)
            val rnd = JavaRandom(ProsodyModel.SEED)
            for (ph in EnglishFrontend(dict).analyze(text)) {
                val t = Renderer(p).render(ph, ProsodyModel().plan(ph, vp, rnd), vp, p.medianF0, 1)
                val level = DoubleArray(t.frames)
                var max = -1e9
                for (f in 0 until t.frames) {
                    var s = 0.0
                    for (v in t.envDb(f)) {
                        s += 10.0.pow(v / 10.0)
                    }
                    level[f] = 10 * log10(s / t.envDb(f).size + 1e-30)
                    max = maxOf(max, level[f])
                }
                for (f in 1 until t.frames) {
                    // consonant-vowel transitions in the recordings reach ~8 dB per frame; pops were 10-60 dB
                    val loud = level[f] > max - 12 && level[f - 1] > max - 12
                    check(!loud || abs(level[f] - level[f - 1]) < 10, "level step of ${level[f] - level[f - 1]} dB in $ph at frame $f")
                    val flip = (t.f0[f] > 0) != (t.f0[f - 1] > 0)
                    check(!(loud && flip && level[f] > max - 10), "voicing flips inside a loud sound in $ph at frame $f")
                }
            }
        }
    }

    /** Places where the 1 ms peak level jumps by more than 20 dB to a loud level. */
    private fun suddenOnsets(c: AudioClip): Int {
        val x = c.samples
        val w = c.sampleRate / 1000
        val n = x.size / w
        val pk = DoubleArray(n) { i ->
            var m = 0f
            for (j in i * w until (i + 1) * w) {
                m = maxOf(m, abs(x[j]))
            }
            20 * log10(m + 1e-9)
        }
        var count = 0
        for (i in 4 until n) {
            val prev = maxOf(maxOf(pk[i - 1], pk[i - 2]), maxOf(pk[i - 3], pk[i - 4]))
            if (pk[i] > -25 && pk[i] - prev > 20) {
                count++
            }
        }
        return count
    }

    private fun japaneseSynthesis() {
        val tts = Synthesizer.builder(pack).language(Language.JAPANESE_KANA).build()
        val c = checkedSpeech(tts, "こんにちは、はるかななです。", "japanese.wav")
        check(c.durationSeconds > 1 && c.durationSeconds < 4, "duration ${c.durationSeconds}")
    }

    // ---------------------------------------------------------------- styles & expressive cues

    private fun styleProfiles() {
        val p = StyleProfile.PLAYFUL
        eq(0.3, p["snap"])
        val q = p.with("snap", 0.9)
        eq(0.9, q["snap"])
        eq(0.3, p["snap"]) // immutable
        try {
            p.with("nope", 1.0)
            throw AssertionError("unknown knob accepted")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        val loaded = StyleProfile.NEUTRAL.load("# comment\naccentHeight = 6.5\nsnap=0.5\n")
        eq(6.5, loaded["accentHeight"])
        eq(0.5, loaded["snap"])
        eq(StyleProfile.NEUTRAL["declination"], loaded["declination"])
        // toString is a loadable properties file
        eq(q, StyleProfile.NEUTRAL.load(q.toString()))
        eq(StyleProfile.PLAYFUL, VoiceParams.defaults().style)
        // pentatonic snapping on the base pitch: 0 2 4 7 9
        check(abs(ProsodyModel.snap(6.6, 1.0) - 7) < 1e-9, "snap to 7")
        check(abs(ProsodyModel.snap(6.6, 0.5) - 6.8) < 1e-9, "half snap")
        check(abs(ProsodyModel.snap(-2.0, 1.0) + 3) < 1e-9, "snap below base to -3 (a 9 of the octave below)")
        eq(5.5, ProsodyModel.snap(5.5, 0.0))
    }

    private fun textCues() {
        val n = EnglishNormalizer()
        val c = n.normalize("This is *really* cute~ ♪")
        eq(1, c.size)
        eq("[this, is, really, cute]", c[0].words.toString())
        eq("[2]", c[0].emphasized.toString())
        eq("[3]", c[0].glides.toString())
        check(c[0].sung, "♪ marks the phrase as sung")
        val w = n.normalize("Wow!! Hi!~ *Two words* here")
        eq(3, w.size)
        check(w[0].excited && w[0].ending == Phrase.Ending.EXCLAMATION, "!! is excited")
        check(!w[1].excited && 0 in w[1].glides, "~ after ! glides the closed phrase")
        eq("[0, 1]", w[2].emphasized.toString())
        val p = EnglishFrontend(dict).analyze("so *cute*~")
        val lastMora = p[0].morae[p[0].morae.size - 1]
        check(lastMora.epenthetic && !lastMora.glide, "glide is not put on the epenthetic と")
        var glided = false
        for (m in p[0].morae) {
            glided = glided or m.glide
            check(m.kana.indexOf('*') < 0 && m.kana.indexOf('~') < 0, "cue spoken: ${m.kana}")
        }
        check(glided, "no mora glides")
        val ja = KanaFrontend().analyze("*すごい*ね〜。やった！！たのしいー")
        check(ja[0].morae[0].emphasis && last(ja[0]).glide, "kana emphasis and 〜 glide")
        check(ja[1].excited && ja[1].ending == Phrase.Ending.EXCLAMATION, "！！ excited")
        check(last(ja[2]).glide && last(ja[2]).longVowel, "phrase-final ー glides")
    }

    private fun kanaOf(p: Phrase): String {
        val sb = StringBuilder()
        for (m in p.morae) {
            if (m.wordStart && sb.isNotEmpty()) {
                sb.append(' ')
            }
            sb.append(m.kana).append(if (m.longVowel) "ー" else "")
        }
        return sb.toString()
    }

    private fun kanaInEnglish() {
        val fe = EnglishFrontend(dict)
        val p = fe.analyze("I love すし and ラーメン!")
        eq(1, p.size)
        check(kanaOf(p[0]).endsWith("すし あんど らーめん"), kanaOf(p[0]))
        // dakuten survive normalisation, っ marks the next mora
        val gakkou = fe.analyze("がっこう")[0].morae
        eq("が", gakkou[0].kana)
        check(gakkou[1].geminate, "っ")
        // half-width katakana, Japanese punctuation, kana glued to latin letters, cues
        val q = fe.analyze("ﾗｰﾒﾝ、Nanaちゃん。*すごい*~")
        eq(3, q.size)
        eq("らーめん", kanaOf(q[0]))
        eq(Phrase.Ending.CONTINUATION, q[0].ending)
        eq("なな ちゃ", kanaOf(q[1]).substring(0, 5))
        check(q[2].morae[0].emphasis && last(q[2]).glide, "cues on kana words")
        // a kana word is accented like a content word
        check(q[0].morae[0].accentHead && q[0].morae[0].stress == 1, "kana word accent")
        // and it is actually spoken
        val tts = Synthesizer.builder(pack).build()
        val withKana = tts.speak("I love すし and ラーメン!").durationSeconds
        val without = tts.speak("I love and!").durationSeconds
        check(withKana > without + 0.4, "kana words are not spoken")
        eq("らーめん", kanaOf(KanaFrontend().analyze("ﾗｰﾒﾝ")[0])) // half-width in the kana frontend too
    }

    private fun last(p: Phrase): Mora = p.morae[p.morae.size - 1]

    /** Pitch in semitones (relative to the voice's base) of the voiced frames of each phrase of a text. */
    private fun pitchTracks(text: String, vp: VoiceParams): List<DoubleArray> {
        val p = pack
        val rnd = JavaRandom(ProsodyModel.SEED)
        return EnglishFrontend(dict).analyze(text).map { ph ->
            val plan = ProsodyModel().plan(ph, vp, rnd)
            val f0 = Renderer(p).render(ph, plan, vp, p.medianF0, 1).f0
            f0.filter { it > 0 }.map { 12 * ln(it / p.medianF0) / ln(2.0) }.toDoubleArray()
        }
    }

    private fun sd(v: DoubleArray): Double {
        val m = if (v.isEmpty()) 0.0 else v.average()
        var s = 0.0
        for (x in v) {
            s += (x - m) * (x - m)
        }
        return sqrt(s / maxOf(1, v.size))
    }

    private fun playfulVsNeutral() {
        val text = "I really like chocolate cake. We went to the beautiful park yesterday afternoon."
        val neutral = pitchTracks(text, VoiceParams.defaults().style(StyleProfile.NEUTRAL)).sumOf { sd(it) }
        val playful = pitchTracks(text, VoiceParams.defaults()).sumOf { sd(it) }
        check(playful > neutral * 1.3, "playful spread $playful vs neutral $neutral")
    }

    private fun pitchCap() {
        val extreme = StyleProfile.PLAYFUL.with("accentHeight", 25.0).with("questionRise", 30.0)
            .with("vibratoDepth", 3.0).with("dipDepth", 20.0)
        val vp = VoiceParams.defaults().style(extreme).range(2.0)
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (t in pitchTracks("Is this *amazing*? Wow!! Sing along~ ♪", vp)) {
            for (x in t) {
                lo = minOf(lo, x)
                hi = maxOf(hi, x)
            }
        }
        check(hi <= extreme["capHigh"] + 1e-6 && lo >= extreme["capLow"] - 1e-6, "pitch $lo..$hi escapes the cap")
        check(hi > 7, "cap not approached: $hi")
    }

    private fun endings() {
        for (s in arrayOf(StyleProfile.PLAYFUL, StyleProfile.NEUTRAL)) {
            val q = pitchTracks("Do you want to come with me?", VoiceParams.defaults().style(s))[0]
            val st = pitchTracks("I want to come with you.", VoiceParams.defaults().style(s))[0]
            check(tailMean(q) > q.average() + 2, "question does not rise")
            check(tailMean(st) < st.average() - 1, "statement does not fall")
        }
    }

    private fun rhythmGrid() {
        val text = "Twinkle twinkle little star, how I wonder what you are. Street lights! " +
            "We need apples and a little bit of sugar."
        val fe = EnglishFrontend(dict)
        val phrases = fe.analyze(text)
        val strict = VoiceParams.defaults().style(StyleProfile.PLAYFUL.with("rhythm", 1.0))
        val rnd = JavaRandom(ProsodyModel.SEED)
        val plans = ArrayList<ProsodyModel.Plan>()
        for (ph in phrases) {
            val plan = ProsodyModel().plan(ph, strict, rnd)
            plans.add(plan)
            val heads = ProsodyModel.beatHeads(ph.morae)
            var t = 0.0
            for (i in ph.morae.indices) {
                if (i > 0) {
                    t += plan.vowel[i - 1] + plan.consonant[i]
                }
                if (heads[i]) {
                    val slot = (t - plan.firstBeat) / plan.beat
                    check(abs(slot - slot.roundToLong()) < 1e-6, "syllable off the grid in $ph: $slot")
                }
            }
            check(!heads[0] == (plan.firstBeat > 0), "pickup handling in $ph")
        }
        // phrases start on half-bar boundaries in the actual audio
        val clips = ArrayList<AudioClip>()
        val tts = Synthesizer.builder(pack).build()
        tts.speak(phrases, strict) { clips.add(it) }
        var start = 0.0
        val first = Renderer.firstBeat(plans[0])
        for (i in clips.indices) {
            val halfBars = (start + Renderer.firstBeat(plans[i]) - first) / (plans[i].halfBar * plans[i].beat)
            check(abs(halfBars - halfBars.roundToLong()) < 0.01, "phrase $i starts off the beat: $halfBars")
            start += clips[i].durationSeconds
        }
        // rhythm 0 leaves natural timing alone; ♪ is always rhythmic
        val ph = phrases[0]
        val natural = ProsodyModel().plan(ph, VoiceParams.defaults())
        val zero = ProsodyModel().plan(ph, VoiceParams.defaults().style(StyleProfile.PLAYFUL.with("rhythm", 0.0)))
        check(natural.vowel.contentEquals(zero.vowel) && natural.rhythm == 0.0, "rhythm 0 changes timing")
        val sung = fe.analyze("let us sing ♪")[0]
        eq(1.0, ProsodyModel().plan(sung, VoiceParams.defaults()).rhythm)
    }

    /** Mean of the last 50 ms of voicing. */
    private fun tailMean(v: DoubleArray): Double = v.copyOfRange(maxOf(0, v.size - 10), v.size).average()

    // ---------------------------------------------------------------- Android compatibility

    private val FORBIDDEN_PACKAGES = arrayOf(
        "java/awt/", "javax/sound/", "javax/swing/", "java/nio/file/", "java/lang/invoke/StringConcatFactory",
    )

    /** Methods missing on older Android API levels (owner.name). */
    private val FORBIDDEN_METHODS = setOf(
        "java/util/List.of", "java/util/List.copyOf", "java/util/Set.of", "java/util/Set.copyOf",
        "java/util/Map.of", "java/util/Map.ofEntries", "java/util/Map.entry", "java/util/Map.copyOf",
        "java/lang/String.isBlank", "java/lang/String.strip", "java/lang/String.repeat",
        "java/lang/String.lines", "java/util/stream/Stream.toList", "java/io/InputStream.readAllBytes",
        "java/io/InputStream.transferTo", "java/util/Optional.isEmpty",
    )

    private fun androidSafety() {
        val dirs = System.getProperty("nanatts.classesDir", "core/build/classes/kotlin/jvm/main")
        val problems = ArrayList<String>()
        var classes = 0
        for (d in dirs.split(File.pathSeparator)) {
            File(d).walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach {
                classes++
                scanClass(it, problems)
            }
        }
        check(classes > 20, "no classes found in $dirs")
        check(problems.isEmpty(), "Android-incompatible references: $problems")
    }

    /** Minimal class-file constant pool parser: checks the bytecode version and referenced APIs. */
    private fun scanClass(f: File, problems: MutableList<String>) {
        DataInputStream(f.inputStream()).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt(), "bad class file $f")
            input.readUnsignedShort()
            val major = input.readUnsignedShort()
            if (major != 61) {
                problems.add("${f.name} has class version $major (want 61 = Java 17)")
            }
            val count = input.readUnsignedShort()
            val utf = arrayOfNulls<String>(count)
            val refs = arrayOfNulls<IntArray>(count)
            val classNameIdx = IntArray(count)
            var i = 1
            while (i < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf[i] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> {
                        input.readLong()
                        i++
                    }
                    7 -> classNameIdx[i] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 10, 11, 12 -> refs[i] = intArrayOf(tag, input.readUnsignedShort(), input.readUnsignedShort())
                    15 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                    }
                    17, 18 -> {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                    }
                    else -> throw IOException("unknown constant tag $tag in $f")
                }
                i++
            }
            for (k in 1 until count) {
                if (classNameIdx[k] != 0) {
                    val name = utf[classNameIdx[k]]!!
                    for (p in FORBIDDEN_PACKAGES) {
                        if (name.startsWith(p)) {
                            problems.add("${f.name} -> $name")
                        }
                    }
                }
                val r = refs[k]
                if (r != null && r[0] != 12) {
                    val owner = utf[classNameIdx[r[1]]]
                    val member = utf[refs[r[2]]!![1]]
                    if ("$owner.$member" in FORBIDDEN_METHODS) {
                        problems.add("${f.name} -> $owner.$member")
                    }
                }
            }
        }
    }
}
