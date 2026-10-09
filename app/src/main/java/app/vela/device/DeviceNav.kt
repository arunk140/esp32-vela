package app.vela.device

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.TrafficSpan
import app.vela.core.nav.NavSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Maneuver codes on the wire. They mirror `NAV_*` in the device firmware's `ble_service.h` and
 * are bytes on the link: never renumber one side alone.
 */
object DeviceManeuver {
    const val NONE = 0
    const val CONTINUE = 1
    const val SLIGHT_LEFT = 2
    const val LEFT = 3
    const val SHARP_LEFT = 4
    const val SLIGHT_RIGHT = 5
    const val RIGHT = 6
    const val SHARP_RIGHT = 7
    const val KEEP_LEFT = 8
    const val KEEP_RIGHT = 9
    const val UTURN = 10
    const val ROUNDABOUT = 11
    const val OFF_ROUTE = 12
    const val ARRIVE = 13

    /** No `else`: a new [ManeuverType] has to be given a code here before the app compiles. */
    fun of(type: ManeuverType): Int = when (type) {
        ManeuverType.DEPART, ManeuverType.CONTINUE, ManeuverType.STRAIGHT,
        ManeuverType.MERGE, ManeuverType.UNKNOWN -> CONTINUE
        ManeuverType.SLIGHT_LEFT, ManeuverType.RAMP_LEFT -> SLIGHT_LEFT
        ManeuverType.TURN_LEFT -> LEFT
        ManeuverType.SHARP_LEFT -> SHARP_LEFT
        ManeuverType.SLIGHT_RIGHT, ManeuverType.RAMP_RIGHT -> SLIGHT_RIGHT
        ManeuverType.TURN_RIGHT -> RIGHT
        ManeuverType.SHARP_RIGHT -> SHARP_RIGHT
        ManeuverType.KEEP_LEFT, ManeuverType.FORK_LEFT -> KEEP_LEFT
        ManeuverType.KEEP_RIGHT, ManeuverType.FORK_RIGHT -> KEEP_RIGHT
        ManeuverType.UTURN -> UTURN
        ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT -> ROUNDABOUT
        ManeuverType.ARRIVE -> ARRIVE
    }
}

/** What the device shows for the drive: the turn banner, the street, and the destination pin. */
data class DeviceNavFrame(
    val active: Boolean = false,
    val maneuver: Int = DeviceManeuver.NONE,
    val distanceM: Int = 0,
    val street: String = "",
    val afterManeuver: Int = DeviceManeuver.NONE,
    val afterDistanceM: Int = 0,
    val remainingM: Int = 0,
    val remainingS: Int = 0,
    val dest: LatLng? = null,
) {
    companion object {
        val IDLE = DeviceNavFrame()

        /** The frame for a session state. A paused drive is sent as no drive at all: the
         *  device drops its pin and line whenever it is told the drive is not active. */
        fun of(s: NavSession.State): DeviceNavFrame {
            val route = s.route
            if (route == null || !(s.navigating || s.arrived)) return IDLE
            if (s.paused && !s.arrived) return IDLE
            val dest = route.polyline.lastOrNull()
            if (s.arrived) return DeviceNavFrame(active = true, maneuver = DeviceManeuver.ARRIVE, dest = dest)
            val steps = route.maneuvers
            val next = steps.getOrNull(s.nav.stepIndex)
            val after = steps.getOrNull(s.nav.stepIndex + 1)
            return DeviceNavFrame(
                active = true,
                maneuver = when {
                    s.nav.offRoute -> DeviceManeuver.OFF_ROUTE
                    next != null -> DeviceManeuver.of(next.type)
                    else -> DeviceManeuver.NONE
                },
                distanceM = s.nav.distanceToNextManeuver.toInt(),
                street = next?.road?.takeIf { it.isNotBlank() } ?: next?.ref.orEmpty(),
                afterManeuver = after?.let { DeviceManeuver.of(it.type) } ?: DeviceManeuver.NONE,
                // A maneuver's own distance is the travel from it to the one after.
                afterDistanceM = if (after != null) next?.distanceMeters?.toInt() ?: 0 else 0,
                remainingM = s.remainingDistance.toInt(),
                remainingS = s.remainingDuration.toInt(),
                dest = dest,
            )
        }
    }
}

/**
 * Packs navigation for the device's NAV characteristic. STATE goes out on every call, because the
 * device hides its banner when none arrives for six seconds. TEXT and DEST go out only when they
 * change. Nothing here logs: a street name and a destination are exactly what a log line must not
 * carry.
 */
class DeviceNavEncoder(private val out: Out) {

    interface Out {
        /** [tag] not null: only the newest queued write with that tag is kept. */
        fun navWrite(data: ByteArray, tag: String?)
        fun navReady(): Boolean
    }

    private var lastStreet: String? = null
    private var lastDest: Pair<Int, Int>? = null
    private var destSent = false
    private var lastLimit: Pair<Boolean, Int>? = null

