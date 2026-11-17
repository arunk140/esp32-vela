package app.vela.device.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vectors from the cmf-watch-sdk Python host (`wp2apps.link.frame`), key 00 01 … 0f. */
class WatchFramesTest {
    private val k = ByteArray(16) { it.toByte() }
    private fun hex(s: String) = WatchFrames.hex(s)

    @Test
    fun framesMatchThePythonHost() {
        assertArrayEquals(
            hex("f50010ffff0001000190a7d7a03ccf08ff2d1d1569f9684f3d6223"),
            WatchFrames.frame(WatchFrames.PING, byteArrayOf(0), k),
        )
        assertArrayEquals(
            hex("f50010ffff0001000190a56bc6b3689c06b5210bd489537178127f"),
            WatchFrames.frame(WatchFrames.MSG, byteArrayOf(0xFF.toByte()) + WatchNav.ASK, k),
        )
    }

    @Test
    fun repliesAreParsedAcrossNotificationsAndOpened() {
        val pong = hex("f50010ffff00010001b0a799fa4e06d749eef057c9681ca82f52fc")
        val event = hex("f50010ffff00010001b0a63128c5675a82848f4fa1cd7b1568493d")
        val p = WatchFrames.Parser()
        val stream = byteArrayOf(0x00) + pong + event
        val got = (0 until stream.size step 20).flatMap { p.feed(stream.copyOfRange(it, minOf(it + 20, stream.size))) }
        assertEquals(listOf(0xB0A7, 0xB0A6), got.map { it.c2 })
        assertArrayEquals(byteArrayOf(0, 2, 1), WatchFrames.decrypt(k, got[0].body))
        assertArrayEquals(byteArrayOf(7, 2) + WatchNav.ASK, WatchFrames.decrypt(k, got[1].body))
        // another host's frame (another key) does not open
        assertNull(WatchFrames.decrypt(ByteArray(16), got[0].body))
    }

    @Test
    fun navMessagesFitAndCarryTheFields() {
        val s = WatchNav.state(
            7,
            WatchNav.State(
                flags = WatchNav.F_ACTIVE, maneuver = 3, distM = 350, remainingM = 8200, remainingS = 840,
                limit = 50, x = -12, y = 40000, course = 16384, progress = 7,
            ),
        )
        assertEquals(28, s.size)
        assertArrayEquals(hex("6e3153070103" + "5e01" + "00" + "0000" + "3403" + "4803" + "32" + "f4ffffff" + "409c0000" + "0040" + "0700"), s)
        val pts = (0 until 100).map { intArrayOf(it * 37 - 2000, it * it * 9) }
        val r = WatchNav.route(0, 3, pts)
        assertEquals(1 + 4 + 1, r.size)
        r.forEach { assert(it.size <= 128) }
        // "Straße" cut on a character boundary
        val t = WatchNav.text(0, "a".repeat(99) + "ß")
        assertEquals(4 + 99, t.size)
    }
}
