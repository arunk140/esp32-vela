package app.vela.device.watch

import app.vela.core.data.naming.NamedLine
import app.vela.core.model.LatLng
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class WatchRoadsTest {
    private val origin = LatLng(12.9000, 77.6000) // the route's first point
    private val here = LatLng(12.9045, 77.6000) // the car, ~500 m north of it

    private fun street(cls: String, vararg p: Pair<Double, Double>) =
        NamedLine("x", null, cls, p.map { LatLng(it.first, it.second) })

    @Test
    fun onlyStreetsInTheBoxInTheRoutesFrame() {
        val lines = listOf(
            street("minor", 12.9045 to 77.5990, 12.9045 to 77.6010), // across the car
            street("primary", 12.9000 to 77.6000, 12.9090 to 77.6000), // the road the route drives
            street("path", 12.9045 to 77.5990, 12.9046 to 77.6010), // a footpath: left out
            street("minor", 12.9500 to 77.6000, 12.9510 to 77.6000), // 5 km away: left out
        )
        val set = WatchRoads.set(origin, here, lines)
        assertEquals(2, set.lines.size)
        // the set's origin is the car in the route's frame, ~500 m north
        assertTrue("${set.origin.toList()}", abs(set.origin[0]) <= 1 && abs(set.origin[1] - 498) < 5)
        // nearest first: the street across the car, its points metres from the car
        val first = set.lines[0]
        assertTrue(!first.major && first.pts.size == 2)
        assertTrue(first.pts.all { abs(it[1]) <= 1 && abs(abs(it[0]) - 108) < 5 })
        assertTrue(set.lines[1].major)
    }

    @Test
    fun aLongStreetIsCutIntoRecordsThatShareEnds() {
        // a zigzag of 100 points that thinning keeps
        val zig = (0 until 100).map { i -> 12.9040 + i * 0.00001 to 77.6000 + if (i % 2 == 0) 0.0 else 0.0002 }
        val set = WatchRoads.set(origin, here, listOf(street("tertiary", *zig.toTypedArray())))
        assertTrue(set.lines.all { it.pts.size in 2..WatchRoads.LINE_MAX })
        for (k in 1 until set.lines.size) assertArrayEquals(set.lines[k - 1].pts.last(), set.lines[k].pts.first())
    }

    @Test
    fun theWatchsBudgetHoldsAndEveryMessageFits() {
        // a dense grid: 60 streets each way, 2 km across
        val lines = (0 until 60).flatMap { k ->
            val d = -0.009 + k * 0.0003
            listOf(
                street("minor", *(0..40).map { 12.9045 + d to 77.591 + it * 0.00045 }.toTypedArray()),
                street("minor", *(0..40).map { 12.8955 + it * 0.00045 to 77.6 + d }.toTypedArray()),
            )
        }
        val set = WatchRoads.set(origin, here, lines)
        assertTrue(set.points <= WatchRoads.MAX_POINTS && set.lines.size <= WatchRoads.MAX_LINES)
        val parts = WatchRoads.parts(9, set)
        assertEquals('G', parts.first().first)
        assertEquals('J', parts.last().first)
        parts.forEach { (k, b) -> assertTrue(WatchNav.msg(k, 0, b).size <= 128) }
        // the end counts what was sent
        val end = ByteBuffer.wrap(parts.last().second).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(9, end.get().toInt())
        assertEquals(set.lines.size, end.short.toInt())
        // records parse back to the lines, in order
        var n = 0
        for ((k, b) in parts) {
            if (k != 'H') continue
            var at = 1
            while (at < b.size) {
                val len = b[at].toInt()
                assertEquals(set.lines[n].pts.size, len)
                at += 2 + 4 * len
                n++
            }
        }
        assertEquals(set.lines.size, n)
    }
}
