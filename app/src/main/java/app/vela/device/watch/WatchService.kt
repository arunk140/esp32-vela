package app.vela.device.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.vela.MainActivity
import app.vela.R
import app.vela.core.data.naming.RoadNameTiles
import app.vela.core.location.LocationProvider
import app.vela.core.model.Route
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import app.vela.core.nav.RouteProjection
import app.vela.device.DeviceBridge
import app.vela.device.DeviceNavFrame
import app.vela.device.DeviceService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Shows the drive on a CMF Watch Pro 2 running the Vela Nav watch app. It holds a second GATT
 * client on the link Nothing X already has ([WatchSession]), and while the watch app is open it
 * sends the drive once a second: the turn, the road, what is left, and the route line with the
 * car on it (protocol `n1`, the watch app's `src/proto.rs`). The watch asks for everything again
 * (`n1Q`) when it opens or loses a message; the answer ends with a state flagged as a snapshot.
 *
 * Like [DeviceService] it only reads [NavSession.state] and [LocationProvider.updates].
 */
@AndroidEntryPoint
class WatchService : Service() {

    @Inject lateinit var navSession: NavSession
    @Inject lateinit var locationProvider: LocationProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var locationJob: Job? = null
    private var foreground = false

    private var lastFix: Location? = null
    private var course = 0f
    private var cumFor: Route? = null
    private var cum = DoubleArray(0)

    // what the open watch app holds
    private var seq = 0
    private var routeId = 0
    private var sentRoute: Route? = null
    private var geometry: WatchDrive.Geometry? = null
    private var sentText: String? = null
    private var wantSnapshot = true

    // the streets around the car ([WatchRoads]): the last set, and what of it is still to go
    private var roadsId = 0
    private var roadsJob: Job? = null
    private var roads: Pair<app.vela.core.model.LatLng, List<Pair<Char, ByteArray>>>? = null // route origin, parts
    private var roadsCenter: app.vela.core.model.LatLng? = null
    private val roadsQueue = ArrayDeque<Pair<Char, ByteArray>>()
    private var roadsRetryAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WatchPrefs.load(this)
        if (intent?.action == ACTION_STOP || !WatchPrefs.enabled.value || WatchPrefs.address.value == null ||
            !DeviceService.hasBluetooth(this)
        ) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!foreground) {
            try {
                startForegroundTyped(notification(state.value))
                foreground = true
            } catch (t: Throwable) {
                Log.w(TAG, "foreground start refused: ${t.javaClass.simpleName}")
                shutDown()
                return START_NOT_STICKY
            }
        }
        startLocation()
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        publish(State.OFF)
        super.onDestroy()
    }

    private fun startLocation() {
        if (locationJob != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        locationJob = scope.launch {
            locationProvider.updates().collect { loc ->
                if (loc.latitude == 0.0 && loc.longitude == 0.0) return@collect
                lastFix = loc
                course = courseFor(loc)
            }
        }
    }

    /** Connect, feed, and on any loss connect again, backing off to half a minute. */
    private suspend fun run() {
        var backoff = 2_000L
        while (scope.isActive) {
            val device = WatchPrefs.address.value?.let { a ->
                getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }
                    ?.bondedDevices?.firstOrNull { it.address == a }
            }
            if (device == null) {
                publish(State.CONNECTING)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            publish(State.CONNECTING)
            val session = WatchSession(this)
            try {
                session.open(device)
                backoff = 2_000L
                feed(session)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "watch link: ${t.javaClass.simpleName}")
            } finally {
                session.close()
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /** Runs while the link is up. Returns when it is lost. */
    private suspend fun feed(session: WatchSession) {
        var appOpen = false
        var silent = 0
        forgetWatch()
        while (session.isOpen) {
            if (!appOpen) {
                val pong = session.ping() ?: if (++silent >= 3) return else null
                if (pong != null) silent = 0
                appOpen = pong?.appOpen == true
                publish(if (appOpen) State.SHOWING else State.CONNECTED)
                if (!appOpen) {
                    forgetWatch()
                    delay(IDLE_PING_MS)
                    continue
                }
            }
            while (true) {
                val e = session.events.tryReceive().getOrNull() ?: break
                if (e.contentEquals(WatchNav.ASK)) wantSnapshot = true
            }
            when (val r = push(session)) {
                WatchSession.OK -> silent = 0
                WatchSession.NO_APP -> appOpen = false
                WatchSession.NO_REPLY -> if (++silent >= 3) return
                else -> Log.w(TAG, "watch refused a message: $r")
            }
            delay(TICK_MS)
        }
    }

    /** The watch app closed (or never opened): it holds nothing, so the next open starts over. */
    private fun forgetWatch() {
        sentRoute = null
        sentText = null
        wantSnapshot = true
        roadsQueue.clear()
    }

    private suspend fun send(session: WatchSession, build: (Int) -> ByteArray): Int {
        val r = session.send(build(seq and 0xFF))
        if (r == WatchSession.OK) seq++ // the watch counts only what reached it
        return r
    }

    /** One tick: a snapshot when asked, else what changed and the state. */
    private suspend fun push(session: WatchSession): Int {
        val s = navSession.state.value
        val frame = DeviceNavFrame.of(s)
        val snapshot = wantSnapshot
        val text = WatchDrive.line(frame, s.destinationLabel)
        if (snapshot || text != sentText) {
            val r = send(session) { WatchNav.text(it, text) }
            if (r != WatchSession.OK) return r
            sentText = text
        }
        val route = s.route?.takeIf { frame.active }
        if (snapshot || route !== sentRoute) {
            val g = route?.let { WatchDrive.geometry(it.polyline) }
            if (g != null) {
                routeId = (routeId + 1) and 0xFF
                for ((kind, body) in WatchNav.routeParts(routeId, g.points, WatchDrive.frame(g.origin))) {
                    val r = send(session) { WatchNav.msg(kind, it, body) }
                    if (r != WatchSession.OK) return r
                }
            }
            geometry = g
            sentRoute = route
            // the watch dropped its streets with the new line: the same set again if it is in this frame
            roadsQueue.clear()
            val last = roads
            if (g != null && last != null && last.first == g.origin) roadsQueue.addAll(last.second) else roadsCenter = null
        }
        maybeFindRoads()
        repeat(ROADS_PER_TICK) {
            val part = roadsQueue.firstOrNull() ?: return@repeat
            val r = send(session) { WatchNav.msg(part.first, it, part.second) }
            if (r != WatchSession.OK) return r
            roadsQueue.removeFirst()
        }
        val r = send(session) { WatchNav.state(it, stateFor(s, frame, snapshot)) }
        if (r == WatchSession.OK && snapshot) wantSnapshot = false
        return r
    }

    /**
     * A new set of streets once the car is [WatchRoads.REFRESH_M] from the last one's centre, read
     * off the main thread from the map's tiles (a downloaded region's, else the tile host's; with
     * neither the watch shows the route alone).
     */
    private fun maybeFindRoads() {
        val g = geometry ?: return
        val fix = lastFix ?: return
        if (roadsJob?.isActive == true) return
        val here = app.vela.core.model.LatLng(fix.latitude, fix.longitude)
        val center = roadsCenter
        if (center != null && roads?.first == g.origin && center.distanceTo(here) < WatchRoads.REFRESH_M) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < roadsRetryAt) return
        roadsJob = scope.launch {
            val set = withContext(Dispatchers.Default) {
                RoadNameTiles.linesAlong(WatchRoads.probe(here))?.let { WatchRoads.set(g.origin, here, it) }
            }
            if (set == null) {
                roadsRetryAt = android.os.SystemClock.elapsedRealtime() + ROADS_RETRY_MS
                return@launch
            }
            if (geometry?.origin != g.origin) return@launch // a new route meanwhile
            roadsId = (roadsId + 1) and 0xFF
            val parts = WatchRoads.parts(roadsId, set)
            roads = g.origin to parts
            roadsCenter = here
            roadsQueue.clear()
            roadsQueue.addAll(parts)
        }
    }

    private fun stateFor(s: NavSession.State, frame: DeviceNavFrame, snapshot: Boolean): WatchNav.State {
        val g = geometry
        val fix = lastFix
        val imperial = app.vela.ui.Units.imperial.value
        val kmh = DeviceBridge.speedLimitKmh.value
        val limit = if (kmh == null) 0 else (if (imperial) kmh / 1.609344 else kmh).roundToInt()
        val total = s.route?.distanceMeters ?: 0.0
        val fraction = if (total > 0) (total - s.remainingDistance) / total else 0.0
        return WatchDrive.state(
            frame = frame,
            arrived = s.arrived,
            imperial = imperial,
            clock24 = app.vela.ui.Clock24.on.value,
            fix = fix != null && fix.hasAccuracy() && fix.accuracy < FIX_ACCURACY_M,
            limit = limit,
            position = if (g != null && fix != null) {
                // on the route, the car sits on its line: a raw fix wobbles a few metres to the side
                WatchDrive.metres(g.origin, onRoute(s) ?: app.vela.core.model.LatLng(fix.latitude, fix.longitude))
            } else null,
            courseDeg = course,
            progress = if (g != null) WatchDrive.progress(g, fraction) else 0,
            snapshot = snapshot,
        )
    }

    /** Where the car is along the route while it follows it, else null. */
    private fun onRoute(s: NavSession.State): app.vela.core.model.LatLng? {
        val route = s.route ?: return null
        if (!s.navigating || s.paused || s.nav.offRoute || route.polyline.size < 2) return null
        if (cumFor !== route) {
            cumFor = route
            cum = RouteProjection.cumulative(route.polyline)
        }
        val along = (cum.last() - s.remainingDistance).coerceIn(0.0, cum.last())
        return RouteProjection.pointAt(route.polyline, cum, along)
    }

    /** As [DeviceService]: the road's own direction on a drive, else the GPS course while moving. */
    private fun courseFor(loc: Location): Float {
        val s = navSession.state.value
        val route = s.route
        if (route != null && s.navigating && !s.paused && !s.nav.offRoute && route.polyline.size >= 2) {
            if (cumFor !== route) {
                cumFor = route
                cum = RouteProjection.cumulative(route.polyline)
            }
            val along = (cum.last() - s.remainingDistance).coerceIn(0.0, cum.last())
            return RouteProjection.bearingAt(route.polyline, cum, along).toFloat()
        }
        return if (loc.hasBearing() && loc.hasSpeed() && loc.speed > MOVING_MPS) loc.bearing else course
    }

    // ---- state shown to the user ----

    private fun publish(s: State) {
        if (state.value == s) return
        state.value = s
        if (foreground) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s)) }
        }
    }

    private fun shutDown() {
        loop?.cancel()
        locationJob?.cancel()
        locationJob = null
        publish(State.OFF)
        if (foreground) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foreground = false
        stopSelf()
    }

    private fun startForegroundTyped(n: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n)
            return
        }
        val located = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (located) {
            try {
                startForeground(
                    NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
                return
            } catch (t: Throwable) {
                Log.w(TAG, "location type refused: ${t.javaClass.simpleName}")
            }
        }
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun notification(s: State): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.watch_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(getString(R.string.watch_channel_name))
            .setContentText(getString(s.label))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    enum class State(val label: Int) {
        OFF(R.string.watch_state_off),
        CONNECTING(R.string.watch_state_connecting),
        CONNECTED(R.string.watch_state_connected),
        SHOWING(R.string.watch_state_showing),
    }

    companion object {
        private const val TAG = "VelaWatch"
        private const val ACTION_STOP = "app.vela.watch.STOP"
        private const val CHANNEL_ID = "vela_watch"
        private const val NOTIF_ID = 45
        private const val TICK_MS = 1_000L
        /** Street messages sent beside each state, so a new set never holds the turn countdown up. */
        private const val ROADS_PER_TICK = 8
        private const val ROADS_RETRY_MS = 30_000L
        /** How often to look for the watch app while it is closed. */
        private const val IDLE_PING_MS = 3_000L
        private const val FIX_ACCURACY_M = 50f
        private const val MOVING_MPS = 1.5f

        val state = mutableStateOf(State.OFF)

        fun start(context: Context) {
            WatchPrefs.load(context)
            if (!WatchPrefs.enabled.value || WatchPrefs.address.value == null || !DeviceService.hasBluetooth(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
            }.onFailure { Log.w(TAG, "could not start the watch service") }
        }

        fun stop(context: Context) {
            if (state.value == State.OFF) return
            runCatching { context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
