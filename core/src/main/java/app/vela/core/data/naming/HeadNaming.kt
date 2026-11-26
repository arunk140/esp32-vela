package app.vela.core.data.naming

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.nav.RouteProjection

/**
 * Names the first stretch of a route too long to name whole. [RoadNameTiles.linesAlong] gives up
 * past its tile cap (48 tiles at z14), and such a route used to keep Google's abbreviated
 * steps from start to end, a fraction of the turns. Here the line up to one of Google's own steps
 * near [HEAD_M] is named from the tiles, and Google's steps carry on from that step. The route's
 * [Route.namedToM] says where the named steps end, and a re-check during the drive names the
 * next stretch before the rider reaches it ([app.vela.core.nav.RouteHeal]).
 */
object HeadNaming {
    /** The stretch named ahead. */
    const val HEAD_M = 20_000.0
    /** The seam is one of Google's steps between these two along the line; with none there the
     *  route keeps its abbreviated steps. */
    const val MIN_HEAD_M = 5_000.0
    const val MAX_HEAD_M = 30_000.0
    /** How far a Google step may sit off the line and still be placed along it. */
    private const val MATCH_M = 40.0

    /** Where to cut: the index into [Route.maneuvers] of the Google step that starts the tail, and
     *  how far along the line it is. Null when no step lies between [MIN_HEAD_M] and [MAX_HEAD_M],
     *  or the route is not one leg of steps. */
    fun seam(route: Route, cum: DoubleArray = RouteProjection.cumulative(route.polyline), headM: Double = HEAD_M): Pair<Int, Double>? {
        if (route.legs.size != 1 || route.polyline.size < 2) return null
        val steps = route.maneuvers
        var best: Pair<Int, Double>? = null
        var from = 0.0
        for (i in 1 until steps.size) {
            // Each step is looked for past the one before, so a line that comes back near itself
            // does not place a later step at an earlier pass.
            val at = RouteProjection.alongMeters(route.polyline, cum, steps[i].location, MATCH_M) ?: continue
            if (at < from) continue
            from = at
            if (at < MIN_HEAD_M || at > MAX_HEAD_M) continue
            if (best == null || kotlin.math.abs(at - headM) < kotlin.math.abs(best.second - headM)) best = i to at
        }
        return best
    }

    /** The route up to [cutM] along its line, with Google's steps before [cutIdx] and an arrival at
     *  the cut: what [LineNamer] names. */
    fun head(route: Route, cum: DoubleArray, cutIdx: Int, cutM: Double): Route {
        val poly = route.polyline
        val pts = ArrayList<LatLng>()
        var k = 0
        while (k < poly.size && cum[k] < cutM) { pts += poly[k]; k++ }
        val end = RouteProjection.pointAt(poly, cum, cutM)
        pts += end
        val share = if (route.distanceMeters > 0) cutM / route.distanceMeters else 1.0
        val steps = route.maneuvers.take(cutIdx) + Maneuver(ManeuverType.ARRIVE, "", end, 0.0, 0.0)
        val dur = route.durationSeconds * share
        val traffic = route.durationInTrafficSeconds?.let { it * share }
        return route.copy(
            polyline = pts,
            drawPolyline = null,
            legs = listOf(RouteLeg(cutM, dur, traffic, steps)),
            distanceMeters = cutM,
            durationSeconds = dur,
            durationInTrafficSeconds = traffic,
            legTimes = emptyList(),
        )
    }

    /** [named]'s steps up to the cut, then [route]'s own from [cutIdx] on. */
    fun join(route: Route, named: Route, cutIdx: Int, cutM: Double): Route {
        val steps = named.maneuvers.dropLast(1) + route.maneuvers.drop(cutIdx)
        val leg = route.legs.single()
        return route.copy(
            legs = listOf(leg.copy(maneuvers = steps)),
            provisional = false,
            abbreviatedSteps = false,
            source = RouteSource.GOOGLE_LINE_NAMED,
            namesShort = named.namesShort,
            namedToM = cutM,
        )
    }

    /**
     * [route] with its first stretch named by [nameHead] (the tiles along the head and
     * [LineNamer], from the caller), or null when there is no seam or the head could not be named.
     */
    suspend fun name(route: Route, nameHead: suspend (Route) -> Route?): Route? {
        val cum = RouteProjection.cumulative(route.polyline)
        val (cutIdx, cutM) = seam(route, cum) ?: return null
        val named = nameHead(head(route, cum, cutIdx, cutM)) ?: return null
        if (named.maneuvers.lastOrNull()?.type != ManeuverType.ARRIVE) return null
        return join(route, named, cutIdx, cutM)
    }
}
