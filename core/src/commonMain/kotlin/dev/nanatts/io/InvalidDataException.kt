package dev.nanatts.io

/** Malformed data: a corrupt voice pack, wav or gzip stream. */
class InvalidDataException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
