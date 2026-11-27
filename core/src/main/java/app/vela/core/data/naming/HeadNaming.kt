package app.vela.core.data.naming

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoadRename
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.bearingTo
import app.vela.core.nav.RouteProjection
import kotlin.math.abs

/**
 * Names a route too long to name whole, one stretch at a time. [RoadNameTiles.linesAlong] gives up
 * past its tile cap (48 tiles at z14), and such a route used to keep Google's abbreviated steps from
 * start to end, a fraction of the turns. [name] names its first [STRETCH_M] from the tiles
 * ([LineNamer]); Google's own steps carry on after the cut. [Route.namedToM] says where the named
 * steps end. [nameNext] names the stretch after it the same way, and the last one runs to the
 * destination; the drive calls it until the whole route is named (`NavSession`).
 *
 * A cut falls where the line runs straight ([cutAt]): the tiles' turns are read across a window
 * either side, so a junction at a cut would go unsaid, and a Google step near it would be said
 * twice. The named step before a cut runs on to the next step after it, so a Google step tens of
 * kilometers on (a highway's "Continue for 60 km") needs no cut of its own.
 */
object HeadNaming {
    /** The stretch named at a time. Within [LAST_M] of the destination the stretch runs to it. */
    const val STRETCH_M = 20_000.0
    const val LAST_M = 30_000.0
    /** A cut is looked for from its target to [CUT_SEEK_M] past it, every [CUT_STEP_M], where the
     *  chords [CUT_CHORD_M] either side differ by under [CUT_STRAIGHT_DEG] and no Google step lies
     *  within [CUT_CLEAR_M]. With none there, the target itself. */
    const val CUT_SEEK_M = 3_000.0
    const val CUT_STEP_M = 100.0
    const val CUT_CHORD_M = 150.0
    const val CUT_STRAIGHT_DEG = 8.0
    const val CUT_CLEAR_M = 300.0
    /** How far a step may sit off the line and still be placed along it. */
    private const val MATCH_M = 40.0

    /** Where each of [route]'s steps lies along its line; NaN for one that could not be placed. Each
     *  is looked for past the one before, so a line that comes back near itself does not place a
     *  later step at an earlier pass. */
    fun stepsAlong(route: Route, cum: DoubleArray): DoubleArray {
        val steps = route.maneuvers
        val out = DoubleArray(steps.size) { Double.NaN }
        var from = 0.0
        for (i in steps.indices) {
            val at = when {
                i == 0 -> 0.0
                i == steps.lastIndex && steps[i].type == ManeuverType.ARRIVE -> cum.last()
                else -> RouteProjection.alongMeters(route.polyline, cum, steps[i].location, MATCH_M) ?: continue
            }
            if (at < from) continue
            out[i] = at
            from = at
        }
        return out
    }

    /** Where to cut for a stretch that should end near [targetM] (see [CUT_SEEK_M]). */
    fun cutAt(route: Route, cum: DoubleArray, along: DoubleArray, targetM: Double): Double {
        val poly = route.polyline
        val total = cum.last()
        fun chord(a: Double, b: Double) = RouteProjection.pointAt(poly, cum, a).bearingTo(RouteProjection.pointAt(poly, cum, b))
        var m = targetM
        while (m <= targetM + CUT_SEEK_M && m + CUT_CHORD_M < total) {
            val straight = abs(RouteGeometry.bearingDelta(chord(m - CUT_CHORD_M, m), chord(m, m + CUT_CHORD_M))) < CUT_STRAIGHT_DEG
            val clear = along.none { !it.isNaN() && abs(it - m) < CUT_CLEAR_M }
            if (straight && clear) return m
            m += CUT_STEP_M
        }
        return targetM
    }

    /** The route from [fromM] to [cutM] along its line, with a departure and an arrival: what
     *  [LineNamer] names. Google's step names and exits stay on it, for the turns at their points. */
    fun piece(route: Route, cum: DoubleArray, fromM: Double, cutM: Double): Route {
        val poly = route.polyline
        val pts = ArrayList<LatLng>()
        val start = RouteProjection.pointAt(poly, cum, fromM)
        pts += start
        var k = 0
        while (k < poly.size && cum[k] <= fromM) k++
        while (k < poly.size && cum[k] < cutM) { pts += poly[k]; k++ }
        val end = RouteProjection.pointAt(poly, cum, cutM)
        pts += end
        val length = cutM - fromM
        val share = if (route.distanceMeters > 0) length / route.distanceMeters else 1.0
        val dur = route.durationSeconds * share
        val traffic = route.durationInTrafficSeconds?.let { it * share }
        val steps = listOf(Maneuver(ManeuverType.DEPART, "", start, length, dur), Maneuver(ManeuverType.ARRIVE, "", end, 0.0, 0.0))
        return route.copy(
            polyline = pts,
            drawPolyline = null,
            legs = listOf(RouteLeg(length, dur, traffic, steps)),
            distanceMeters = length,
            durationSeconds = dur,
            durationInTrafficSeconds = traffic,
            legTimes = emptyList(),
            namedToM = null,
        )
    }

