package app.vela.device

import app.vela.core.data.naming.NamedLine
import app.vela.core.model.LatLng
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The streets around the phone, as outlines for the device's minimal display: without them its
 * route line floats in empty black. They come from the map's own vector tiles
 * ([app.vela.core.data.naming.RoadNameTiles]), are cut to a box around the position, thinned,
 * and packed as `NAV_ROADS` chunks (the firmware's `ble_service.h`).
 */
object DeviceRoads {
    /** Half the side of the box of streets sent, in meters. */
    const val RADIUS_M = 700.0
    /** A new set goes out once the phone is this far from where the last one was centered. */
    const val REFRESH_M = 250.0
    /** The device holds 1600 points; the nearest streets win when there are more. */
    const val MAX_POINTS = 1400

    private const val ROADS: Byte = 0x06
    private const val F_START = 0x01
    private const val F_END = 0x02
    /** Points per record. The device takes 60 (`NAV_ROADS_LINE_MAX`); 58 is what fits beside
     *  the origin in the first chunk. A longer street is sent as pieces that share an end point. */
    private const val LINE_MAX = 58
    /** `NAV_ROADS_CHUNK_MAX`. */
    private const val CHUNK_MAX = 252
    private const val THIN_M = 4.0

    // OpenMapTiles road classes. Paths, tracks, service roads and rails are left out: they
    // are clutter at this size.
    private val MAJOR = setOf("motorway", "trunk", "primary", "secondary")
    private val MINOR = setOf("tertiary", "minor")

    /** A path through the box around [center], for asking the tile reader for every tile the
     *  box touches (it returns the tiles a line passes through). */
    fun probe(center: LatLng): List<LatLng> {
        val dLat = RADIUS_M / 111_320.0
        val dLng = RADIUS_M / (111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
        val nw = LatLng(center.lat + dLat, center.lng - dLng)
        val ne = LatLng(center.lat + dLat, center.lng + dLng)
        val se = LatLng(center.lat - dLat, center.lng + dLng)
        val sw = LatLng(center.lat - dLat, center.lng - dLng)
        return listOf(center, nw, ne, se, sw, nw)
    }

    /** The chunks for the streets of [lines] inside the box around [center]. With none, one
     *  chunk that clears the device's set. */
    fun chunks(center: LatLng, lines: List<NamedLine>): List<ByteArray> {
        val oLat = (center.lat * 1e7).roundToLong()
        val oLng = (center.lng * 1e7).roundToLong()
        val dLat = RADIUS_M / 111_320.0
        val dLng = RADIUS_M / (111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
        fun inside(p: LatLng) = abs(p.lat - center.lat) <= dLat && abs(p.lng - center.lng) <= dLng

        class Piece(val major: Boolean, val pts: List<IntArray>, val nearest: Double)
        val pieces = ArrayList<Piece>()
        for (line in lines) {
            val major = line.cls in MAJOR
            if (!major && line.cls !in MINOR) continue
            val p = line.points
            // Runs of points in the box, each with its neighbor outside so the street
            // reaches the edge of the box and does not stop short of it.
            var i = 0
            while (i < p.size) {
                val keep = { k: Int -> inside(p[k]) || (k > 0 && inside(p[k - 1])) || (k + 1 < p.size && inside(p[k + 1])) }
                if (!keep(i)) { i++; continue }
                var j = i
                while (j + 1 < p.size && keep(j + 1)) j++
                val run = RouteSimplify.thin(p.subList(i, j + 1), THIN_M)
                val q = ArrayList<IntArray>(run.size)
                var nearest = Double.MAX_VALUE
                for (pt in run) {
                    val a = (((pt.lat * 1e7).roundToLong() - oLat) / 100.0).roundToInt().coerceIn(-32768, 32767)
                    val b = (((pt.lng * 1e7).roundToLong() - oLng) / 100.0).roundToInt().coerceIn(-32768, 32767)
                    if (q.isEmpty() || q.last()[0] != a || q.last()[1] != b) q += intArrayOf(a, b)
                    // Offsets are 1e-5 degrees; close enough to compare distances with.
                    nearest = minOf(nearest, abs(a.toDouble()) + abs(b.toDouble()))
                }
                if (q.size >= 2) pieces += Piece(major, q, nearest)
                i = j + 1
            }
        }

        // Nearest streets first, up to what the device holds, each cut to the record size.
        val records = ArrayList<ByteArray>()
        var points = 0
        for (piece in pieces.sortedBy { it.nearest }) {
            if (points + piece.pts.size > MAX_POINTS) continue
            points += piece.pts.size
            var from = 0
            while (from < piece.pts.size - 1) {
                val to = minOf(from + LINE_MAX, piece.pts.size)
                val b = ByteBuffer.allocate(2 + (to - from) * 4).order(ByteOrder.LITTLE_ENDIAN)
                b.put((to - from).toByte()).put(if (piece.major) 1 else 0)
                for (k in from until to) b.putShort(piece.pts[k][0].toShort()).putShort(piece.pts[k][1].toShort())
                records += b.array()
                from = to - 1 // the next piece starts on this one's last point
            }
        }

        val chunks = ArrayList<ByteArrayBuilder>()
        fun open(): ByteArrayBuilder {
            val c = ByteArrayBuilder(chunks.size)
            if (chunks.isEmpty()) c.origin(oLat.toInt(), oLng.toInt())
            chunks += c
            return c
        }
        var cur = open()
        for (r in records) {
            if (cur.size + r.size > CHUNK_MAX) cur = open()
            cur.add(r)
        }
        return chunks.mapIndexed { i, c -> c.build(first = i == 0, last = i == chunks.lastIndex) }
    }

    private class ByteArrayBuilder(private val seq: Int) {
        private val body = java.io.ByteArrayOutputStream()
        val size get() = 4 + body.size()
        fun origin(latE7: Int, lngE7: Int) {
            body.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(latE7).putInt(lngE7).array())
        }
        fun add(record: ByteArray) = body.write(record)
        fun build(first: Boolean, last: Boolean): ByteArray {
            val flags = (if (first) F_START else 0) or (if (last) F_END else 0)
            return byteArrayOf(ROADS, flags.toByte(), seq.toByte(), 0) + body.toByteArray()
        }
    }
}
