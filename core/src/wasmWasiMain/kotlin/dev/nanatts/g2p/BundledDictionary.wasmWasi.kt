package dev.nanatts.g2p

import dev.nanatts.wasi.Wasi

internal actual fun bundledDictionary(): ByteArray = Wasi.readFile("cmudict.tsv.gz")