    /**
     * [route] with the steps from [fromM] to [cutM] replaced by [named]'s. From the start [named]'s
     * own departure leads; later, the named step before [fromM] runs on to [named]'s first turn.
     * The last named step runs on to the first of [route]'s steps past [cutM], or [cutM] is the
     * destination and [route]'s arrival ends the list.
     */
    fun join(route: Route, cum: DoubleArray, along: DoubleArray, named: Route, fromM: Double, cutM: Double): Route {
        val n = named.maneuvers
        val old = route.maneuvers
        val total = cum.last()
        val toEnd = cutM >= total - 1.0
        val before = old.indices.filter { !along[it].isNaN() && along[it] < fromM - 0.5 }.map { old[it] to along[it] }
        val after = if (toEnd) emptyList() else old.indices.filter { !along[it].isNaN() && along[it] > cutM + 0.5 }.map { old[it] to along[it] }
        val out = ArrayList<Maneuver>()
        if (before.isEmpty()) {
            out += n.dropLast(1)
        } else {
            before.dropLast(1).forEach { out += it.first }
            val (last, lastAt) = before.last()
            val gap = fromM - lastAt
            val head = n.first()
            out += last.copy(
                distanceMeters = gap + head.distanceMeters,
                durationSeconds = last.durationSeconds * (if (last.distanceMeters > 0) gap / last.distanceMeters else 0.0) + head.durationSeconds,
                renames = last.renames.filter { it.atMeters < gap } + head.renames.map { it.copy(atMeters = it.atMeters + gap) },
            )
            out += n.drop(1).dropLast(1)
        }
        if (toEnd) {
            out += if (old.lastOrNull()?.type == ManeuverType.ARRIVE) old.last() else n.last()
        } else {
            // The last named step runs to the cut; on from there to the next step.
            val nextAt = after.firstOrNull()?.second ?: total
            val i = out.lastIndex
            out[i] = out[i].copy(distanceMeters = out[i].distanceMeters + (nextAt - cutM))
            out += after.map { it.first }
            if (out.last().type != ManeuverType.ARRIVE) out += n.last().copy(location = route.polyline.last())
        }
        val leg = route.legs.single()
        return route.copy(
            legs = listOf(leg.copy(maneuvers = out)),
            provisional = false,
            abbreviatedSteps = false,
            source = RouteSource.GOOGLE_LINE_NAMED,
            namesShort = (before.isNotEmpty() && route.namesShort) || named.namesShort,
            namedToM = if (toEnd) null else cutM,
        )
    }

    /** [route] with its first stretch named by [nameStretch] (the tiles along the stretch and
     *  [LineNamer], from the caller), or null when the stretch could not be named. */
    suspend fun name(route: Route, nameStretch: suspend (Route) -> Route?): Route? = stretch(route, 0.0, nameStretch)

    /** [route], named up to [Route.namedToM], named one stretch further, to the destination when it
     *  is near. Null when the route is named whole, or the stretch could not be named. */
    suspend fun nameNext(route: Route, nameStretch: suspend (Route) -> Route?): Route? =
        route.namedToM?.let { stretch(route, it, nameStretch) }

    /** [route] with its next stretch left on Google's steps and the naming moved on past it: for a
     *  stretch the tiles were read for but could not name (roads the map leaves without names), so
     *  the stretches after it are still named. Null when the route is named whole. */
    fun skipNext(route: Route): Route? {
        val fromM = route.namedToM ?: return null
        if (route.legs.size != 1 || route.polyline.size < 2) return null
        val cum = RouteProjection.cumulative(route.polyline)
        val cutM = cutFor(route, cum, stepsAlong(route, cum), fromM) ?: return null
        return route.copy(namedToM = if (cutM >= cum.last() - 1.0) null else cutM)
    }

