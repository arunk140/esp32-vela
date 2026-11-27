package app.vela.core.data.naming

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.destinationPoint
import app.vela.core.nav.RouteProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long line north out of Davis with Google's abbreviated steps every so often. */
class HeadNamingTest {
    private val a = LatLng(38.5400, -121.7400)
    private val line = List(401) { a.destinationPoint(it * 250.0, 0.0) } // 100 km
    private fun at(m: Double) = a.destinationPoint(m, 0.0)
    private fun step(type: ManeuverType, m: Double, toM: Double, text: String) = Maneuver(type, text, at(m), toM - m, 0.0)
    private val google = listOf(
        step(ManeuverType.DEPART, 0.0, 2_000.0, "Head north"),
        step(ManeuverType.KEEP_RIGHT, 2_000.0, 18_000.0, "Keep right"),
        step(ManeuverType.STRAIGHT, 18_000.0, 60_000.0, "Continue onto the highway"),
        step(ManeuverType.TURN_LEFT, 60_000.0, 100_000.0, "Turn left"),
        Maneuver(ManeuverType.ARRIVE, "Arrive", line.last(), 0.0, 0.0),
    )
    private val route = Route(
        line, listOf(RouteLeg(100_000.0, 4_000.0, 4_400.0, google)), 100_000.0, 4_000.0, 4_400.0,
        abbreviatedSteps = true, source = RouteSource.GOOGLE_ABBREVIATED,
    )

    /** What the tiles would say for a stretch: [turns] as (meters into the stretch, type, text). */
    private fun tiles(stretch: Route, vararg turns: Triple<Double, ManeuverType, String>): Route {
        val o = stretch.polyline.first()
        val len = stretch.distanceMeters
        val marks = listOf(0.0) + turns.map { it.first } + len
        val steps = ArrayList<Maneuver>()
        steps += Maneuver(ManeuverType.DEPART, "Head north", o, marks[1], 0.0)
        turns.forEachIndexed { i, t -> steps += Maneuver(t.second, t.third, o.destinationPoint(t.first, 0.0), marks[i + 2] - t.first, 0.0) }
        steps += Maneuver(ManeuverType.ARRIVE, "", stretch.polyline.last(), 0.0, 0.0)
        return stretch.copy(legs = listOf(RouteLeg(len, stretch.durationSeconds, null, steps)))
    }

    @Test fun theFirstStretchIsNamedAndGooglesStepsCarryOnPastTheCut() = runBlocking {
        var stretchLength = 0.0
        val named = HeadNaming.name(route) { s ->
            stretchLength = s.distanceMeters
            tiles(s, Triple(700.0, ManeuverType.TURN_RIGHT, "Turn right onto B Street"), Triple(900.0, ManeuverType.TURN_LEFT, "Turn left onto C Street"))
        }!!
        assertEquals(HeadNaming.STRETCH_M, stretchLength, 5.0)
        // Google's keep right at 2 km has no tiles' turn near it and is kept.
        assertEquals(listOf("Head north", "Turn right onto B Street", "Turn left onto C Street", "Keep right", "Turn left", "Arrive"), named.maneuvers.map { it.instruction })
        assertEquals(2_000.0 - 900.0, named.maneuvers[2].distanceMeters, 5.0)
        // The last named step runs on to Google's next step, 60 km in.
        assertEquals(60_000.0 - 2_000.0, named.maneuvers[3].distanceMeters, 5.0)
        assertEquals(HeadNaming.STRETCH_M, named.namedToM!!, 5.0)
        assertEquals(RouteSource.GOOGLE_LINE_NAMED, named.source)
        assertTrue(named.hasRealSteps)
        assertEquals(line, named.polyline)
        assertEquals(4_400.0, named.durationInTrafficSeconds!!, 0.0)
    }

    @Test fun theNextStretchJoinsOnFromTheLastNamedStep() = runBlocking {
        val first = HeadNaming.name(route) { s -> tiles(s, Triple(900.0, ManeuverType.TURN_LEFT, "Turn left onto C Street")) }!!
        val next = HeadNaming.nameNext(first) { s -> tiles(s, Triple(5_000.0, ManeuverType.KEEP_LEFT, "Keep left onto the highway")) }!!
        assertEquals(listOf("Head north", "Turn left onto C Street", "Keep right", "Keep left onto the highway", "Turn left", "Arrive"), next.maneuvers.map { it.instruction })
        // The keep right runs to the stretch's start and on to its first turn; the keep left runs to Google's turn.
        assertEquals(20_000.0 - 2_000.0 + 5_000.0, next.maneuvers[2].distanceMeters, 5.0)
        assertEquals(60_000.0 - 25_000.0, next.maneuvers[3].distanceMeters, 5.0)
        assertEquals(40_000.0, next.namedToM!!, 5.0)
    }

