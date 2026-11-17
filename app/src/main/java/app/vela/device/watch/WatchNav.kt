package app.vela.device.watch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Messages for the watch app `vela-nav` (protocol `n1`, documented in the app's `src/proto.rs`).
 * Each is at most 128 bytes, the app channel's limit. Positions are metres east and north of an
 * origin picked per drive, never latitude and longitude: anyone in Bluetooth range can read the
 * channel.
 */
object WatchNav {
    const val F_ACTIVE = 1
    const val F_ARRIVED = 2
    const val F_IMPERIAL = 8
    const val F_FIX = 16
    const val F_CLOCK24 = 32
    const val F_SNAPSHOT = 64
    const val CHUNK = 30
    const val TEXT_MAX = 100
    val ASK = byteArrayOf('n'.code.toByte(), '1'.code.toByte(), 'Q'.code.toByte())

    data class State(
        val flags: Int,
        val maneuver: Int,
        val distM: Int,
        val after: Int = 0,
        val afterM: Int = 0,
        val remainingM: Int,
        val remainingS: Int,
        val limit: Int = 0,
        val x: Int,
        val y: Int,
        /** Heading in 1/65536 turns clockwise from north. */
        val course: Int,
        /** Index of the first route point still ahead. */
        val progress: Int,
    )

    private fun msg(kind: Char, seq: Int, body: ByteArray): ByteArray =
        byteArrayOf('n'.code.toByte(), '1'.code.toByte(), kind.code.toByte(), seq.toByte()) + body

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun u16(v: Int) = v.coerceIn(0, 0xFFFF).toShort()

    fun state(seq: Int, s: State): ByteArray = msg(
        'S', seq,
        le(24).put(s.flags.toByte()).put(s.maneuver.toByte()).putShort(u16(s.distM))
            .put(s.after.toByte()).putShort(u16(s.afterM)).putShort(u16(s.remainingM / 10))
            .putShort(u16(s.remainingS)).put(s.limit.coerceIn(0, 255).toByte())
            .putInt(s.x).putInt(s.y).putShort(s.course.toShort()).putShort(u16(s.progress)).array(),
    )

    /** Cut on a character boundary to [TEXT_MAX] bytes. */
    fun text(seq: Int, t: String): ByteArray {
        var b = t.toByteArray(Charsets.UTF_8)
        if (b.size > TEXT_MAX) {
            var n = TEXT_MAX
            while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
            b = b.copyOf(n)
        }
        return msg('T', seq, b)
    }

    /** Begin, chunks of [CHUNK] points and end for a line in metres; the seq of each follows [seq]. */
    fun route(seq: Int, id: Int, pts: List<IntArray>): List<ByteArray> {
        val max = pts.maxOfOrNull { maxOf(kotlin.math.abs(it[0]), kotlin.math.abs(it[1])) } ?: 0
        val scale = max / Short.MAX_VALUE + 1
        require(scale <= 255 && pts.size <= 512)
        var s = seq
        val out = ArrayList<ByteArray>()
        out += msg('B', s++, le(4).put(id.toByte()).putShort(pts.size.toShort()).put(scale.toByte()).array())
        pts.chunked(CHUNK).forEachIndexed { c, chunk ->
            val b = le(4 + 4 * chunk.size).put(id.toByte()).putShort((c * CHUNK).toShort()).put(chunk.size.toByte())
            chunk.forEach { b.putShort((it[0] / scale).toShort()).putShort((it[1] / scale).toShort()) }
            out += msg('R', s++, b.array())
        }
        out += msg('E', s, le(3).put(id.toByte()).putShort(pts.size.toShort()).array())
        return out
    }
}
