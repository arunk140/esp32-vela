package app.vela.device

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.NavSession
import app.vela.core.nav.NavState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The bytes here are the device's contract (`ble_service.h`): a change that breaks one of these
 *  breaks a device that is already flashed. Coordinates are the Davis fixture. */
class DeviceNavTest {

    private val davis = LatLng(38.5449, -121.7405)

    private fun step(type: ManeuverType, road: String?, meters: Double, ref: String? = null) =
        Maneuver(type = type, instruction = "", location = davis, distanceMeters = meters, durationSeconds = 0.0, road = road, ref = ref)

    private fun route(vararg steps: Maneuver) = Route(
        polyline = listOf(davis, LatLng(38.5600, -121.7405), LatLng(38.5600, -121.7000)),
        legs = listOf(RouteLeg(5_000.0, 600.0, null, steps.toList())),
        distanceMeters = 5_000.0,
        durationSeconds = 600.0,
        durationInTrafficSeconds = null,
    )

    @Test fun everyManeuverTypeHasAnIcon() {
        for (t in ManeuverType.entries) {
            val code = DeviceManeuver.of(t)
            assertTrue("$t", code in DeviceManeuver.CONTINUE..DeviceManeuver.ARRIVE && code != DeviceManeuver.OFF_ROUTE)
        }
        assertEquals(DeviceManeuver.LEFT, DeviceManeuver.of(ManeuverType.TURN_LEFT))
        assertEquals(DeviceManeuver.KEEP_RIGHT, DeviceManeuver.of(ManeuverType.FORK_RIGHT))
        assertEquals(DeviceManeuver.ROUNDABOUT, DeviceManeuver.of(ManeuverType.EXIT_ROUNDABOUT))
    }

    @Test fun stateIsTwelveLittleEndianBytes() {
        val f = DeviceNavFrame(
            active = true, maneuver = DeviceManeuver.LEFT, distanceM = 250,
            afterManeuver = DeviceManeuver.RIGHT, afterDistanceM = 1200, remainingM = 4321, remainingS = 600,
        )
        assertArrayEquals(
            // 4,321 m remaining goes out as 432 units of 10 m, with the flag that says so.
            byteArrayOf(0x01, 0x05, 3, 0xFA.toByte(), 0x00, 6, 0xB0.toByte(), 0x04, 0xB0.toByte(), 0x01, 0x58, 0x02),
            DeviceNavEncoder.state(f),
        )
    }