    fun send(f: DeviceNavFrame) {
        if (!out.navReady()) return
        out.navWrite(state(f), "navst")
        val name = f.street.trim()
        if (name != lastStreet) {
            lastStreet = name
            // An empty name is sent too, so the device drops the previous route's street.
            out.navWrite(text(name), "navtx")
        }
        val dest = f.dest?.let { e7(it.lat) to e7(it.lng) }
        if (!destSent || dest != lastDest) {
            destSent = true
            lastDest = dest
            out.navWrite(dest(dest), "navdest")
        }
    }

    /** The posted limit in the units Vela shows speeds in; 0 when it is not known. Sent on
     *  change, and once per connection so the device learns the units either way. */
    fun sendLimit(imperial: Boolean, limit: Int) {
        if (!out.navReady()) return
        val now = imperial to limit
        if (now == lastLimit) return
        lastLimit = now
        out.navWrite(limit(imperial, limit), "navlim")
    }

    /** The route line as [routeChunks] built it. Never coalesced: every chunk is needed. */
    fun sendRoute(chunks: List<ByteArray>) {
        if (!out.navReady()) return
        chunks.forEach { out.navWrite(it, null) }
    }

    /** The link dropped: the device forgot everything, so the next [send] repeats it all. */
    fun reset() {
        lastStreet = null
        lastDest = null
        destSent = false
        lastLimit = null
    }

    companion object {
        private const val STATE: Byte = 0x01
        private const val TEXT: Byte = 0x02
        private const val DEST: Byte = 0x03
        private const val ROUTE: Byte = 0x04
        private const val LIMIT: Byte = 0x05
        private const val PHONE: Byte = 0x07
        private const val TRAFFIC: Byte = 0x08
        /** `NAV_TRAFFIC_MAX`. */
        const val TRAFFIC_MAX = 50
        private const val F_ACTIVE = 0x01
        /** `NAV_F_REM_10M`: the remaining distance is in units of 10 m. */
        private const val F_REM_10M = 0x04
        private const val ROUTE_F_START = 0x01
        private const val ROUTE_F_END = 0x02
        /** `NAV_TEXT_MAX`, in bytes. */
        const val TEXT_MAX = 48
        /** `NAV_ROUTE_CHUNK_MAX`, in points. */
        const val ROUTE_CHUNK = 31
        /** `ROUTE_MAX` in the firmware's `map_view.c`. */
        const val ROUTE_MAX_POINTS = 512

        private fun u16(v: Int) = v.coerceIn(0, 0xFFFF).toShort()
        private fun e7(deg: Double) = Math.round(deg * 1e7).toInt()

        /** 12 bytes: type, flags, maneuver, u16 dist, after maneuver, u16 after dist, u16
         *  remaining distance in units of 10 m (flag [F_REM_10M], which reaches 655 km), u16
         *  remaining s. Little-endian. */
        fun state(f: DeviceNavFrame): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(STATE)
                put(((if (f.active) F_ACTIVE else 0) or F_REM_10M).toByte())
                put(f.maneuver.toByte())
                putShort(u16(f.distanceM))
                put(f.afterManeuver.toByte())
                putShort(u16(f.afterDistanceM))
                putShort(u16((f.remainingM + 5) / 10))
                putShort(u16(f.remainingS))
            }.array()

        /**
         * The route's congested stretches: type, count, then per stretch u16 start and u16 end
         * as a fraction of the route's length (0..65535) and a level (1 moderate, 2 heavy,
         * 3 severe). The device lays them on its own thinned line, so fractions travel better
         * than meters. With more than [TRAFFIC_MAX], the worst and longest are kept. No
         * stretches is a count of 0, which clears the device's.
         */
        fun traffic(route: Route?): ByteArray {
            val total = route?.distanceMeters ?: 0.0
            val spans = if (route == null || total <= 0.0) emptyList() else route.trafficSpans
                .filter { it.level >= 1 && it.lengthMeters > 0.0 }
                .sortedWith(compareByDescending<TrafficSpan> { it.level }.thenByDescending { it.lengthMeters })
                .take(TRAFFIC_MAX)
                .sortedBy { it.startMeters }
            val b = ByteBuffer.allocate(2 + spans.size * 5).order(ByteOrder.LITTLE_ENDIAN)
            b.put(TRAFFIC).put(spans.size.toByte())
            for (s in spans) {
                val start = (s.startMeters / total * 65535).toInt().coerceIn(0, 65534)
                val end = ((s.startMeters + s.lengthMeters) / total * 65535).toInt().coerceIn(start + 1, 65535)
                b.putShort(start.toShort()).putShort(end.toShort()).put(s.level.coerceAtMost(3).toByte())
            }
            return b.array()
        }

