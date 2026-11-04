package dev.nanatts.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Answers android.speech.tts.engine.GET_SAMPLE_TEXT (used by the system "Listen to an example" button). */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The framework passes ISO-3 codes in "language" / "country" / "variant".
        val text = when (intent.getStringExtra("language")) {
            "jpn" -> "こんにちは、はるかななです。"
            else -> "This is an example of speech synthesis."
        }
        setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text))
        finish()
    }
}
