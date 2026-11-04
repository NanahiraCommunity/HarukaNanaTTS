// Node.js loader for the nana-tts WebAssembly module (Java compiled with TeaVM's WasmGC backend).
//
//   import { createTts, toWav } from "./nana-tts.mjs";
//   const tts = await createTts();
//   const { samples, sampleRate } = tts.speak("Hello! I love ラーメン~");
//   fs.writeFileSync("out.wav", toWav(samples, sampleRate));
//
// Or as a command: node nana-tts.mjs [-l en|ja] [-s playful|neutral|FILE] [-p PITCH] [-r SPEED] [-o out.wav] "text"

import { readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { load } from "./nana-tts.wasm-runtime.js";

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Loads the module, the voice pack and the pronunciation dictionary (by default from this script's folder).
 * Speaking is synchronous once loaded.
 */
export async function createTts({
    wasm = join(here, "nana-tts.wasm"),
    voice = join(here, "nana.vpk"),
    dictionary = join(here, "cmudict.tsv.gz"),
} = {}) {
    const teavm = await load(wasm);
    const api = teavm.exports;
    api.loadVoice(new Int8Array(await readBytes(voice)));
    api.loadDictionary(new Int8Array(await readBytes(dictionary)));
    const sampleRate = api.sampleRate();
    return {
        sampleRate,
        /**
         * @param {string} text English (may contain hiragana/katakana words) or, with language "ja", kana.
         *   Cues: *emphasis*, word~ (glide), ♪ (half-sung), !! (excited).
         * @param {object} [options]
         * @param {"en"|"ja"} [options.language="en"]
         * @param {string} [options.style="playful"] "playful", "neutral", or "knob=value" lines on top of playful
         * @param {number} [options.pitch=1] [options.speed=1] [options.volume=1]
         * @returns {{samples: Float32Array, sampleRate: number}} mono samples in [-1, 1]
         */
        speak(text, { language = "en", style = "playful", pitch = 1, speed = 1, volume = 1 } = {}) {
            const samples = api.speak(String(text), language, style, pitch, speed, volume);
            return { samples, sampleRate };
        },
        /** The kana transcription that will be spoken (useful for checking pronunciations). */
        analyze(text, { language = "en" } = {}) {
            return api.analyze(String(text), language);
        },
    };
}

async function readBytes(source) {
    if (typeof source === "string") {
        const b = await readFile(source);
        return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
    }
    return source; // ArrayBuffer / typed array supplied by the caller
}

/** 16-bit PCM mono WAV file contents. */
export function toWav(samples, sampleRate) {
    const out = Buffer.alloc(44 + samples.length * 2);
    out.write("RIFF", 0, "ascii");
    out.writeUInt32LE(36 + samples.length * 2, 4);
    out.write("WAVEfmt ", 8, "ascii");
    out.writeUInt32LE(16, 16);
    out.writeUInt16LE(1, 20);
    out.writeUInt16LE(1, 22);
    out.writeUInt32LE(sampleRate, 24);
    out.writeUInt32LE(sampleRate * 2, 28);
    out.writeUInt16LE(2, 32);
    out.writeUInt16LE(16, 34);
    out.write("data", 36, "ascii");
    out.writeUInt32LE(samples.length * 2, 40);
    for (let i = 0; i < samples.length; i++) {
        const v = Math.max(-1, Math.min(1, samples[i]));
        out.writeInt16LE(Math.round(v * 32767), 44 + i * 2);
    }
    return out;
}

async function cli(args) {
    const opts = {};
    let out = "out.wav";
    let phonemes = false;
    const words = [];
    for (let i = 0; i < args.length; i++) {
        const a = args[i];
        if (a === "-l" || a === "--lang") opts.language = args[++i];
        else if (a === "-s" || a === "--style") opts.style = args[++i];
        else if (a === "-p" || a === "--pitch") opts.pitch = Number(args[++i]);
        else if (a === "-r" || a === "--speed") opts.speed = Number(args[++i]);
        else if (a === "-o" || a === "--out") out = args[++i];
        else if (a === "--phonemes") phonemes = true;
        else words.push(a);
    }
    const text = words.join(" ");
    if (!text) {
        console.error('usage: node nana-tts.mjs [-l en|ja] [-s playful|neutral|FILE] [-p PITCH] [-r SPEED] [-o out.wav] [--phonemes] "text"');
        process.exit(2);
    }
    if (opts.style && !["playful", "neutral"].includes(opts.style)) {
        opts.style = await readFile(opts.style, "utf8"); // a .properties file of style knobs
    }
    const tts = await createTts();
    if (phonemes) console.log(tts.analyze(text, opts));
    const t0 = performance.now();
    const { samples, sampleRate } = tts.speak(text, opts);
    const ms = performance.now() - t0;
    await writeFile(out, toWav(samples, sampleRate));
    console.log(`wrote ${out} (${(samples.length / sampleRate).toFixed(2)} s audio in ${ms.toFixed(0)} ms)`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    await cli(process.argv.slice(2));
}
