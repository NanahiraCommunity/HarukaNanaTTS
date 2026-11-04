package dev.nanatts.android.engine

import android.content.Context

/** Single place to choose the engine implementation. */
object Engines {
    @Volatile private var instance: SpeechEngine? = null

    fun get(context: Context): SpeechEngine =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): SpeechEngine = NanaEngine(context.assets::open)
}