    @Test fun stretchAfterStretchNamesTheWholeRoute() = runBlocking {
        var r = HeadNaming.name(route) { s -> tiles(s) }!!
        var stretches = 1
        while (r.namedToM != null) {
            r = HeadNaming.nameNext(r) { s -> tiles(s) }!!
            stretches++
            assertTrue("runaway", stretches < 10)
        }
        assertTrue(stretches >= 4)
        assertEquals(ManeuverType.ARRIVE, r.maneuvers.last().type)
        assertEquals(100_000.0, r.maneuvers.sumOf { it.distanceMeters }, 10.0)
        assertNull(HeadNaming.nameNext(r) { s -> tiles(s) })
    }

    @Test fun aCutStaysOffABend() {
        val bend = List(81) { a.destinationPoint(it * 250.0, 0.0) } + List(80) { a.destinationPoint(20_000.0, 0.0).destinationPoint((it + 1) * 250.0, 90.0) }
        val r = route.copy(polyline = bend)
        val cum = RouteProjection.cumulative(bend)
        val cut = HeadNaming.cutAt(r, cum, DoubleArray(0), 20_000.0)
        assertTrue("cut at $cut", cut >= 20_000.0 + HeadNaming.CUT_CHORD_M)
        // And off one of Google's steps.
        val nearStep = HeadNaming.cutAt(route, RouteProjection.cumulative(line), doubleArrayOf(0.0, 20_100.0), 20_000.0)
        assertTrue("cut at $nearStep", nearStep - 20_100.0 >= HeadNaming.CUT_CLEAR_M)
    }

    @Test fun aGoogleTurnTheTilesMissedIsKept() = runBlocking {
        // Google's keep right at 2 km; the tiles find a turn at 700 m and none near 2 km.
        val named = HeadNaming.name(route) { s -> tiles(s, Triple(700.0, ManeuverType.TURN_RIGHT, "Turn right onto B Street")) }!!
        assertEquals(listOf("Head north", "Turn right onto B Street", "Keep right", "Turn left", "Arrive"), named.maneuvers.map { it.instruction })
        assertEquals(2_000.0 - 700.0, named.maneuvers[1].distanceMeters, 5.0)
        assertEquals(60_000.0 - 2_000.0, named.maneuvers[2].distanceMeters, 5.0)
        // A tiles' turn within 150 m of it stands for it: Google's is not added twice.
        val near = HeadNaming.name(route) { s -> tiles(s, Triple(1_950.0, ManeuverType.KEEP_RIGHT, "Keep right onto the ramp")) }!!
        assertEquals(listOf("Head north", "Keep right onto the ramp", "Turn left", "Arrive"), near.maneuvers.map { it.instruction })
        // Google's straight steps are never put back (the 18 km "Continue onto the highway").
        assertTrue(named.maneuvers.none { it.type == ManeuverType.STRAIGHT })
        assertEquals(100_000.0, named.maneuvers.sumOf { it.distanceMeters }, 10.0)
    }

    @Test fun aRouteNamedWholeKeepsTheGoogleTurnsTheTilesMissed() {
        // The tiles name the whole line with one turn at 700 m: Google's keep right at 2 km and
        // turn left at 60 km come back, its straight step does not.
        val named = HeadNaming.keepGoogleTurns(route, tiles(route, Triple(700.0, ManeuverType.TURN_RIGHT, "Turn right onto B Street")))
        assertEquals(listOf("Head north", "Turn right onto B Street", "Keep right", "Turn left", ""), named.maneuvers.map { it.instruction })
        assertEquals(100_000.0, named.maneuvers.sumOf { it.distanceMeters }, 10.0)
        // Tiles that found every turn are left as they are.
        val full = tiles(route, Triple(2_000.0, ManeuverType.KEEP_RIGHT, "Keep right onto I-80"), Triple(60_000.0, ManeuverType.TURN_LEFT, "Turn left onto Main St"))
        assertEquals(full, HeadNaming.keepGoogleTurns(route, full))
    }

