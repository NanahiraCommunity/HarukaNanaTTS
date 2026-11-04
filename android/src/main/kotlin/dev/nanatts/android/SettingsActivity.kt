package dev.nanatts.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.nanatts.android.engine.Engines

/** Engine settings screen linked from system TTS settings (see res/xml/tts_engine.xml). */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voices = Engines.get(this).voices.joinToString("\n") { "• ${it.name} (${it.locale.toLanguageTag()})" }
        setContentView(TextView(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            text = "Installed voices:\n\n$voices"
        })
    }
}
