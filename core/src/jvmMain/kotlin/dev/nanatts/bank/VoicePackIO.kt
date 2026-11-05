@file:JvmName("VoicePackIO")

package dev.nanatts.bank

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/** Reads a `.vpk` stream. */
fun VoicePack.Companion.read(input: InputStream): VoicePack = read(input.readBytes())

/** Writes the pack in the `.vpk` format (gzip-compressed; read with [VoicePack.read]). */
fun VoicePack.write(raw: OutputStream) {
    val gz = GZIPOutputStream(raw, 1 shl 16)
    val out = DataOutputStream(BufferedOutputStream(gz, 1 shl 16))
    out.writeInt(VoicePack.MAGIC)
    out.writeInt(VoicePack.VERSION)
    out.writeInt(metadata.size)
    for ((k, v) in metadata) {
        out.writeUTF(k)
        out.writeUTF(v)
    }
    out.writeDouble(framePeriod)
    out.writeInt(grid.size)
    out.writeDouble(grid.maxHz)
    val bands = apBands
    out.writeInt(bands.size)
    for (b in bands) {
        out.writeDouble(b)
    }
    out.writeDouble(medianF0)
    out.writeInt(segments.size)
    for (s in segments) {
        out.writeUTF(s.file)
        out.writeDouble(s.startSec)
        out.writeInt(s.frames)
        for (c in s.f0q) {
            out.writeChar(c.code)
        }
        for (t in s.envTop) {
            out.writeShort(t.toInt())
        }
        out.write(s.env)
        out.write(s.ap)
    }
    val all = units.values.flatten().sortedWith(compareBy<VoiceUnit> { it.segment }.thenBy { it.start })
    out.writeInt(all.size)
    for (u in all) {
        out.writeUTF(u.alias)
        out.writeChar(u.prev.code)
        out.writeUTF(u.kana)
        out.writeInt(u.segment)
        out.writeDouble(u.start)
        out.writeDouble(u.overlap)
        out.writeDouble(u.consonantStart)
        out.writeDouble(u.onset)
        out.writeDouble(u.fixedEnd)
        out.writeDouble(u.end)
    }
    out.flush()
    gz.finish()
}