    @Test fun aTilesUturnGoogleDoesNotHaveTakesGooglesStepOrIsDropped() {
        // The tiles read a U-turn at 2 km where Google keeps right: Google's step wins.
        val near = HeadNaming.keepGoogleTurns(route, tiles(route, Triple(1_980.0, ManeuverType.UTURN, "Make a U-turn onto B Street")))
        assertEquals(ManeuverType.KEEP_RIGHT, near.maneuvers[1].type)
        assertEquals("Keep right", near.maneuvers[1].instruction)
        assertTrue(near.maneuvers.none { it.type == ManeuverType.UTURN })
        // A U-turn at 10 km with no Google step near is a bend of the road: folded away, its
        // road kept as a rename.
        val bend = HeadNaming.keepGoogleTurns(route, tiles(route, Triple(10_000.0, ManeuverType.UTURN, "Make a U-turn onto C Street")))
        assertTrue(bend.maneuvers.none { it.type == ManeuverType.UTURN })
        assertEquals(100_000.0, bend.maneuvers.sumOf { it.distanceMeters }, 10.0)
        // Google's own U-turn stays.
        val g = route.copy(legs = listOf(route.legs.single().copy(maneuvers = google.toMutableList().also {
            it[3] = step(ManeuverType.UTURN, 60_000.0, 100_000.0, "Make a U-turn")
        })))
        val real = HeadNaming.keepGoogleTurns(g, tiles(g, Triple(60_020.0, ManeuverType.UTURN, "Make a U-turn onto D Street")))
        assertEquals("Make a U-turn onto D Street", real.maneuvers.single { it.type == ManeuverType.UTURN }.instruction)
    }

    @Test fun googlesFlyoverWordsReachTheNamedSteps() {
        val fly = route.copy(legs = listOf(route.legs.single().copy(maneuvers = google.toMutableList().also {
            it[2] = step(ManeuverType.STRAIGHT, 18_000.0, 60_000.0, "Continue straight to take the flyover")
        })))
        // A tiles' step beside it takes its words; with none there it is put in, though straight.
        val near = HeadNaming.keepGoogleTurns(fly, tiles(fly, Triple(2_000.0, ManeuverType.KEEP_RIGHT, "Keep right onto I-80"), Triple(18_050.0, ManeuverType.SLIGHT_LEFT, "Slight left onto Main St")))
        assertEquals("Continue straight to take the flyover", near.maneuvers[2].instruction)
        assertEquals(ManeuverType.SLIGHT_LEFT, near.maneuvers[2].type)
        val alone = HeadNaming.keepGoogleTurns(fly, tiles(fly, Triple(2_000.0, ManeuverType.KEEP_RIGHT, "Keep right onto I-80")))
        assertTrue(alone.maneuvers.any { it.instruction == "Continue straight to take the flyover" })
        assertEquals(100_000.0, alone.maneuvers.sumOf { it.distanceMeters }, 10.0)
    }

    @Test fun aNestedFlyoverStepIsPutInWhereNoStepSaysIt() {
        // Google's step list says nothing at 30 km; a step nested under the highway one takes a
        // flyover there. A second one beside Google's keep right adds nothing new.
        val fly = route.copy(roadKindSteps = listOf(
            app.vela.core.model.RoadKindStep(at(30_000.0), "Take the flyover"),
        ))
        val named = HeadNaming.keepGoogleTurns(fly, tiles(fly, Triple(2_000.0, ManeuverType.KEEP_RIGHT, "Keep right onto I-80"), Triple(60_000.0, ManeuverType.TURN_LEFT, "Turn left onto Main St")))
        val f = named.maneuvers.single { it.instruction == "Take the flyover" }
        assertEquals(ManeuverType.STRAIGHT, f.type)
        assertEquals(30_000.0, named.maneuvers.takeWhile { it !== f }.sumOf { it.distanceMeters }, 10.0)
        assertEquals(100_000.0, named.maneuvers.sumOf { it.distanceMeters }, 10.0)
    }

    @Test fun aStretchTheMapLeavesUnnamedIsSkippedAndTheRestIsStillNamed() = runBlocking {
        val first = HeadNaming.name(route) { s -> tiles(s) }!!
        val skipped = HeadNaming.skipNext(first)!!
        assertEquals(40_000.0, skipped.namedToM!!, 5.0)
        assertEquals(first.maneuvers, skipped.maneuvers)
        // Naming goes on from past the skipped stretch, to the destination.
        var r = skipped
        while (r.namedToM != null) r = HeadNaming.nameNext(r) { s -> tiles(s) }!!
        assertEquals(100_000.0, r.maneuvers.sumOf { it.distanceMeters }, 10.0)
        assertEquals(ManeuverType.ARRIVE, r.maneuvers.last().type)
    }

    @Test fun aStretchTheTilesCannotNameKeepsTheRouteAsItIs() = runBlocking {
        assertNull(HeadNaming.name(route) { null })
    }
}