        /** 12 bytes: type, battery percent (0xFF unknown), flags (bit 0 charging), 0, u32 Unix
         *  seconds, i16 minutes east of UTC, two zero bytes. */
        fun phone(batteryPct: Int?, charging: Boolean, epochS: Long, utcOffsetMin: Int): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(PHONE)
                put((batteryPct?.coerceIn(0, 100) ?: 0xFF).toByte())
                put(if (charging) 1 else 0)
                put(0)
                putInt((epochS and 0xFFFFFFFFL).toInt())
                putShort(utcOffsetMin.toShort())
                putShort(0)
            }.array()

        /** Type, length, then UTF-8 cut at a character boundary inside [TEXT_MAX] bytes. */
        fun text(name: String): ByteArray {
            var s = name
            var bytes = s.toByteArray(Charsets.UTF_8)
            while (bytes.size > TEXT_MAX) {
                s = s.dropLast(1)
                bytes = s.toByteArray(Charsets.UTF_8)
            }
            return byteArrayOf(TEXT, bytes.size.toByte()) + bytes
        }

        /** 10 bytes: type, present, i32 lat e7, i32 lon e7. Null clears the pin. */
        fun dest(latLonE7: Pair<Int, Int>?): ByteArray =
            ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(DEST)
                put(if (latLonE7 != null) 1 else 0)
                putInt(latLonE7?.first ?: 0)
                putInt(latLonE7?.second ?: 0)
            }.array()

        /** 4 bytes: type, units (0 km/h, 1 mph), u16 limit in those units (0 unknown). */
        fun limit(imperial: Boolean, limit: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(LIMIT)
                put(if (imperial) 1 else 0)
                putShort(u16(limit))
            }.array()

        /** Header (type, flags, seq, count) then count pairs of i32 lat e7, lon e7. Fewer than
         *  two points is one empty START|END chunk, which clears the line. */
        fun routeChunks(points: List<LatLng>): List<ByteArray> {
            val pts = RouteSimplify.forDisplay(points, ROUTE_MAX_POINTS)
            if (pts.size < 2) {
                return listOf(byteArrayOf(ROUTE, (ROUTE_F_START or ROUTE_F_END).toByte(), 0, 0))
            }
            val chunks = ArrayList<ByteArray>((pts.size + ROUTE_CHUNK - 1) / ROUTE_CHUNK)
            var i = 0
            while (i < pts.size) {
                val n = minOf(ROUTE_CHUNK, pts.size - i)
                var flags = 0
                if (i == 0) flags = flags or ROUTE_F_START
                if (i + n >= pts.size) flags = flags or ROUTE_F_END
                val b = ByteBuffer.allocate(4 + n * 8).order(ByteOrder.LITTLE_ENDIAN)
                b.put(ROUTE).put(flags.toByte()).put(chunks.size.toByte()).put(n.toByte())
                for (k in 0 until n) {
                    b.putInt(e7(pts[i + k].lat))
                    b.putInt(e7(pts[i + k].lng))
                }
                chunks += b.array()
                i += n
            }
            return chunks
        }

        /** 18 bytes: i32 lat e7, i32 lon e7, u16 speed cm/s, u16 course 0.01 deg, sats, fix,
         *  u32 phone clock ms. */
        fun position(lat: Double, lng: Double, speedMps: Float, courseDeg: Float, fix: Boolean, nowMs: Long): ByteArray =
            ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(e7(lat))
                putInt(e7(lng))
                putShort(u16((speedMps * 100).toInt()))
                putShort(((courseDeg * 100).toInt().coerceIn(0, 35_999)).toShort())
                put(0) // satellite count: not known here
                put(if (fix) 1 else 0)
                putInt((nowMs and 0xFFFFFFFFL).toInt())
            }.array()
    }
}

/**
 * Thins a route line for the device: Douglas-Peucker with a tolerance that doubles until the
 * result fits, then an even stride if a kilometer of tolerance was still not enough. Iterative,
 * so a long route cannot overflow the stack.
 */
object RouteSimplify {

    fun forDisplay(points: List<LatLng>, maxPoints: Int): List<LatLng> {
        if (points.size <= maxPoints) return points
        var eps = 5.0
        var out = reduce(points, eps)
        while (out.size > maxPoints && eps < 1000.0) {
            eps *= 2.0
            out = reduce(points, eps)
        }
        if (out.size <= maxPoints) return out
        val stride = (out.size + maxPoints - 2) / (maxPoints - 1)
        val thinned = ArrayList<LatLng>(maxPoints)
        var i = 0
        while (i < out.size - 1) {
            thinned += out[i]
            i += stride
        }
        thinned += out.last()
        return thinned
    }

    /** Douglas-Peucker: drops points that sit within [epsM] meters of the line through
     *  their neighbors. */
    fun thin(points: List<LatLng>, epsM: Double): List<LatLng> = reduce(points, epsM)

    private fun reduce(points: List<LatLng>, epsM: Double): List<LatLng> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        stack.addLast(points.size - 1)
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            val s = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = offLineM(points[i], points[s], points[e])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx != -1 && maxD > epsM) {
                keep[idx] = true
                stack.addLast(s)
                stack.addLast(idx)
                stack.addLast(idx)
                stack.addLast(e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Meters from [p] to the segment [a]-[b], on a flat patch around [p]. */
    private fun offLineM(p: LatLng, a: LatLng, b: LatLng): Double {
        val latM = 111_320.0
        val lngM = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * lngM
        val ay = (a.lat - p.lat) * latM
        val dx = (b.lng - a.lng) * lngM
        val dy = (b.lat - a.lat) * latM
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return kotlin.math.sqrt(cx * cx + cy * cy)
    }
}
