package dev.nanatts.io

/**
 * Where binary resources (voice packs, dictionaries) come from. On the JVM, see the `ResourceSource.classpath`,
 * `directory` and `streams` helpers; on Android, wrap `AssetManager::open` with `ResourceSource.streams`.
 */
fun interface ResourceSource {

    /** The complete contents of the named resource. Throws if it does not exist. */
    fun read(name: String): ByteArray

    companion object
}
