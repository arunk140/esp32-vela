package app.vela.device.watch

import app.vela.core.model.LatLng
import app.vela.device.DeviceManeuver
import app.vela.device.DeviceNavFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WatchDriveTest {
    // a drive in Bengaluru: ~1 km north, then ~1 km east
    private val start = LatLng(12.9000, 77.6000)
    private val line = (0..100).map { LatLng(12.9000 + it * 0.00009, 77.6000) } +
        (1..100).map { LatLng(12.9090, 77.6000 + it * 0.0000922) }

    @Test
    fun metresFromTheFirstPoint() {
        val m = WatchDrive.metres(start, LatLng(12.9090, 77.6092))
        assertTrue("east ${m[0]}", abs(m[0] - 998) < 5)
        assertTrue("north ${m[1]}", abs(m[1] - 995) < 5)
    }

    @Test
    fun geometryFitsTheWatch() {
        val g = WatchDrive.geometry(line)!!
        assertEquals(start, g.origin)
        // RouteSimplify thins only a line longer than the watch's buffer
        assertEquals(201, g.points.size)
        assertTrue(abs(g.cum.last() - 1993) < 10)
        // every message of the line fits the channel
        WatchNav.route(0, 1, g.points).forEach { assertTrue(it.size <= 128) }
    }

    @Test
    fun aRouteIsTaggedWithItsFrame() {
        val f = WatchDrive.frame(start)
        assertEquals(f, WatchDrive.frame(LatLng(12.9000, 77.6000)))
        assertTrue(f != WatchDrive.frame(LatLng(12.9001, 77.6000)))
        val b = WatchNav.routeParts(3, listOf(intArrayOf(0, 0), intArrayOf(5, 5)), f).first()
        assertEquals('B', b.first)
        assertEquals(6, b.second.size)
        assertEquals(f, (b.second[4].toInt() and 0xFF) or ((b.second[5].toInt() and 0xFF) shl 8))
        assertEquals(4, WatchNav.routeParts(3, listOf(intArrayOf(0, 0), intArrayOf(5, 5))).first().second.size)
    }

    @Test
    fun theWatchAsksForHomeOrWorkOnly() {
        fun m(vararg b: Int) = b.map { it.toByte() }.toByteArray()
        assertEquals(WatchNav.P_HOME, WatchNav.action(m('n'.code, '1'.code, 'A'.code, 1)))
        assertEquals(WatchNav.P_WORK, WatchNav.action(m('n'.code, '1'.code, 'A'.code, 2)))
        assertEquals(null, WatchNav.action(m('n'.code, '1'.code, 'A'.code, 3)))
        assertEquals(null, WatchNav.action(m('n'.code, '1'.code, 'A'.code, 1, 0)))
        assertEquals(null, WatchNav.action(WatchNav.ASK))
        assertArrayEquals(m('n'.code, '1'.code, 'P'.code, 9, 3, 1), WatchNav.places(9, 3, WatchNav.START_BUSY))
    }

    @Test
    fun aLongRouteIsCappedAt512() {
        val wiggle = (0 until 5000).map { LatLng(12.9 + it * 0.0001, 77.6 + (if (it % 2 == 0) 0.0 else 0.0003)) }
        val g = WatchDrive.geometry(wiggle)!!
        assertTrue(g.points.size <= WatchDrive.ROUTE_MAX)
        assertTrue(WatchNav.route(0, 1, g.points).size <= 1 + 18 + 1)
    }

    @Test
    fun progressFollowsTheShareOfTheWay() {
        val g = WatchDrive.geometry(line)!!
        assertEquals(1, WatchDrive.progress(g, 0.0))
        assertTrue(WatchDrive.progress(g, 0.3) in 59..62) // ~600 m up the first leg, 10 m a point
        assertTrue(WatchDrive.progress(g, 0.7) in 139..142) // ~400 m along the second leg
        assertEquals(201, WatchDrive.progress(g, 1.0))
    }

    @Test
    fun theDestinationNameIsNeverSent() {
        val last = DeviceNavFrame(active = true, maneuver = DeviceManeuver.RIGHT, street = "Mum's place")
        assertEquals("", WatchDrive.line(last, "Mum's place"))
        val arrive = DeviceNavFrame(active = true, maneuver = DeviceManeuver.ARRIVE, street = "Hosur Road")
        assertEquals("", WatchDrive.line(arrive, "Mum's place"))
        val road = DeviceNavFrame(active = true, maneuver = DeviceManeuver.LEFT, street = "Hosur Road")
        assertEquals("Hosur Road", WatchDrive.line(road, "Mum's place"))
    }

    @Test
    fun stateFlagsAndCourse() {
        val f = DeviceNavFrame(active = true, maneuver = DeviceManeuver.LEFT, distanceM = 120, remainingM = 5000, remainingS = 600)
        val s = WatchDrive.state(f, arrived = false, imperial = true, clock24 = false, fix = true, limit = 30,
            position = intArrayOf(5, -7), courseDeg = 90f, progress = 4, snapshot = true)
        assertEquals(WatchNav.F_ACTIVE or WatchNav.F_IMPERIAL or WatchNav.F_FIX or WatchNav.F_SNAPSHOT, s.flags)
        assertEquals(16384, s.course)
        // no position: no fix flag, and the car sits at the origin
        val n = WatchDrive.state(f, false, false, true, true, 0, null, 0f, 0, false)
        assertEquals(WatchNav.F_ACTIVE or WatchNav.F_CLOCK24, n.flags)
    }
}
