@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmInterop::class)

package dev.nanatts.wasi

import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.MemoryAllocator
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * The few WASI (preview 1) calls the library and its command line need: arguments, standard streams, files in
 * preopened directories and the exit code. The Kotlin/Wasm standard library has no file API for WASI.
 */
object Wasi {

    const val STDIN = 0
    const val STDOUT = 1
    const val STDERR = 2

    private const val CHUNK = 1 shl 16
    private const val ERRNO_BADF = 8
    private const val RIGHT_FD_READ = 1L shl 1
    private const val RIGHT_FD_WRITE = 1L shl 6
    private const val OFLAG_CREAT = 1
    private const val OFLAG_TRUNC = 8

    /** Command-line arguments, without the program name. */
    fun args(): List<String> = withScopedMemoryAllocator { a ->
        val sizes = a.allocate(8)
        check("args_sizes_get", argsSizesGet(sizes.address.toInt(), (sizes + 4).address.toInt()))
        val argc = sizes.loadInt()
        val bufSize = (sizes + 4).loadInt()
        val argv = a.allocate(maxOf(4, argc * 4))
        val buf = a.allocate(maxOf(1, bufSize))
        check("args_get", argsGet(argv.address.toInt(), buf.address.toInt()))
        (1 until argc).map { i ->
            val p = Pointer((argv + 4 * i).loadInt().toUInt())
            var len = 0
            while ((p + len).loadByte().toInt() != 0) {
                len++
            }
            load(p, len).decodeToString()
        }
    }

    /** Reads a stream until its end. */
    fun readAll(fd: Int): ByteArray = withScopedMemoryAllocator { a ->
        var out = ByteArray(CHUNK)
        var len = 0
        val buf = a.allocate(CHUNK)
        val iov = a.allocate(8)
        val count = a.allocate(4)
        while (true) {
            iov.storeInt(buf.address.toInt())
            (iov + 4).storeInt(CHUNK)
            check("fd_read", fdRead(fd, iov.address.toInt(), 1, count.address.toInt()))
            val n = count.loadInt()
            if (n == 0) {
                break
            }
            if (len + n > out.size) {
                out = out.copyOf(maxOf(out.size * 2, len + n))
            }
            for (i in 0 until n) {
                out[len + i] = (buf + i).loadByte()
            }
            len += n
        }
        out.copyOf(len)
    }

    fun write(fd: Int, data: ByteArray) {
        withScopedMemoryAllocator { a ->
            val buf = a.allocate(CHUNK)
            val iov = a.allocate(8)
            val count = a.allocate(4)
            var pos = 0
            while (pos < data.size) {
                val n = minOf(CHUNK, data.size - pos)
                for (i in 0 until n) {
                    (buf + i).storeByte(data[pos + i])
                }
                var done = 0
                while (done < n) {
                    iov.storeInt(buf.address.toInt() + done)
                    (iov + 4).storeInt(n - done)
                    check("fd_write", fdWrite(fd, iov.address.toInt(), 1, count.address.toInt()))
                    done += count.loadInt()
                }
                pos += n
            }
        }
    }

    fun readFile(path: String): ByteArray {
        val fd = open(path, 0, RIGHT_FD_READ)
        try {
            return readAll(fd)
        } finally {
            fdClose(fd)
        }
    }

    fun writeFile(path: String, data: ByteArray) {
        val fd = open(path, OFLAG_CREAT or OFLAG_TRUNC, RIGHT_FD_WRITE)
        try {
            write(fd, data)
        } finally {
            fdClose(fd)
        }
    }

    fun exit(code: Int): Nothing {
        procExit(code)
        throw IllegalStateException("proc_exit returned")
    }

    /** Preopened directories: guest path to file descriptor. */
    private val preopens: List<Pair<String, Int>> by lazy {
        val out = ArrayList<Pair<String, Int>>()
        withScopedMemoryAllocator { a ->
            val prestat = a.allocate(8)
            var fd = 3
            while (true) {
                val err = fdPrestatGet(fd, prestat.address.toInt())
                if (err == ERRNO_BADF) {
                    break
                }
                check("fd_prestat_get", err)
                if (prestat.loadByte().toInt() == 0) { // a directory
                    val len = (prestat + 4).loadInt()
                    val name = a.allocate(maxOf(1, len))
                    check("fd_prestat_dir_name", fdPrestatDirName(fd, name.address.toInt(), len))
                    out.add(load(name, len).decodeToString().trimEnd('\u0000') to fd)
                }
                fd++
            }
        }
        out
    }

    /** Opens [path] in the preopened directory that contains it (relative paths: the `.` or first one). */
    private fun open(path: String, oflags: Int, rights: Long): Int {
        val (dirFd, relative) = resolve(path)
        return withScopedMemoryAllocator { a ->
            val bytes = relative.encodeToByteArray()
            val p = store(a, bytes)
            val fd = a.allocate(4)
            val err = pathOpen(dirFd, 0, p.address.toInt(), bytes.size, oflags, rights, 0L, 0, fd.address.toInt())
            if (err != 0) {
                throw IllegalStateException("cannot open $path (WASI errno $err)")
            }
            fd.loadInt()
        }
    }

    private fun resolve(path: String): Pair<Int, String> {
        val dirs = preopens
        if (dirs.isEmpty()) {
            throw IllegalStateException("cannot open $path: no directory is preopened (wasmtime --dir)")
        }
        if (!path.startsWith("/")) {
            val dot = dirs.firstOrNull { it.first == "." || it.first == "./" } ?: dirs[0]
            return dot.second to path
        }
        val best = dirs.filter { path == it.first || path.startsWith(it.first.trimEnd('/') + "/") }
            .maxByOrNull { it.first.length }
            ?: throw IllegalStateException("cannot open $path: not inside a preopened directory")
        return best.second to path.substring(best.first.trimEnd('/').length).trimStart('/').ifEmpty { "." }
    }

    private fun check(call: String, errno: Int) {
        if (errno != 0) {
            throw IllegalStateException("$call failed (WASI errno $errno)")
        }
    }

    private fun load(p: Pointer, len: Int): ByteArray = ByteArray(len) { (p + it).loadByte() }

    private fun store(a: MemoryAllocator, bytes: ByteArray): Pointer {
        val p = a.allocate(maxOf(1, bytes.size))
        for (i in bytes.indices) {
            (p + i).storeByte(bytes[i])
        }
        return p
    }
}

@WasmImport("wasi_snapshot_preview1", "args_sizes_get")
private external fun argsSizesGet(argc: Int, bufSize: Int): Int

@WasmImport("wasi_snapshot_preview1", "args_get")
private external fun argsGet(argv: Int, buf: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun fdRead(fd: Int, iovs: Int, iovsLen: Int, nread: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_write")
private external fun fdWrite(fd: Int, iovs: Int, iovsLen: Int, nwritten: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_close")
private external fun fdClose(fd: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_prestat_get")
private external fun fdPrestatGet(fd: Int, prestat: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_prestat_dir_name")
private external fun fdPrestatDirName(fd: Int, path: Int, len: Int): Int

@WasmImport("wasi_snapshot_preview1", "path_open")
private external fun pathOpen(
    dirFd: Int, dirFlags: Int, path: Int, pathLen: Int, oflags: Int, rightsBase: Long, rightsInheriting: Long,
    fdFlags: Int, fd: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "proc_exit")
private external fun procExit(code: Int)
