package dev.nanatts.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import dev.nanatts.android.engine.Engines

/** Answers android.speech.tts.engine.CHECK_TTS_DATA: all bundled voices are installed. */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val available = Engines.get(this).voices.map { Locales.iso3Key(it.locale) }.distinct()
        setResult(
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            Intent()
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(available))
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf()),
        )
        finish()
    }
}
