package dev.nanatts.bank

import dev.nanatts.io.ByteReader
import dev.nanatts.io.Gzip
import dev.nanatts.io.InvalidDataException
import dev.nanatts.io.ResourceSource

/**
 * A compiled voice: pre-analysed vocoder parameters for every unit of a voicebank. Produced offline by the
 * `bankc` tool from an UTAU voicebank, so devices never analyse audio themselves.
 */
class VoicePack(
    metadata: Map<String, String>,
    /** Analysis frame period in seconds. */
    val framePeriod: Double,
    val grid: MelGrid,
    apBands: DoubleArray,
    /** Median F0 (Hz) of all voiced frames: the singer's recorded pitch. */
    val medianF0: Double,
    segments: List<Segment>,
    unitList: List<VoiceUnit>,
) {

    /** Free-form metadata, e.g. `name`, `author`, `license`. */
    val metadata: Map<String, String> = LinkedHashMap(metadata)
    private val apBandsHz = apBands.copyOf()
    internal val segments: List<Segment> = ArrayList(segments)
    internal val units: Map<String, List<VoiceUnit>>

    init {
        val m = HashMap<String, MutableList<VoiceUnit>>()
        for (u in unitList) {
            m.getOrPut(u.alias) { ArrayList() }.add(u)
        }
        units = m
    }

    /** Aperiodicity band centre frequencies (Hz). */
    val apBands: DoubleArray
        get() = apBandsHz.copyOf()

    fun segment(i: Int): Segment = segments[i]

    val segmentCount: Int
        get() = segments.size

    /** All candidate units for an alias (empty if none). */
    fun units(alias: String): List<VoiceUnit> = units[alias] ?: emptyList()

    fun has(alias: String): Boolean = units.containsKey(alias)

    val unitCount: Int
        get() = units.values.sumOf { it.size }

    companion object {
        internal const val MAGIC = 0x4E56504B // "NVPK"
        internal const val VERSION = 1

        fun load(source: ResourceSource, name: String): VoicePack = read(source.read(name))

        /** Reads a pack from the contents of a `.vpk` file (gzip-compressed). */
        fun read(gzipped: ByteArray): VoicePack {
            val input = ByteReader(Gzip.decompress(gzipped))
            if (input.readInt() != MAGIC) {
                throw InvalidDataException("not a voice pack")
            }
            val version = input.readInt()
            if (version != VERSION) {
                throw InvalidDataException("unsupported voice pack version $version")
            }
            val metaCount = input.readInt()
            val meta = LinkedHashMap<String, String>()
            repeat(metaCount) {
                val key = input.readUTF()
                meta[key] = input.readUTF()
            }
            val framePeriod = input.readDouble()
            val grid = MelGrid(input.readInt(), input.readDouble())
            val bands = DoubleArray(input.readInt())
            for (i in bands.indices) {
                bands[i] = input.readDouble()
            }
            val medianF0 = input.readDouble()
            val segCount = input.readInt()
            val segs = ArrayList<Segment>(segCount)
            repeat(segCount) {
                val file = input.readUTF()
                val start = input.readDouble()
                val frames = input.readInt()
                val f0 = CharArray(frames) { input.readChar() }
                val top = ShortArray(frames) { input.readShort() }
                val env = ByteArray(frames * grid.size)
                input.readFully(env)
                val ap = ByteArray(frames * bands.size)
                input.readFully(ap)
                segs.add(Segment(file, start, frames, grid.size, bands.size, f0, top, env, ap))
            }
            val unitCount = input.readInt()
            val units = ArrayList<VoiceUnit>(unitCount)
            repeat(unitCount) {
                units.add(
                    VoiceUnit(
                        input.readUTF(), input.readChar(), input.readUTF(), input.readInt(), input.readDouble(),
                        input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(),
                        input.readDouble(),
                    ),
                )
            }
            return VoicePack(meta, framePeriod, grid, bands, medianF0, segs, units)
        }
    }
}
