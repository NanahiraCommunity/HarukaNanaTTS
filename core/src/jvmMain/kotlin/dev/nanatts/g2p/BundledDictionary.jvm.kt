package dev.nanatts.g2p

import dev.nanatts.io.ResourceSource
import dev.nanatts.io.classpath

internal actual fun bundledDictionary(): ByteArray =
    ResourceSource.classpath(EnglishFrontend::class.java.classLoader).read(CmuDict.RESOURCE)
