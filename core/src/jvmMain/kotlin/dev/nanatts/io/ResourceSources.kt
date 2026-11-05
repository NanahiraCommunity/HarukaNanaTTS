@file:JvmName("ResourceSources")

package dev.nanatts.io

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Resources resolved relative to the root of the given class loader. */
fun ResourceSource.Companion.classpath(
    loader: ClassLoader = ResourceSource::class.java.classLoader,
): ResourceSource = ResourceSource { name ->
    val stream = loader.getResourceAsStream(name) ?: throw FileNotFoundException("classpath resource not found: $name")
    stream.use { it.readBytes() }
}

/** Resources resolved relative to a directory on the file system. */
fun ResourceSource.Companion.directory(dir: File): ResourceSource = ResourceSource { name -> File(dir, name).readBytes() }

/** Resources read from streams, e.g. `ResourceSource.streams(context.assets::open)` on Android. */
fun ResourceSource.Companion.streams(open: (String) -> InputStream): ResourceSource =
    ResourceSource { name -> open(name).use { it.readBytes() } }