    private fun cutFor(route: Route, cum: DoubleArray, along: DoubleArray, fromM: Double): Double? {
        val total = cum.last()
        if (fromM >= total) return null
        return if (total - fromM <= LAST_M) total else cutAt(route, cum, along, fromM + STRETCH_M)
    }

    private suspend fun stretch(route: Route, fromM: Double, nameStretch: suspend (Route) -> Route?): Route? {
        if (route.legs.size != 1 || route.polyline.size < 2) return null
        val cum = RouteProjection.cumulative(route.polyline)
        val along = stepsAlong(route, cum)
        val cutM = cutFor(route, cum, along, fromM) ?: return null
        val tiles = nameStretch(piece(route, cum, fromM, cutM)) ?: return null
        val n = tiles.maneuvers
        if (n.size < 2 || n.first().type != ManeuverType.DEPART || n.last().type != ManeuverType.ARRIVE) return null
        val google = route.maneuvers.indices
            .filter { !along[it].isNaN() && along[it] > fromM + KEEP_EDGE_M && along[it] < cutM - KEEP_EDGE_M && route.maneuvers[it].type !in ENDS }
            .map { route.maneuvers[it] to along[it] - fromM }
            .let { it + roadKindAlong(route, cum, it.map { g -> g.first to g.second + fromM }).filter { s -> s.second > fromM + KEEP_EDGE_M && s.second < cutM - KEEP_EDGE_M }.map { s -> s.first to s.second - fromM } }
        return join(route, cum, along, withGoogleTurns(tiles, google), fromM, cutM)
    }

    /** [named], [route] named whole from the tiles on [route]'s own line, with each of [route]'s
     *  turns the tiles missed put back ([withGoogleTurns]), as a stretch gets. */
    fun keepGoogleTurns(route: Route, named: Route): Route {
        if (route.legs.size != 1 || route.polyline.size < 2 || named.legs.size != 1) return named
        val cum = RouteProjection.cumulative(route.polyline)
        val along = stepsAlong(route, cum)
        val total = cum.last()
        val google = route.maneuvers.indices
            .filter { !along[it].isNaN() && along[it] > KEEP_EDGE_M && along[it] < total - KEEP_EDGE_M && route.maneuvers[it].type !in ENDS }
            .map { route.maneuvers[it] to along[it] }
            .let { it + roadKindAlong(route, cum, it).filter { s -> s.second > KEEP_EDGE_M && s.second < total - KEEP_EDGE_M } }
        return withGoogleTurns(named, google)
    }

    /** [Route.roadKindSteps] as straight steps at their meters along [route], each one that no
     *  step of [google] near it already says. */
    private fun roadKindAlong(route: Route, cum: DoubleArray, google: List<Pair<Maneuver, Double>>): List<Pair<Maneuver, Double>> =
        route.roadKindSteps.mapNotNull { s ->
            val at = RouteProjection.alongMeters(route.polyline, cum, s.at, MATCH_M) ?: return@mapNotNull null
            if (google.any { abs(it.second - at) < KEEP_NEAR_M && mentionsRoadKind(it.first.instruction) }) return@mapNotNull null
            Maneuver(ManeuverType.STRAIGHT, s.text, s.at, 0.0, 0.0) to at
        }.distinctBy { it.second.toInt() / 50 }


    private val ENDS = setOf(ManeuverType.DEPART, ManeuverType.ARRIVE)

    /** Words that say a step takes a flyover, stays below one, or goes through a tunnel. The nav
     *  device matches the same words for its road badge. */
    private val ROAD_WORDS = listOf("flyover", "overpass", "elevated", "overbridge", "tunnel", "underpass", "service road")

    fun mentionsRoadKind(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        val low = s.lowercase()
        return ROAD_WORDS.any { it in low }
    }

    /** Google's turns that [withGoogleTurns] keeps: every step that changes the course or the road. */
    private val KEPT_TYPES = ManeuverType.entries.toSet() - setOf(
        ManeuverType.DEPART, ManeuverType.ARRIVE, ManeuverType.STRAIGHT, ManeuverType.CONTINUE, ManeuverType.UNKNOWN,
    )
    /** A Google turn with no named step within this along the line is one the tiles missed. */
    const val KEEP_NEAR_M = 150.0
    /** Google's steps this close to a stretch's ends are left to the steps on either side. */
    private const val KEEP_EDGE_M = 50.0

