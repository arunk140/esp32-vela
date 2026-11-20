package app.vela.device.watch

import app.vela.core.model.LatLng
import app.vela.device.DeviceManeuver
import app.vela.device.DeviceNavFrame
import app.vela.device.RouteSimplify
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The drive as the watch's Vela Nav app takes it. Pure, so it is unit-tested.
 *
 * Every position on the wire is metres east and north of the route's first point, never latitude
 * and longitude: the watch's app channel can be read by anyone in Bluetooth range. Over the few
 * hundred kilometres a drive covers, a flat patch around that point is accurate enough for a
 * 466 px map.
 */
object WatchDrive {
    /** `ROUTE_MAX` in the watch app's `proto.rs`. */
    const val ROUTE_MAX = 512
    private const val M_PER_DEG_LAT = 110_574.0
    private const val M_PER_DEG_LNG_EQ = 111_320.0

    /** A route line in the watch's units: points in metres from [origin], and their running length. */
    class Geometry(val origin: LatLng, val points: List<IntArray>, val cum: DoubleArray)

    /** A 16-bit tag for the frame around [origin]: two routes from the same first point share it. */
    fun frame(origin: LatLng): Int {
        val a = (origin.lat * 1e7).toLong()
        val b = (origin.lng * 1e7).toLong()
        return ((a * 31 + b) xor ((a * 31 + b) ushr 32)).toInt().let { it xor (it ushr 16) } and 0xFFFF
    }

    fun metres(origin: LatLng, p: LatLng): IntArray = intArrayOf(
        ((p.lng - origin.lng) * M_PER_DEG_LNG_EQ * cos(Math.toRadians(origin.lat))).roundToInt(),
        ((p.lat - origin.lat) * M_PER_DEG_LAT).roundToInt(),
    )

    /** Null for a line with fewer than two points. */
    fun geometry(polyline: List<LatLng>): Geometry? {
        if (polyline.size < 2) return null
        val thin = RouteSimplify.forDisplay(polyline, ROUTE_MAX)
        val origin = thin.first()
        val pts = thin.map { metres(origin, it) }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            val dx = (pts[i][0] - pts[i - 1][0]).toDouble()
            val dy = (pts[i][1] - pts[i - 1][1]).toDouble()
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        return Geometry(origin, pts, cum)
    }

    /**
     * The index of the first point still ahead of the car, [fraction] of the way along. The thinned
     * line is a little shorter than the real one, so the share of the way is what carries over.
     */
    fun progress(g: Geometry, fraction: Double): Int {
        val target = fraction.coerceIn(0.0, 1.0) * g.cum.last()
        val i = g.cum.indexOfFirst { it > target }
        return if (i < 0) g.points.size else i.coerceAtLeast(1)
    }

    /** Degrees clockwise from north → 1/65536 turns. */
    fun course(deg: Float): Int = ((deg / 360f) * 65536f).roundToInt() and 0xFFFF

    /**
     * The line under the turn. The destination's own name (a home, a friend's address) is not
     * sent: on the last step and on arrival the watch gets the road, or nothing.
     */
    fun line(frame: DeviceNavFrame, destinationLabel: String): String {
        if (frame.maneuver == DeviceManeuver.ARRIVE) return ""
        val s = frame.street.trim()
        return if (destinationLabel.isNotBlank() && s == destinationLabel.trim()) "" else s
    }

    @Suppress("LongParameterList")
    fun state(
        frame: DeviceNavFrame,
        arrived: Boolean,
        imperial: Boolean,
        clock24: Boolean,
        fix: Boolean,
        limit: Int,
        position: IntArray?,
        courseDeg: Float,
        progress: Int,
        snapshot: Boolean,
    ): WatchNav.State {
        var flags = 0
        if (frame.active) flags = flags or WatchNav.F_ACTIVE
        if (arrived) flags = flags or WatchNav.F_ARRIVED
        if (imperial) flags = flags or WatchNav.F_IMPERIAL
        if (fix && position != null) flags = flags or WatchNav.F_FIX
        if (clock24) flags = flags or WatchNav.F_CLOCK24
        if (snapshot) flags = flags or WatchNav.F_SNAPSHOT
        return WatchNav.State(
            flags = flags,
            maneuver = frame.maneuver,
            distM = frame.distanceM,
            after = frame.afterManeuver,
            afterM = frame.afterDistanceM,
            remainingM = frame.remainingM,
            remainingS = frame.remainingS,
            limit = limit,
            x = position?.get(0) ?: 0,
            y = position?.get(1) ?: 0,
            course = course(courseDeg),
            progress = progress,
        )
    }
}
