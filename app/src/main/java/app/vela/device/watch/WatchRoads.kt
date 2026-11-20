package app.vela.device.watch

import app.vela.core.data.naming.NamedLine
import app.vela.core.model.LatLng
import app.vela.device.RouteSimplify
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos

/**
 * The streets around the car for the watch's Vela Nav, drawn under the route and fading out away
 * from it. They come from the map's own vector tiles, as [app.vela.device.DeviceRoads] takes them
 * for the ESP32 display: the box around the car, minor and major roads only, thinned, nearest
 * first. Pure, so it is unit-tested.
 *
 * On the wire they are metres in the route's frame ([WatchDrive]), never latitude and longitude:
 * the set's origin is the car's position in that frame, and each point is metres from it.
 */
object WatchRoads {
    /** Half the side of the box of streets, in metres: the screen's radius at the default zoom. */
    const val RADIUS_M = 800.0
    /** A new set goes out once the car is this far from where the last one was centred. */
    const val REFRESH_M = 250.0
    /** The watch holds 1200 points and 240 lines a set (`ROADS_PTS`, `ROADS_LINES` in `proto.rs`). */
    const val MAX_POINTS = 1150
    const val MAX_LINES = 230
    /** Points a line record holds (`ROAD_LINE_MAX`); a longer street goes as pieces sharing an end. */
    const val LINE_MAX = 30
    private const val BODY_MAX = 124 // a message is 128 B: "n1", kind, seq
    private const val THIN_M = 5.0

    // OpenMapTiles classes. Paths, tracks, service roads and rails are clutter at this size.
    private val MAJOR = setOf("motorway", "trunk", "primary", "secondary")
    private val MINOR = setOf("tertiary", "minor")

    class Line(val major: Boolean, val pts: List<IntArray>)

    /** A set: its origin in the route's frame, and its lines in metres from that origin. */
    class Set(val origin: IntArray, val lines: List<Line>) {
        val points get() = lines.sumOf { it.pts.size }
    }

    /** A path around the box, for asking the tile reader for every tile the box touches. */
    fun probe(center: LatLng): List<LatLng> {
        val dLat = RADIUS_M / 111_320.0
        val dLng = RADIUS_M / (111_320.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
        val nw = LatLng(center.lat + dLat, center.lng - dLng)
        val ne = LatLng(center.lat + dLat, center.lng + dLng)
        val se = LatLng(center.lat - dLat, center.lng + dLng)
        val sw = LatLng(center.lat - dLat, center.lng - dLng)
        return listOf(center, nw, ne, se, sw, nw)
    }

    /** The streets of [lines] in the box around [here], nearest first, cut to what the watch holds. */
    fun set(routeOrigin: LatLng, here: LatLng, lines: List<NamedLine>): Set {
        val origin = WatchDrive.metres(routeOrigin, here)
        val dLat = RADIUS_M / 111_320.0
        val dLng = RADIUS_M / (111_320.0 * cos(Math.toRadians(here.lat)).coerceAtLeast(0.2))
        fun inside(p: LatLng) = abs(p.lat - here.lat) <= dLat && abs(p.lng - here.lng) <= dLng

        class Piece(val major: Boolean, val pts: List<IntArray>, val nearest: Int)
        val pieces = ArrayList<Piece>()
        for (line in lines) {
            val major = line.cls in MAJOR
            if (!major && line.cls !in MINOR) continue
            val p = line.points
            // runs of points in the box, each with its neighbour outside, so a street reaches the edge
            var i = 0
            while (i < p.size) {
                val keep = { k: Int -> inside(p[k]) || (k > 0 && inside(p[k - 1])) || (k + 1 < p.size && inside(p[k + 1])) }
                if (!keep(i)) { i++; continue }
                var j = i
                while (j + 1 < p.size && keep(j + 1)) j++
                val q = ArrayList<IntArray>()
                var nearest = Int.MAX_VALUE
                for (pt in RouteSimplify.thin(p.subList(i, j + 1), THIN_M)) {
                    val m = WatchDrive.metres(routeOrigin, pt)
                    val v = intArrayOf((m[0] - origin[0]).coerceIn(-32768, 32767), (m[1] - origin[1]).coerceIn(-32768, 32767))
                    if (q.isEmpty() || !q.last().contentEquals(v)) q += v
                    nearest = minOf(nearest, abs(v[0]) + abs(v[1]))
                }
                if (q.size >= 2) pieces += Piece(major, q, nearest)
                i = j + 1
            }
        }
        val out = ArrayList<Line>()
        var points = 0
        for (piece in pieces.sortedBy { it.nearest }) {
            var from = 0
            val cut = ArrayList<Line>()
            while (from < piece.pts.size - 1) {
                val to = minOf(from + LINE_MAX, piece.pts.size)
                cut += Line(piece.major, piece.pts.subList(from, to))
                from = to - 1 // the next piece starts on this one's last point
            }
            val n = cut.sumOf { it.pts.size }
            if (points + n > MAX_POINTS || out.size + cut.size > MAX_LINES) continue
            points += n
            out += cut
        }
        return Set(origin, out)
    }

    /** The set as (kind, body) parts: `G` begin, `H` messages of whole lines, `J` end. */
    fun parts(id: Int, set: Set): List<Pair<Char, ByteArray>> {
        fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Pair<Char, ByteArray>>()
        out += 'G' to le(9).put(id.toByte()).putInt(set.origin[0]).putInt(set.origin[1]).array()
        var body = java.io.ByteArrayOutputStream().apply { write(id) }
        for (line in set.lines) {
            val rec = le(2 + 4 * line.pts.size).put(line.pts.size.toByte()).put(if (line.major) 1 else 0)
            line.pts.forEach { rec.putShort(it[0].toShort()).putShort(it[1].toShort()) }
            if (body.size() + rec.capacity() > BODY_MAX) {
                out += 'H' to body.toByteArray()
                body = java.io.ByteArrayOutputStream().apply { write(id) }
            }
            body.write(rec.array())
        }
        if (body.size() > 1) out += 'H' to body.toByteArray()
        out += 'J' to le(5).put(id.toByte()).putShort(set.lines.size.toShort()).putShort(set.points.toShort()).array()
        return out
    }
}
