package app.vela.core.data.google.parse

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Google's step list read from every step's structured parts, in the shape calibrated
 *  2026-11-14 (`DirectionsExitSignsTest`). Coordinates are the Davis fixture. */
class DirectionsFullStepsTest {
    private fun arr(vararg e: JsonElement?) = JsonArray(e.map { it ?: JsonNull })
    private fun n(v: Number) = JsonPrimitive(v)
    private fun s(v: String) = JsonPrimitive(v)

    private fun stepOf(text: String, at: LatLng, icon: String?): JsonArray {
        val parts = ArrayList<JsonArray>()
        Regex("\\[([^\\]]*)\\]|([^\\[]+)").findAll(text).forEach { m ->
            val name = m.groups[1]?.value
            parts += if (name != null) arr(n(2), arr(s(name), n(1))) else arr(n(2), arr(s(m.groups[2]!!.value)))
        }
        val st = arr(
            n(0), s(""), arr(n(500), s("0.3 mi"), n(1)), arr(n(30), s("30 sec")), null, null, null,
            arr(arr(arr(n(2612.0), n(at.lng), n(at.lat))), arr(), arr(null, null, n(at.lat), n(at.lng))),
            null, null, null, null, null, null, JsonArray(parts),
        )
        return arr(st, null, arr(s("//maps.gstatic.com/tactile/directions/text_mode/maneuvers-2x.png"), icon?.let { s("dir-tt $it") }))
    }

    private val a = LatLng(38.5449, -121.7405)
    private val line = List(81) { a.destinationPoint(it * 100.0, 0.0) } // 8 km north
    private fun at(m: Double) = a.destinationPoint(m, 0.0)

    private fun route(vararg st: Triple<String, Double, String?>): JsonElement =
        arr(arr(*st.map { (t, m, icon) -> stepOf(t, at(m), icon) }.toTypedArray()))

    @Test fun everyStepIsReadWithItsTurnAndPlacedAtItsOwnPoint() {
        val r = route(
            Triple("Head north", 0.0, "dir-tt-straight"),
            Triple("Merge onto [Covell Blvd]", 1_800.0, "dir-tt-merge"),
            Triple("Keep right to take the flyover", 1_890.0, "dir-tt-turn-right"),
            Triple("Continue onto [Covell Flyover]", 2_000.0, "dir-tt-straight"),
            Triple("Keep right to take the flyover", 3_800.0, "dir-tt-turn-right"),
            Triple("Slight left", 5_200.0, "dir-tt-turn-slight-left"),
            Triple("Turn left after [Davis Market] (on the left)", 5_800.0, "dir-tt-turn-left"),
        )
        val steps = DirectionsParser.fullSteps(r, line, markupCount = 4)!!
        assertEquals(
            listOf(
                ManeuverType.DEPART, ManeuverType.MERGE, ManeuverType.KEEP_RIGHT, ManeuverType.STRAIGHT,
                ManeuverType.KEEP_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.TURN_LEFT,
            ),
            steps.map { it.type },
        )
        assertEquals("Continue onto Covell Flyover", steps[3].instruction)
        assertEquals(1_800.0, steps[0].distanceMeters, 2.0)
        assertEquals(90.0, steps[1].distanceMeters, 2.0)
        assertEquals(1_400.0, steps[4].distanceMeters, 2.0)
        assertEquals(8_000.0 - 5_800.0, steps.last().distanceMeters, 2.0)
        assertEquals(0.0, steps[5].location.distanceTo(at(5_200.0)), 2.0)
    }

    @Test fun aStepOffTheLineOrOutOfOrderLeavesTheMarkupList() {
        val off = route(Triple("Head north", 0.0, "dir-tt-straight"), Triple("Turn left", 3_000.0, "dir-tt-turn-left"))
        val shifted = DirectionsParser.fullSteps(off, line.map { LatLng(it.lat, it.lng + 0.01) }, 1)
        assertNull(shifted)
        val back = route(Triple("Head north", 0.0, null), Triple("Turn left", 3_000.0, null), Triple("Turn right", 1_000.0, null))
        assertNull(DirectionsParser.fullSteps(back, line, 1))
        // Fewer steps than the markup has: the markup stands.
        assertNull(DirectionsParser.fullSteps(route(Triple("Head north", 0.0, null), Triple("Turn left", 3_000.0, null)), line, 5))
    }

    @Test fun aStepWithNoIconTakesItsTurnFromItsWords() {
        assertEquals(ManeuverType.KEEP_LEFT, DirectionsParser.iconType(null, "Keep left to stay on I-80"))
        assertEquals(ManeuverType.UTURN, DirectionsParser.iconType(null, "Make a U-turn"))
        assertEquals(ManeuverType.KEEP_LEFT, DirectionsParser.iconType("dir-tt-turn-left", "Keep left to take the flyover"))
        assertEquals(ManeuverType.SHARP_RIGHT, DirectionsParser.iconType("dir-tt-turn-sharp-right", "Turn sharp right"))
        assertEquals(ManeuverType.UTURN, DirectionsParser.iconType("dir-tt-uturn-left", "Make a U-turn"))
    }
}
