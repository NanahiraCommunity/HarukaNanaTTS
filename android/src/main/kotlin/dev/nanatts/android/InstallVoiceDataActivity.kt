package dev.nanatts.android

import android.app.Activity
import android.os.Bundle

/** Answers android.speech.tts.engine.INSTALL_TTS_DATA. Voices are bundled, so there is nothing to install. */
class InstallVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