    @Test fun longDistancesClampInsteadOfWrapping() {
        val b = ByteBuffer.wrap(DeviceNavEncoder.state(DeviceNavFrame(remainingM = 80_000, distanceM = -5, afterDistanceM = 90_000)))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, b.getShort(3).toInt() and 0xFFFF)
        assertEquals(0xFFFF, b.getShort(6).toInt() and 0xFFFF)
        // 80 km fits now that the unit is 10 m; 700 km still stops at the top of the field.
        assertEquals(8_000, b.getShort(8).toInt() and 0xFFFF)
        val far = ByteBuffer.wrap(DeviceNavEncoder.state(DeviceNavFrame(remainingM = 700_000))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xFFFF, far.getShort(8).toInt() and 0xFFFF)
    }

    @Test fun textIsCutOnACharacterBoundary() {
        val t = DeviceNavEncoder.text("é".repeat(40)) // two bytes each
        assertEquals(0x02, t[0].toInt())
        assertEquals(48, t[1].toInt())
        assertEquals(50, t.size)
        assertEquals("é".repeat(24), String(t, 2, 48, Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x02, 0), DeviceNavEncoder.text(""))
    }

    @Test fun destinationAndItsAbsence() {
        val d = ByteBuffer.wrap(DeviceNavEncoder.dest(385449000 to -1217405000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10, d.capacity())
        assertEquals(0x03, d.get(0).toInt())
        assertEquals(1, d.get(1).toInt())
        assertEquals(385449000, d.getInt(2))
        assertEquals(-1217405000, d.getInt(6))
        assertArrayEquals(ByteArray(10).also { it[0] = 0x03 }, DeviceNavEncoder.dest(null))
    }

    @Test fun positionIsEighteenBytes() {
        val p = ByteBuffer.wrap(
            DeviceNavEncoder.position(davis.lat, davis.lng, speedMps = 13.5f, courseDeg = 359.999f, fix = true, nowMs = 0x1_0000_0005L),
        ).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(18, p.capacity())
        assertEquals(385449000, p.getInt(0))
        assertEquals(-1217405000, p.getInt(4))
        assertEquals(1350, p.getShort(8).toInt())
        assertEquals(35_999, p.getShort(10).toInt() and 0xFFFF)
        assertEquals(1, p.get(13).toInt())
        assertEquals(5, p.getInt(14))
    }

    @Test fun aLongLineFitsTheDeviceAndKeepsItsEnds() {
        // A wiggling line north out of Davis: nothing a straight segment could stand in for.
        val line = List(5_000) { i -> LatLng(38.54 + i * 0.0001, -121.74 + (if (i % 2 == 0) 0.0 else 0.02)) }
        val thin = RouteSimplify.forDisplay(line, DeviceNavEncoder.ROUTE_MAX_POINTS)
        assertTrue(thin.size in 2..DeviceNavEncoder.ROUTE_MAX_POINTS)
        assertEquals(line.first(), thin.first())
        assertEquals(line.last(), thin.last())

        val chunks = DeviceNavEncoder.routeChunks(line)
        var points = 0
        chunks.forEachIndexed { i, c ->
            assertEquals(0x04, c[0].toInt())
            assertEquals(i, c[2].toInt() and 0xFF)
            val n = c[3].toInt()
            assertTrue(n in 1..DeviceNavEncoder.ROUTE_CHUNK)
            assertEquals(4 + n * 8, c.size)
            assertEquals("start flag", i == 0, c[1].toInt() and 0x01 != 0)
            assertEquals("end flag", i == chunks.lastIndex, c[1].toInt() and 0x02 != 0)
            points += n
        }
        assertEquals(thin.size, points)
    }

    @Test fun trafficIsSentAsFractionsOfTheRoute() {
        val r = route(step(ManeuverType.DEPART, "Russell Blvd", 5_000.0)).copy(
            trafficSpans = listOf(
                app.vela.core.model.TrafficSpan(level = 2, startMeters = 2_500.0, lengthMeters = 1_250.0),
                app.vela.core.model.TrafficSpan(level = 1, startMeters = 0.0, lengthMeters = 500.0),
                app.vela.core.model.TrafficSpan(level = 7, startMeters = 4_900.0, lengthMeters = 500.0),
            ),
        )
        val t = ByteBuffer.wrap(DeviceNavEncoder.traffic(r)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2 + 3 * 5, t.capacity())
        assertEquals(0x08, t.get(0).toInt())
        assertEquals(3, t.get(1).toInt())
        // In order along the route: the first tenth, the stretch from half to three quarters,
        // and one that runs past the end, which stops at it. A level above 3 is sent as 3.
        assertEquals(0, t.getShort(2).toInt() and 0xFFFF)
        assertEquals(6553, t.getShort(4).toInt() and 0xFFFF)
        assertEquals(1, t.get(6).toInt())
        assertEquals(32767, t.getShort(7).toInt() and 0xFFFF)
        assertEquals(49151, t.getShort(9).toInt() and 0xFFFF)
        assertEquals(2, t.get(11).toInt())
        assertEquals(65535, t.getShort(14).toInt() and 0xFFFF)
        assertEquals(3, t.get(16).toInt())

        assertArrayEquals(byteArrayOf(0x08, 0), DeviceNavEncoder.traffic(null))
        assertArrayEquals(byteArrayOf(0x08, 0), DeviceNavEncoder.traffic(route(step(ManeuverType.DEPART, null, 1.0))))
    }

    @Test fun phoneBatteryAndClock() {
        val p = ByteBuffer.wrap(DeviceNavEncoder.phone(50, charging = true, epochS = 1_791_000_000L, utcOffsetMin = -420))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, p.capacity())
        assertEquals(0x07, p.get(0).toInt())
        assertEquals(50, p.get(1).toInt())
        assertEquals(1, p.get(2).toInt())
        assertEquals(1_791_000_000, p.getInt(4))
        assertEquals(-420, p.getShort(8).toInt())
        assertEquals(0xFF, DeviceNavEncoder.phone(null, false, 0, 0)[1].toInt() and 0xFF)
    }

    @Test fun limitCarriesItsUnits() {
        assertArrayEquals(byteArrayOf(0x05, 0, 100, 0), DeviceNavEncoder.limit(imperial = false, limit = 100))
        assertArrayEquals(byteArrayOf(0x05, 1, 65, 0), DeviceNavEncoder.limit(imperial = true, limit = 65))
        assertArrayEquals(byteArrayOf(0x05, 0, 0, 0), DeviceNavEncoder.limit(imperial = false, limit = 0))
    }

    @Test fun streetsArePackedAroundAnOrigin() {
        fun line(cls: String, vararg p: LatLng) = app.vela.core.data.naming.NamedLine("x", null, cls, p.toList())
        val near = line("minor", davis, LatLng(davis.lat, davis.lng + 0.001))
        val far = line("primary", LatLng(davis.lat + 0.5, davis.lng), LatLng(davis.lat + 0.6, davis.lng))
        val path = line("path", davis, LatLng(davis.lat + 0.001, davis.lng))
        val long = line("primary", *Array(150) { LatLng(davis.lat - 0.004 + it * 0.00005, davis.lng + (if (it % 2 == 0) 0.0 else 0.001)) })

        val chunks = DeviceRoads.chunks(davis, listOf(near, far, path, long))
        val first = ByteBuffer.wrap(chunks[0]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x06, first.get(0).toInt())
        assertEquals(385449000, first.getInt(4))
        assertEquals(-1217405000, first.getInt(8))
        var lines = 0
        var points = 0
        var majors = 0
        chunks.forEachIndexed { i, c ->
            assertTrue(c.size <= 252)
            assertEquals(i, c[2].toInt())
            assertEquals("start flag", i == 0, c[1].toInt() and 0x01 != 0)
            assertEquals("end flag", i == chunks.lastIndex, c[1].toInt() and 0x02 != 0)
            var o = if (i == 0) 12 else 4
            while (o < c.size) {
                val n = c[o].toInt()
                assertTrue(n in 2..60)
                majors += c[o + 1].toInt()
                lines++
                points += n
                o += 2 + n * 4
            }
            assertEquals(c.size, o)
        }
        // The near street whole, the long one in three pieces, nothing far away and no path.
        assertEquals(4, lines)
        assertEquals(3, majors)
        assertEquals(2 + 150 + 2, points)
        // The near street runs from the origin to 100 units of 1e-5 degrees east of it.
        val b = ByteBuffer.wrap(chunks[0]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, b.get(12).toInt())
        assertEquals(0, b.get(13).toInt())
        assertEquals(0, b.getShort(14).toInt())
        assertEquals(0, b.getShort(16).toInt())
        assertEquals(0, b.getShort(18).toInt())
        assertEquals(100, b.getShort(20).toInt())

        assertArrayEquals(
            byteArrayOf(0x06, 0x03, 0, 0) + chunks[0].copyOfRange(4, 12),
            DeviceRoads.chunks(davis, emptyList()).single(),
        )
    }

    @Test fun noLineIsOneClearingChunk() {
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0, 0), DeviceNavEncoder.routeChunks(emptyList()).single())
    }

    @Test fun frameFollowsTheSession() {
        val r = route(
            step(ManeuverType.DEPART, "Russell Blvd", 800.0),
            step(ManeuverType.TURN_RIGHT, "Anderson Rd", 1_500.0),
            step(ManeuverType.RAMP_LEFT, null, 2_000.0, ref = "CA 113"),
            step(ManeuverType.ARRIVE, null, 0.0),
        )
        assertEquals(DeviceNavFrame.IDLE, DeviceNavFrame.of(NavSession.State()))
        assertEquals(DeviceNavFrame.IDLE, DeviceNavFrame.of(NavSession.State(route = r)))

        val driving = NavSession.State(
            navigating = true, route = r, nav = NavState(stepIndex = 1, distanceToNextManeuver = 320.4),
            remainingDistance = 3_800.0, remainingDuration = 410.0,
        )
        val f = DeviceNavFrame.of(driving)
        assertTrue(f.active)
        assertEquals(DeviceManeuver.RIGHT, f.maneuver)
        assertEquals(320, f.distanceM)
        assertEquals("Anderson Rd", f.street)
        assertEquals(DeviceManeuver.SLIGHT_LEFT, f.afterManeuver)
        assertEquals(1_500, f.afterDistanceM)
        assertEquals(3_800, f.remainingM)
        assertEquals(410, f.remainingS)
        assertEquals(r.polyline.last(), f.dest)

        // A road with no name falls back to its number.
        assertEquals("CA 113", DeviceNavFrame.of(driving.copy(nav = NavState(stepIndex = 2))).street)
        assertEquals(DeviceManeuver.OFF_ROUTE, DeviceNavFrame.of(driving.copy(nav = NavState(stepIndex = 1, offRoute = true))).maneuver)

        // The device clears its line on any inactive frame, so a paused drive is no drive.
        assertEquals(DeviceNavFrame.IDLE, DeviceNavFrame.of(driving.copy(paused = true)))

        val arrived = DeviceNavFrame.of(driving.copy(navigating = false, arrived = true))
        assertTrue(arrived.active)
        assertEquals(DeviceManeuver.ARRIVE, arrived.maneuver)
        assertNull(DeviceNavFrame.of(driving.copy(navigating = false)).dest)
    }

    @Test fun encoderRepeatsStateAndSendsTheRestOnChange() {
        val writes = ArrayList<Pair<Int, String?>>()
        var ready = false
        val enc = DeviceNavEncoder(object : DeviceNavEncoder.Out {
            override fun navWrite(data: ByteArray, tag: String?) { writes += data[0].toInt() to tag }
            override fun navReady() = ready
        })
        val f = DeviceNavFrame(active = true, street = "Russell Blvd", dest = davis)
        enc.send(f)
        assertTrue(writes.isEmpty())
        ready = true
        enc.send(f)
        assertEquals(listOf(1 to "navst", 2 to "navtx", 3 to "navdest"), writes)
        writes.clear()
        enc.send(f)
        assertEquals(listOf<Pair<Int, String?>>(1 to "navst"), writes)
        writes.clear()
        enc.send(DeviceNavFrame.IDLE)
        assertEquals(listOf(1 to "navst", 2 to "navtx", 3 to "navdest"), writes)
        writes.clear()
        enc.reset()
        enc.send(DeviceNavFrame.IDLE)
        assertEquals(listOf(1 to "navst", 2 to "navtx", 3 to "navdest"), writes)
    }

    @Test fun onlyADeviceQrPairs() {
        val bond = DeviceQr.parse("""{"n":"NAV-1A2B","m":"aa:bb:cc:dd:ee:ff","s":"e8dffd56-e8b9-4279-9bff-1e0d2220d776","t":"012345"}""")
        assertEquals(DeviceLinkPrefs.Bond("AA:BB:CC:DD:EE:FF", "NAV-1A2B", "012345"), bond)
        assertNull(DeviceQr.parse("https://example.com"))
        assertNull(DeviceQr.parse("""{"m":"not an address","t":"012345"}"""))
        assertNull(DeviceQr.parse("""{"m":"AA:BB:CC:DD:EE:FF","t":"abc"}"""))
    }
}
