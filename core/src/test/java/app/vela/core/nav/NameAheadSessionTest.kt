package app.vela.core.nav

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.vela.core.data.MapDataSource
import app.vela.core.diag.DiagLog
import app.vela.core.feedback.Haptics
import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.voice.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** A long route named only at its head is named on during the drive (NavSession.maybeNameAhead). Davis fixture. */
class NameAheadSessionTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double) = LatLng(lat, lng0 + eastM / mPerDegLng)

    private val poly = (0..300).map { at(it * 10.0) } // 3 km east
    private val dest = poly.last()

    private fun route(steps: List<Maneuver>, namedToM: Double?) = Route(
        poly, listOf(RouteLeg(3000.0, 300.0, 330.0, steps)), 3000.0, 300.0, 330.0,
        source = RouteSource.GOOGLE_LINE_NAMED, namedToM = namedToM,
    )

    private val head = route(listOf(
        Maneuver(ManeuverType.DEPART, "Head east on Covell Boulevard", poly.first(), 2500.0, 250.0),
        Maneuver(ManeuverType.KEEP_LEFT, "Keep left", at(2500.0), 500.0, 50.0),
        Maneuver(ManeuverType.ARRIVE, "Arrive", dest, 0.0, 0.0),
    ), namedToM = 1000.0)

    private val whole = route(listOf(
        Maneuver(ManeuverType.DEPART, "Head east on Covell Boulevard", poly.first(), 1500.0, 150.0),
        Maneuver(ManeuverType.STRAIGHT, "Continue onto Russell Boulevard", at(1500.0), 1000.0, 100.0),
        Maneuver(ManeuverType.KEEP_LEFT, "Keep left", at(2500.0), 500.0, 50.0),
        Maneuver(ManeuverType.ARRIVE, "Arrive", dest, 0.0, 0.0),
    ), namedToM = null)

    private object NoPrefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun `the stretch ahead is named during the drive and swapped in without losing the place`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val asks = AtomicInteger()
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        val data = java.lang.reflect.Proxy.newProxyInstance(MapDataSource::class.java.classLoader, arrayOf(MapDataSource::class.java)) { _, m, args ->
            if (m.name != "nameAhead") throw AssertionError("unexpected fetch: ${m.name}")
            asks.incrementAndGet()
            if (args[0] === head) whole else null
        } as MapDataSource
        val s = NavSession(data, VoiceGuide(ctx).apply { muted = true }, Haptics(ctx), DiagLog(ctx)).apply { liveRechecks = false; replayMode = false }
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        s.onNote = { notes += it }
        s.start(head, dest, "Davis Depot")
        for (x in 0..10) s.onLocation(at(x * 20.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
        val deadline = System.currentTimeMillis() + 3000
        while (s.state.value.route !== whole && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$notes", s.state.value.route === whole)
        val before = s.state.value.nav.traveledM
        assertTrue("progress kept: $before", before > 150.0)
        // The drive goes on along the named route: the next step is the new one.
        for (x in 11..20) s.onLocation(at(x * 20.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
        assertEquals(1, s.state.value.nav.stepIndex)
        assertTrue(s.state.value.nav.traveledM >= before)
        // Named whole: nothing more is asked.
        assertEquals(1, asks.get())
        s.stop()
    }
}
