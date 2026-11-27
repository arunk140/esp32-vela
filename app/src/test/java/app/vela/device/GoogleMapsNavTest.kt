package app.vela.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** Google Maps' navigation notification, as the device shows it. Street names are the Davis fixture's. */
class GoogleMapsNavTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 27, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun aTurnWithItsDistance() {
        val f = GoogleMapsNav.frameOf("100 m · Turn left onto Covell Boulevard", "100 m", "Arrive 16:16", 287, 237_051, now)!!
        assertEquals(DeviceManeuver.LEFT, f.maneuver)
        assertEquals(100, f.distanceM)
        assertEquals("Covell Boulevard", f.street)
        assertEquals(237_051 - 287, f.remainingM)
        assertEquals(76 * 60, f.remainingS)
        assertEquals(true, f.active)
        assertNull(f.dest)
        assertEquals(true, f.turnOnly)
        // On the wire: active, 10 m units and turn only.
        assertEquals(0x01 or 0x04 or 0x08, DeviceNavEncoder.state(f)[1].toInt())
    }

    @Test fun theLastMetersBeforeATurnShowNoFigure() {
        val counting = GoogleMapsNav.frameOf("50 m · Turn left onto Covell Boulevard", "50 m", "Arrive 16:16", 335, 237_051, now)
        val near = GoogleMapsNav.frameOf("Turn left onto Covell Boulevard", "", "Arrive 16:16", 346, 237_051, now, counting)!!
        assertEquals(40, near.distanceM)
        // At the start of a trip there was no countdown: the step is shown as here.
        val start = GoogleMapsNav.frameOf("Head west", "", "Arrive 16:07", 2, 234_695, now)!!
        assertEquals(DeviceManeuver.CONTINUE, start.maneuver)
        assertEquals(0, start.distanceM)
        assertEquals("Head west", start.street)
    }

    @Test fun distancesInEveryUnit() {
        assertEquals(1200, GoogleMapsNav.distanceM("1.2 km"))
        assertEquals(1200, GoogleMapsNav.distanceM("1,2 km"))
        assertEquals(482, GoogleMapsNav.distanceM("0.3 mi"))
        assertEquals(152, GoogleMapsNav.distanceM("500 ft"))
        assertNull(GoogleMapsNav.distanceM("Head west"))
        assertNull(GoogleMapsNav.distanceM(null))
    }

    @Test fun theTurnIsReadFromTheWords() {
        val cases = mapOf(
            "Turn right onto Russell Boulevard" to DeviceManeuver.RIGHT,
            "Turn right at Anderson Road onto Russell Boulevard" to DeviceManeuver.RIGHT,
            "Slight left" to DeviceManeuver.SLIGHT_LEFT,
            "Sharp right onto F Street" to DeviceManeuver.SHARP_RIGHT,
            "Keep right to take the flyover" to DeviceManeuver.KEEP_RIGHT,
            "Take the exit on the left toward Sacramento" to DeviceManeuver.SLIGHT_LEFT,
            "Make a U-turn" to DeviceManeuver.UTURN,
            "At the roundabout, take the 2nd exit onto Mace Boulevard" to DeviceManeuver.ROUNDABOUT,
            "Continue straight to stay on Covell Boulevard" to DeviceManeuver.CONTINUE,
            "Merge onto I-80 E" to DeviceManeuver.CONTINUE,
            "Your destination is on the right" to DeviceManeuver.ARRIVE,
            "Arrive at the destination" to DeviceManeuver.ARRIVE,
            "Gire a la izquierda" to DeviceManeuver.CONTINUE,
        )
        cases.forEach { (words, code) -> assertEquals(words, code, GoogleMapsNav.maneuverOf(words)) }
    }

    @Test fun theStreetIsTheRoadEntered() {
        assertEquals("Russell Boulevard", GoogleMapsNav.streetOf("Turn right at Anderson Road onto Russell Boulevard"))
        assertEquals("Covell Boulevard", GoogleMapsNav.streetOf("Continue straight to stay on Covell Boulevard"))
        assertEquals("Sacramento", GoogleMapsNav.streetOf("Head east toward Sacramento"))
        assertEquals("Slight left", GoogleMapsNav.streetOf("Slight left"))
        assertEquals("Sacramento", GoogleMapsNav.streetOf("Take the exit on the left toward Sacramento"))
        assertEquals("Keep right to take the flyover", GoogleMapsNav.streetOf("Keep right to take the flyover"))
    }

    @Test fun theArrivalIsTheTimeLeft() {
        assertEquals(76 * 60, GoogleMapsNav.arrivalSeconds("Arrive 16:16", now))
        assertEquals(76 * 60, GoogleMapsNav.arrivalSeconds("Arrive 4:16 PM", now))
        // A time well past is tomorrow's; the minute under way is now.
        assertEquals((24 * 60 - 60) * 60, GoogleMapsNav.arrivalSeconds("Arrive 14:00", now))
        assertEquals(0, GoogleMapsNav.arrivalSeconds("Arrive 14:58", now))
        assertNull(GoogleMapsNav.arrivalSeconds("Fastest route", now))
    }

    @Test fun noTitleIsNoDrive() {
        assertNull(GoogleMapsNav.frameOf(null, null, null, 0, 0, now))
        assertNull(GoogleMapsNav.frameOf("  ", null, null, 0, 0, now))
    }
}
