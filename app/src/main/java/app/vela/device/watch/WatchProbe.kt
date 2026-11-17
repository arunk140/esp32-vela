package app.vela.device.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A step-by-step test of the CMF watch's app channel from this phone, next to Nothing X, on the
 * same [WatchSession] the drive goes over. [demoDrive] then sends a short made-up drive to the
 * Vela Nav watch app if it is open. Only for the test section of Settings > Nav device; the log
 * shows no key, address or position.
 */
@SuppressLint("MissingPermission")
object WatchProbe {
    val log = mutableStateListOf<String>()
    val running = mutableStateOf(false)
    val linked = mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: WatchSession? = null
    private lateinit var appContext: Context
    private var seq = 0

    private fun say(s: String) {
        scope.launch { log += s }
    }

    private fun manager() = appContext.getSystemService(BluetoothManager::class.java)

    private fun systemLinked(d: BluetoothDevice): Boolean =
        manager().getConnectionState(d, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED

    /** A bonded device the test can be pointed at. */
    class Candidate(val address: String, val name: String, val connected: Boolean)

    /** Bonded devices, the ones connected right now first, then CMF-named ones. */
    fun candidates(context: Context): List<Candidate> {
        appContext = context.applicationContext
        val adapter = manager().adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { Candidate(it.address, it.name ?: "?", systemLinked(it)) }
            .sortedWith(compareByDescending<Candidate> { it.connected }.thenByDescending { "CMF" in it.name.uppercase() })
    }

    fun linkTest(context: Context) {
        if (running.value) return
        appContext = context.applicationContext
        running.value = true
        log.clear()
        scope.launch {
            try {
                runLinkTest()
            } catch (e: Throwable) {
                say("✗ ${e.message ?: e.javaClass.simpleName}")
                close()
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun runLinkTest() {
        close()
        WatchPrefs.load(appContext)
        val adapter = manager().adapter ?: error("no Bluetooth on this phone")
        if (!adapter.isEnabled) error("Bluetooth is off")
        val address = WatchPrefs.address.value ?: error("pick the watch first")
        val watch = adapter.bondedDevices.orEmpty().firstOrNull { it.address == address }
            ?: error("the picked watch is no longer bonded: pick it again")
        say("✓ 1. bonded watch: ${watch.name}")
        say(if (systemLinked(watch)) "✓ 2. the watch is connected (Nothing X holds the link)"
            else "• 2. the watch is not connected now: Vela will open the link itself")
        val s = WatchSession(appContext, ::say)
        session = s
        s.onDrop = {
            say("✗ link dropped")
            scope.launch { linked.value = false }
        }
        s.open(watch)
        val pong = s.ping()
            ?: error("7. no PING reply: stock firmware, or a stub older than 2026-11-13 (no channel key)")
        linked.value = true
        say("✓ 7. patched firmware answered the PING")
        say(
            when {
                pong.slot == 0xFF -> "• no watch app is open. Open Vela Nav on the watch, then Send demo drive"
                pong.flags and 2 != 0 -> "• an app runs in slot ${pong.slot} but is paused (a popup covers it)"
                pong.flags and 1 == 0 -> "• the app in slot ${pong.slot} is too old for messages (ABI 1.0)"
                else -> "✓ an app is open in slot ${pong.slot}: try Send demo drive"
            },
        )
        say(if (systemLinked(watch)) "✓ Nothing X's link is still up" else "• the system link is down")
    }

    /** A made-up 40 s drive for the open Vela Nav app: route, then the car moving along it. */
    fun demoDrive() {
        if (running.value || !linked.value) return
        running.value = true
        scope.launch {
            try {
                runDemo()
            } catch (e: Throwable) {
                say("✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun send(data: ByteArray): Boolean {
        val s = session ?: error("not linked")
        return when (s.send(data)) {
            WatchSession.OK -> true
            WatchSession.NO_APP -> error("Vela Nav is not open on the watch")
            WatchSession.NO_REPLY -> error("no reply to a message")
            else -> false
        }
    }

    private suspend fun runDemo() {
        // north 600 m, right 400 m, a slight left for 350 m: a route in metres from the drive's origin
        val pts = (0..30).map { intArrayOf(0, it * 20) } +
            (1..20).map { intArrayOf(it * 20, 600) } +
            (1..25).map { intArrayOf(400 + it * 10, 600 + it * 10) }
        fun along(d: Int): IntArray {
            var left = d
            for (i in 1 until pts.size) {
                val (a, b) = pts[i - 1] to pts[i]
                val len = kotlin.math.hypot((b[0] - a[0]).toDouble(), (b[1] - a[1]).toDouble()).toInt()
                if (left <= len) {
                    val dx = b[0] - a[0]
                    val dy = b[1] - a[1]
                    val course = (Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble())) / 360 * 65536).toInt()
                    return intArrayOf(a[0] + dx * left / len, a[1] + dy * left / len, course and 0xFFFF, i)
                }
                left -= len
            }
            return intArrayOf(pts.last()[0], pts.last()[1], 8192, pts.size)
        }
        val total = 600 + 400 + 354
        val t0 = System.nanoTime()
        var ok = 0
        val first = mutableListOf(WatchNav.text(seq++, "Demo Road"))
        first += WatchNav.route(seq, 1, pts).also { seq += it.size }
        for (m in first) if (send(m)) ok++
        say("✓ route sent: $ok messages in ${(System.nanoTime() - t0) / 1_000_000} ms")
        var street = "Demo Road"
        for (s in 0..40) {
            val d = (s * 35).coerceAtMost(total)
            val p = along(d)
            val arrived = d >= total
            val (man, next) = when {
                arrived -> 13 to 0
                d < 600 -> 6 to 600 - d
                d < 1000 -> 2 to 1000 - d
                else -> 13 to total - d
            }
            val name = when {
                arrived -> "Destination"
                d < 600 -> "Demo Road"
                else -> "Second Street"
            }
            if (name != street) {
                send(WatchNav.text(seq++, name))
                street = name
            }
            val flags = WatchNav.F_ACTIVE or WatchNav.F_FIX or WatchNav.F_CLOCK24 or
                (if (s == 0) WatchNav.F_SNAPSHOT else 0) or (if (arrived) WatchNav.F_ARRIVED else 0)
            send(
                WatchNav.state(
                    seq++,
                    WatchNav.State(
                        flags = flags, maneuver = man, distM = next, remainingM = total - d,
                        remainingS = (total - d) / 10, limit = if (d < 600) 50 else 30,
                        x = p[0], y = p[1], course = p[2], progress = p[3],
                    ),
                ),
            )
            if (s == 0) say("• driving: watch the watch")
            while (true) {
                val e = session?.events?.tryReceive()?.getOrNull() ?: break
                say(if (e.contentEquals(WatchNav.ASK)) "← app message: n1Q (the app asks for a snapshot)" else "← app message: ${e.size} B")
            }
            if (arrived) break
            delay(1000)
        }
        say("✓ demo drive done")
    }

    fun close() {
        session?.close()
        session = null
        linked.value = false
    }
}