    /**
     * [named] checked against Google's own steps ([google]: each step and its meters into the
     * stretch). Google decides what kind of step a corner is; the tiles name it.
     * - A tiles' U-turn (a bend of 165 degrees or more on the line) that Google does not call a
     *   U-turn takes the type and words of Google's step within [KEEP_NEAR_M], or with none there is
     *   a bend of the road and is folded into the step before it.
     * - A Google step that names a flyover, an underpass or a tunnel ([mentionsRoadKind]) gives its
     *   words to the tiles' step within [KEEP_NEAR_M], or is put in at its place.
     * - A Google turn ([KEPT_TYPES]) with no named step within [KEEP_NEAR_M] is put in at its place:
     *   the tiles can miss a junction, and a turn Google says is there is not dropped. The step
     *   before it now ends there; the Google step runs on to where that one did.
     */
    fun withGoogleTurns(named: Route, google: List<Pair<Maneuver, Double>>): Route {
        if (google.isEmpty()) return named
        val pc = RouteProjection.cumulative(named.polyline)
        val steps = named.maneuvers
        val list = ArrayList<Pair<Maneuver, Double>>()
        for (i in steps.indices) {
            val at = when (i) {
                0 -> 0.0
                steps.lastIndex -> pc.last()
                else -> RouteProjection.alongMeters(named.polyline, pc, steps[i].location, MATCH_M) ?: Double.NaN
            }
            list += steps[i] to at
        }
        fun googleNear(at: Double) = google.filter { abs(it.second - at) < KEEP_NEAR_M }.minByOrNull { abs(it.second - at) }?.first
        fun takeWords(m: Maneuver, g: Maneuver, type: ManeuverType) = m.copy(
            type = type, instruction = g.instruction, instructionNoRoad = g.instructionNoRoad,
            road = g.road ?: m.road, ref = g.ref ?: m.ref, lanes = m.lanes.ifEmpty { g.lanes },
        )
        var changed = false

        // U-turns Google does not have.
        for (i in list.lastIndex - 1 downTo 1) {
            val (m, at) = list[i]
            if (m.type != ManeuverType.UTURN || at.isNaN()) continue
            val g = googleNear(at)
            if (g?.type == ManeuverType.UTURN) continue
            changed = true
            if (g != null) {
                list[i] = takeWords(m, g, g.type) to at
            } else {
                val (prev, prevAt) = list[i - 1]
                val gap = prev.distanceMeters
                list[i - 1] = prev.copy(
                    distanceMeters = prev.distanceMeters + m.distanceMeters,
                    durationSeconds = prev.durationSeconds + m.durationSeconds,
                    renames = prev.renames + listOfNotNull(
                        RoadRename(gap, m.road, m.ref).takeIf { m.road != null || m.ref != null },
                    ) + m.renames.map { it.copy(atMeters = it.atMeters + gap) },
                ) to prevAt
                list.removeAt(i)
            }
        }

        for ((g, gAt) in google.sortedBy { it.second }) {
            val words = mentionsRoadKind(g.instruction)
            if (g.type !in KEPT_TYPES && !words) continue
            val near = list.indices.filter { !list[it].second.isNaN() && abs(list[it].second - gAt) < KEEP_NEAR_M }
                .minByOrNull { abs(list[it].second - gAt) }
            if (near != null) {
                // A step stands for it; the flyover's words go onto that step.
                val (m, at) = list[near]
                if (words && near in 1 until list.lastIndex && !mentionsRoadKind(m.instruction)) {
                    list[near] = takeWords(m, g, if (g.type in KEPT_TYPES) g.type else m.type) to at
                    changed = true
                }
                continue
            }
            val j = list.indices.lastOrNull { it < list.lastIndex && !list[it].second.isNaN() && list[it].second < gAt } ?: continue
            val (prev, prevAt) = list[j]
            val prevEnd = prevAt + prev.distanceMeters
            val share = if (prev.distanceMeters > 0) (gAt - prevAt) / prev.distanceMeters else 1.0
            list[j] = prev.copy(distanceMeters = gAt - prevAt, durationSeconds = prev.durationSeconds * share, renames = prev.renames.filter { it.atMeters < gAt - prevAt }) to prevAt
            list.add(j + 1, g.copy(distanceMeters = (prevEnd - gAt).coerceAtLeast(0.0), durationSeconds = prev.durationSeconds * (1 - share)) to gAt)
            changed = true
        }
        if (!changed) return named
        val leg = named.legs.single()
        return named.copy(legs = listOf(leg.copy(maneuvers = list.map { it.first })))
    }
}
