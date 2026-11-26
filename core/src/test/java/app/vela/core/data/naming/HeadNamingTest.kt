package app.vela.core.data.naming

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
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

    @Test fun theSeamIsGooglesStepNearestTwentyKilometers() {
        val (idx, m) = HeadNaming.seam(route)!!
        assertEquals(2, idx)
        assertEquals(18_000.0, m, 5.0)
    }

    @Test fun theHeadIsNamedAndGooglesStepsCarryOn() = runBlocking {
        var headLength = 0.0
        val named = HeadNaming.name(route) { head ->
            headLength = head.distanceMeters
            assertEquals(ManeuverType.ARRIVE, head.maneuvers.last().type)
            assertTrue(head.polyline.last().distanceTo(at(18_000.0)) < 5.0)
            // What the tiles would say: two turns Google left out.
            head.copy(
                legs = listOf(RouteLeg(head.distanceMeters, head.durationSeconds, null, listOf(
                    step(ManeuverType.DEPART, 0.0, 700.0, "Head north on A Street"),
                    step(ManeuverType.TURN_RIGHT, 700.0, 900.0, "Turn right onto B Street"),
                    step(ManeuverType.TURN_LEFT, 900.0, 18_000.0, "Turn left onto C Street"),
                    Maneuver(ManeuverType.ARRIVE, "", head.polyline.last(), 0.0, 0.0),
                ))),
                namesShort = false,
            )
        }!!
        assertEquals(18_000.0, headLength, 5.0)
        assertEquals(
            listOf("Head north on A Street", "Turn right onto B Street", "Turn left onto C Street", "Continue onto the highway", "Turn left", "Arrive"),
            named.maneuvers.map { it.instruction },
        )
        assertEquals(18_000.0, named.namedToM!!, 5.0)
        assertEquals(RouteSource.GOOGLE_LINE_NAMED, named.source)
        assertTrue(named.hasRealSteps)
        // The whole route is kept: its line, length and times.
        assertEquals(line, named.polyline)
        assertEquals(100_000.0, named.distanceMeters, 0.0)
        assertEquals(4_400.0, named.durationInTrafficSeconds!!, 0.0)
    }

    @Test fun noStepNearTheHeadKeepsTheAbbreviatedRoute() = runBlocking {
        val sparse = route.copy(legs = listOf(RouteLeg(100_000.0, 4_000.0, 4_400.0, listOf(google[0], google[3], google[4]))))
        assertNull(HeadNaming.seam(sparse))
        assertNull(HeadNaming.name(sparse) { it })
    }

    @Test fun aHeadTheTilesCannotNameKeepsTheAbbreviatedRoute() = runBlocking {
        assertNull(HeadNaming.name(route) { null })
    }
}
