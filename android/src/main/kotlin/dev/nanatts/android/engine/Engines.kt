package dev.nanatts.android.engine

import android.content.Context
import dev.nanatts.io.ResourceSource
import dev.nanatts.io.streams

/** Single place to choose the engine implementation. */
object Engines {
    @Volatile private var instance: SpeechEngine? = null

    fun get(context: Context): SpeechEngine =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): SpeechEngine = NanaEngine(ResourceSource.streams(context.assets::open))
}
