package app.vela.core

import app.vela.core.data.google.DirectionsPb
import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google's two-wheeler mode: mode 9 and `!8b1` in the `!20m` route-options block, as its web
 *  client sends them (captured 2026-11-23). */
class DirectionsPbTwoWheelerTest {
    private val car = "!20m5!1e{MODE}!2e3!5e2!6b1!14b1!46m1"

    @Test fun `the flag goes in by field number and the count grows`() {
        val out = DirectionsPb.withTwoWheeler(DirectionsPb.DEFAULT_TEMPLATE, DirectionsPb.DEFAULT_TWO_WHEELER)!!
        assertTrue(out.contains("!20m6!1e9!2e3!5e2!6b1!8b1!14b1!46m1"))
        assertFalse(out.contains(car))
        // Nothing else moves.
        assertEquals(DirectionsPb.DEFAULT_TEMPLATE.length + "!8b1".length - "{MODE}".length + 1, out.length)
    }

    @Test fun `a field already there is rewritten, a later one is appended`() {
        val t = "!3m1!20m3!1e{MODE}!2e3!8b0!46m1!1b0"
        assertEquals("!3m1!20m3!1e9!2e3!8b1!46m1!1b0", DirectionsPb.withTwoWheeler(t, "!1e9!8b1"))
        assertEquals("!3m1!20m4!1e9!2e3!8b0!9b1!46m1!1b0", DirectionsPb.withTwoWheeler(t, "!1e9!9b1"))
    }

    @Test fun `no block or no mode means no request`() {
        assertNull(DirectionsPb.withTwoWheeler("!1m4!3m2", DirectionsPb.DEFAULT_TWO_WHEELER))
        assertNull(DirectionsPb.withTwoWheeler(DirectionsPb.DEFAULT_TEMPLATE, ""))
        assertNull(DirectionsPb.withTwoWheeler(DirectionsPb.DEFAULT_TEMPLATE, "!8b1"))
        assertFalse(DirectionsPb.twoWheelerSupported(DirectionsPb.DEFAULT_TEMPLATE, ""))
    }

    @Test fun `build asks for it on a drive only`() {
        val a = LatLng(19.0596, 72.8295); val b = LatLng(19.0176, 72.8172)
        val moto = DirectionsPb.build(a, b, TravelMode.DRIVE, twoWheeler = DirectionsPb.DEFAULT_TWO_WHEELER)
        assertTrue(moto.contains("!20m6!1e9!2e3!5e2!6b1!8b1!14b1"))
        val drive = DirectionsPb.build(a, b, TravelMode.DRIVE)
        assertTrue(drive.contains("!20m5!1e0!2e3!5e2!6b1!14b1"))
        val walk = DirectionsPb.build(a, b, TravelMode.WALK, twoWheeler = DirectionsPb.DEFAULT_TWO_WHEELER)
        assertTrue(walk.contains("!20m5!1e2!2e3"))
    }
}
