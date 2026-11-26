package app.vela.device

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.TrafficSpan
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

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

        /**
         * The line under the distance: the road being entered, else its number, else the
         * place for the last step, else the instruction the phone's own banner shows. Many
         * steps carry no road (a fork, a ramp, a turn onto a lane with no name), and the
         * instruction is always there.
         */
        private fun lineFor(next: Maneuver?, destination: String): String {
            if (next == null) return ""
            next.road?.takeIf { it.isNotBlank() }?.let { return it }
            next.ref?.takeIf { it.isNotBlank() }?.let { return it }
            if (next.type == ManeuverType.ARRIVE && destination.isNotBlank()) return destination
            return next.instruction.trim()
        }

        /** The frame for a session state. A paused drive is sent as no drive at all: the
         *  device drops its pin and line whenever it is told the drive is not active. */
        fun of(s: NavSession.State): DeviceNavFrame {
            val route = s.route
            if (route == null || !(s.navigating || s.arrived)) return IDLE
            if (s.paused && !s.arrived) return IDLE
            val dest = route.polyline.lastOrNull()
            if (s.arrived) {
                return DeviceNavFrame(active = true, maneuver = DeviceManeuver.ARRIVE, street = s.destinationLabel, dest = dest)
            }
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
                street = lineFor(next, s.destinationLabel),
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
    private var lastStops: ByteArray? = null

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

    /** The waypoint face (bike-os): which stop is next, of how many, and how far. Sent when it
     *  changes; the distance is rounded to 50 m and the time to 30 s so a drive does not send it
     *  every second. count 0 clears the face. */
    fun sendStops(next: Int, count: Int, distanceM: Double, seconds: Double, label: String) {
        if (!out.navReady()) return
        val b = stops(next, count, (distanceM / 50).roundToLong() * 50, (seconds / 30).roundToLong() * 30, label)
        if (lastStops?.contentEquals(b) == true) return
        lastStops = b
        out.navWrite(b, "navstops")
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
        lastStops = null
    }

    companion object {
        private const val STATE: Byte = 0x01
        private const val TEXT: Byte = 0x02
        private const val DEST: Byte = 0x03
        private const val ROUTE: Byte = 0x04
        private const val LIMIT: Byte = 0x05
        private const val PHONE: Byte = 0x07
        private const val TRAFFIC: Byte = 0x08
        private const val STOPS: Byte = 0x09
        /** `NAV_STOPS_LABEL_MAX`, in bytes. */
        const val STOPS_LABEL_MAX = 40
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
        /** `ROUTE_MAX` in the firmware's `map_view.c` (bike-os 0.10+; firmware_ws146 took 512). */
        const val ROUTE_MAX_POINTS = 2048

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

        /** 12 bytes: type, battery percent (0xFF unknown), flags (bit 0 charging, bit 1 a
         *  24-hour clock), 0, u32 Unix seconds, i16 minutes east of UTC, two zero bytes. */
        fun phone(batteryPct: Int?, charging: Boolean, clock24: Boolean, epochS: Long, utcOffsetMin: Int): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(PHONE)
                put((batteryPct?.coerceIn(0, 100) ?: 0xFF).toByte())
                put(((if (charging) 1 else 0) or (if (clock24) 2 else 0)).toByte())
                put(0.toByte())
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

        /** NAV_STOPS (bike-os): type, next stop (1-based, 0 = none left), count, 0, u32 meters,
         *  u16 seconds, label length, then the label as UTF-8 cut at a character boundary. */
        fun stops(next: Int, count: Int, distanceM: Long, seconds: Long, label: String): ByteArray {
            var s = label
            var bytes = s.toByteArray(Charsets.UTF_8)
            while (bytes.size > STOPS_LABEL_MAX) {
                s = s.dropLast(1)
                bytes = s.toByteArray(Charsets.UTF_8)
            }
            return ByteBuffer.allocate(11 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(STOPS)
                put(next.coerceIn(0, 255).toByte())
                put(count.coerceIn(0, 255).toByte())
                put(0.toByte())
                putInt(distanceM.coerceIn(0, 0xFFFF_FFFFL).toInt())
                putShort(u16(seconds.coerceIn(0, 0xFFFF).toInt()))
                put(bytes.size.toByte())
                put(bytes)
            }.array()
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
        fun routeChunks(points: List<LatLng>, progressM: Double = 0.0): List<ByteArray> {
            // bike-os: full detail for the road ahead of [progressM], coarser further on; the
            // service sends the line again every [RouteSimplify.RESEND_M] of progress.
            val pts = RouteSimplify.forDevice(points, ROUTE_MAX_POINTS, progressM)
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

    /** Kept at full detail ([AHEAD_EPS_M]) past the rider's place on the route. */
    const val AHEAD_M = 20_000.0
    /** A little behind the rider too, so a resend does not cut the line at the marker. */
    const val BEHIND_M = 300.0
    const val AHEAD_EPS_M = 5.0
    /** The part already ridden: only its shape matters (the device dims it). */
    private const val PAST_EPS_M = 300.0
    /** Detail kept for the far part beyond the window, at least. */
    private const val FAR_MIN_POINTS = 64
    /** Send the line again after this much progress, so the detail keeps ahead of the rider. */
    const val RESEND_M = 8_000.0

    /**
     * For the bike display: the whole route, but its points spent where the rider is going.
     * Thinning the whole line evenly (as [forDisplay] does) made a long ride's line drift tens
     * of meters off the roads; here the next [AHEAD_M] keeps [AHEAD_EPS_M] and only the rest is
     * thinned harder. The line still runs start to end, so places along it (traffic spans, the
     * device's progress) keep their meaning.
     */
    fun forDevice(points: List<LatLng>, maxPoints: Int, progressM: Double): List<LatLng> {
        if (points.size <= maxPoints) return points
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + points[i - 1].distanceTo(points[i])
        fun indexAt(m: Double): Int {
            if (m <= 0.0) return 0
            var lo = 0
            var hi = points.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (cum[mid] < m) lo = mid + 1 else hi = mid
            }
            return lo
        }
        val from = indexAt(progressM - BEHIND_M)
        val to = maxOf(from, indexAt(progressM + AHEAD_M))

        val past = if (from > 0) reduce(points.subList(0, from + 1), PAST_EPS_M) else listOf(points[0])
        val aheadMax = (maxPoints - past.size - FAR_MIN_POINTS).coerceAtLeast(2)
        val ahead = fit(points.subList(from, to + 1), AHEAD_EPS_M, aheadMax)
        val farMax = (maxPoints - past.size - ahead.size + 2).coerceAtLeast(2)
        val far = if (to < points.size - 1) fit(points.subList(to, points.size), AHEAD_EPS_M, farMax) else listOf(points.last())

        val out = ArrayList<LatLng>(past.size + ahead.size + far.size)
        out += past
        out += ahead.drop(1)
        out += far.drop(1)
        return if (out.size <= maxPoints) out else forDisplay(out, maxPoints)
    }

    fun forDisplay(points: List<LatLng>, maxPoints: Int): List<LatLng> = fit(points, 5.0, maxPoints)

    /** Douglas-Peucker from [eps0], the tolerance doubling until the line fits [maxPoints]; then
     *  an even stride if a kilometer of tolerance was still not enough. */
    private fun fit(points: List<LatLng>, eps0: Double, maxPoints: Int): List<LatLng> {
        if (points.size <= maxPoints) return points
        var eps = eps0
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
