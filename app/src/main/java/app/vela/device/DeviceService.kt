package app.vela.device

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.vela.MainActivity
import app.vela.R
import app.vela.core.location.LocationProvider
import app.vela.core.model.Route
import app.vela.core.nav.NavSession
import app.vela.core.nav.RouteProjection
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
 * Holds the Bluetooth link to a paired nav device and feeds it what Vela already knows: where the
 * phone is, which way it is going, the drive's next turn, the route line and the destination.
 * With their switches on it also sends what is playing and a ringing call, and takes the device's
 * buttons for both.
 *
 * It reads [NavSession.state] and [LocationProvider.updates] and changes nothing in either, the
 * way the drive notification and the car screens do. A start that the system refuses is
 * swallowed: the app works the same without the device.
 */
@AndroidEntryPoint
class DeviceService : Service() {

    @Inject lateinit var navSession: NavSession
    @Inject lateinit var locationProvider: LocationProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    private var link: DeviceLink? = null
    private var connected = false
    /** The user sent the device to sleep: a dropped link is expected until it is touched. */
    private var asleep = false
    private var foreground = false

    private var locationJob: Job? = null
    private var navJob: Job? = null
    private var routeJob: Job? = null
    /** The route whose line the device holds; compared by identity, as the session swaps it. */
    private var sentRoute: Route? = null
    private var routeSent = false
    private var cumFor: Route? = null
    private var cum = DoubleArray(0)
    /** The last course worth showing, held while stopped so the device map does not spin. */
    private var course = 0f

    private val encoder = DeviceNavEncoder(object : DeviceNavEncoder.Out {
        override fun navWrite(data: ByteArray, tag: String?) { link?.writeNav(data, tag) }
        override fun navReady() = link?.mediaReady == true
    })
    private var media: DeviceMedia? = null
    private var calls: DeviceCalls? = null

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) {
                link?.onBondReady()
            }
        }
    }

    /** Bluetooth switched off and on kills the GATT client for good: build the link again. */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF -> link?.disconnect()
                BluetoothAdapter.STATE_ON -> {
                    val bond = DeviceLinkPrefs.bond.value ?: return
                    if (!DeviceLinkPrefs.enabled.value) return
                    link?.disconnect()
                    main.postDelayed({ link?.connect(bond.mac, bond.token) }, 1000)
                    publish(Link.CONNECTING)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        media = DeviceMedia(this, object : DeviceMedia.Out {
            override fun mediaWrite(data: ByteArray, tag: String?) { link?.writeMedia(data, tag) }
            override fun mediaMaxWrite() = link?.maxWrite() ?: 20
            override fun mediaBoost(ms: Long) { link?.boost(ms) }
            override fun mediaLinkReady() = link?.mediaReady == true
        })
        calls = DeviceCalls(this, object : DeviceCalls.Out {
            override fun callWrite(data: ByteArray, tag: String?) { link?.writeCall(data, tag) }
            override fun callLinkReady() = link?.callReady == true
        })
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bondReceiver) }
        runCatching { unregisterReceiver(adapterReceiver) }
        main.removeCallbacksAndMessages(null)
        media?.stop()
        calls?.stop()
        link?.disconnect()
        scope.cancel()
        publish(Link.OFF)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bond = DeviceLinkPrefs.bond.value
        if (intent?.action == ACTION_STOP || bond == null || !DeviceLinkPrefs.enabled.value || !hasBluetooth(this)) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!foreground) {
            // Can throw on Android 12 and up (a start from the background, or a permission the
            // service type demands that is not granted). The link is not worth a crash.
            try {
                startForegroundTyped(notification(linkState.value))
                foreground = true
            } catch (t: Throwable) {
                Log.w(TAG, "foreground start refused", t)
                shutDown()
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_UNPAIR -> {
                // Queued behind anything in flight: give it time to reach the device before the
                // bond and the link go.
                link?.writeCmd(byteArrayOf(DeviceLink.CMD_UNPAIR))
                DeviceLinkPrefs.clearBond(this)
                main.postDelayed({
                    link?.removeBond()
                    shutDown()
                }, 800)
                return START_NOT_STICKY
            }
            ACTION_SLEEP -> {
                // No disconnect here: it would cancel this very write. The link drops when the
                // device powers down, and the reconnect backoff finds it when it is touched awake.
                if (connected) {
                    asleep = true
                    publish(Link.ASLEEP)
                    link?.cmdSleep()
                }
                return START_STICKY
            }
            ACTION_BRIGHTNESS -> {
                link?.cmdSetBrightness(DeviceLinkPrefs.brightness.intValue)
                return START_STICKY
            }
            ACTION_THEME -> {
                link?.cmdSetTheme(DeviceLinkPrefs.darkMap.value)
                return START_STICKY
            }
            ACTION_MINIMAL -> {
                link?.cmdSetMinimal(DeviceLinkPrefs.minimal.value)
                return START_STICKY
            }
            ACTION_REFRESH -> {
                syncBridges()
                if (connected) startLocation()
                return START_STICKY
            }
        }
        val fresh = intent?.getBooleanExtra(EXTRA_FRESH, false) == true
        val l = link ?: DeviceLink(this, linkCallbacks).also { link = it }
        if (fresh) l.disconnect() // an attempt still running on the previous pairing
        l.connect(bond.mac, bond.token, fresh)
        if (!connected) publish(Link.CONNECTING)
        return START_STICKY
    }

    private val linkCallbacks = object : DeviceLink.Cb {
        override fun onConnState(c: Boolean) {
            connected = c
            if (c) {
                asleep = false
                publish(Link.CONNECTED)
                startLocation()
            } else {
                publish(if (asleep) Link.ASLEEP else Link.CONNECTING)
                locationJob?.cancel()
                locationJob = null
                navJob?.cancel()
                navJob = null
                routeJob?.cancel()
                // The device forgot the street, the pin and the line: send them all again.
                encoder.reset()
                routeSent = false
                sentRoute = null
                media?.stop()
                calls?.stop()
            }
        }

        override fun onBonded() {}

        override fun onStatus(batteryMv: Int, link: Int, darkMap: Boolean, minimal: Boolean?) {
            if (DeviceLinkPrefs.darkMap.value != darkMap) DeviceLinkPrefs.setDarkMap(this@DeviceService, darkMap)
            // The mode can also be switched on the device itself, by a long press on its map.
            if (minimal != null && DeviceLinkPrefs.minimal.value != minimal) {
                DeviceLinkPrefs.setMinimal(this@DeviceService, minimal)
            }
        }

        /** Discovery, MTU and the first subscription are done: writes now reach the device. */
        override fun onMediaReady() {
            startNav()
            syncBridges()
        }

        override fun onMediaCmd(cmd: ByteArray) {
            if (DeviceLinkPrefs.media.value) media?.handle(cmd)
        }

        override fun onCallReady() = syncBridges()

        override fun onCallCmd(cmd: ByteArray) {
            if (DeviceLinkPrefs.calls.value) calls?.handle(cmd)
        }
    }

    /** Start or stop the optional bridges to match their switches and the channels that are up. */
    private fun syncBridges() {
        val l = link
        if (l?.mediaReady == true && DeviceLinkPrefs.media.value) {
            media?.start()
            media?.sendAll()
        } else {
            media?.stop()
        }
        if (l?.callReady == true && DeviceLinkPrefs.calls.value) calls?.start() else calls?.stop()
    }

    // ---- position ----

    private fun startLocation() {
        if (locationJob != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        // With the simulator on, these are the simulated fixes: the device follows a demo drive.
        locationJob = scope.launch { locationProvider.updates().collect { onFix(it) } }
    }

    private fun onFix(loc: Location) {
        if (!connected) return
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        course = courseFor(loc)
        link?.writePosition(
            DeviceNavEncoder.position(
                lat = loc.latitude,
                lng = loc.longitude,
                speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                courseDeg = course,
                fix = loc.hasAccuracy() && loc.accuracy < FIX_ACCURACY_M,
                nowMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Which way the device's marker points. On a drive it is the road's own direction, steady at
     * any speed. Otherwise it is the GPS course while moving, and the last value while stopped,
     * where a GPS course is noise.
     */
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

    // ---- the drive ----

    private fun startNav() {
        if (navJob != null) return
        navJob = scope.launch {
            launch { navSession.state.collect { pushNav(it) } }
            // The session does not emit while nothing changes (stopped at a light), and the
            // device hides its banner after six seconds of silence.
            while (isActive) {
                delay(STATE_REPEAT_MS)
                pushNav(navSession.state.value)
            }
        }
    }

    private fun pushNav(s: NavSession.State) {
        val frame = DeviceNavFrame.of(s)
        encoder.send(frame)
        val imperial = app.vela.ui.Units.imperial.value
        val kmh = DeviceBridge.speedLimitKmh.value
        encoder.sendLimit(imperial, if (kmh == null) 0 else (if (imperial) kmh / 1.609344 else kmh).roundToInt())
        // The line goes with the frame: an idle frame (no drive, or a paused one) makes the
        // device clear its line, so the line is sent again when the drive is back.
        val route = s.route?.takeIf { frame != DeviceNavFrame.IDLE }
        if (routeSent && route === sentRoute) return
        routeSent = true
        sentRoute = route
        routeJob?.cancel()
        routeJob = scope.launch {
            // Thinning a long line is real work; the writes belong on the main thread.
            val chunks = withContext(Dispatchers.Default) {
                DeviceNavEncoder.routeChunks(route?.polyline ?: emptyList())
            }
            encoder.sendRoute(chunks)
        }
    }

    // ---- state shown to the user ----

    private fun publish(state: Link) {
        if (linkState.value == state) return
        linkState.value = state
        if (foreground) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(state)) }
        }
    }

    private fun shutDown() {
        main.removeCallbacksAndMessages(null)
        asleep = false
        media?.stop()
        calls?.stop()
        link?.disconnect()
        link = null
        publish(Link.OFF)
        if (foreground) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foreground = false
        stopSelf()
    }

    private fun startForegroundTyped(n: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n)
            return
        }
        // The location type keeps fixes coming with the screen off, but it may only be claimed
        // while the app is visible. Without it the link still runs and position follows the app.
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

    private fun notification(state: Link): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.device_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = DeviceLinkPrefs.bond.value?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.settings_device)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(name)
            .setContentText(getString(state.label))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Where the link stands, for the settings page and the notification. */
    enum class Link(val label: Int) {
        OFF(R.string.device_state_off),
        CONNECTING(R.string.device_state_connecting),
        CONNECTED(R.string.device_state_connected),
        ASLEEP(R.string.device_state_asleep),
    }

    companion object {
        private const val TAG = "VelaDevice"
        private const val ACTION_STOP = "app.vela.device.STOP"
        private const val ACTION_UNPAIR = "app.vela.device.UNPAIR"
        private const val ACTION_SLEEP = "app.vela.device.SLEEP"
        private const val ACTION_BRIGHTNESS = "app.vela.device.BRIGHTNESS"
        private const val ACTION_THEME = "app.vela.device.THEME"
        private const val ACTION_MINIMAL = "app.vela.device.MINIMAL"
        private const val ACTION_REFRESH = "app.vela.device.REFRESH"
        private const val EXTRA_FRESH = "fresh"
        private const val CHANNEL_ID = "vela_device"
        private const val NOTIF_ID = 44
        private const val STATE_REPEAT_MS = 1_000L
        /** A fix looser than this is sent as "no fix", and the device grays its marker. */
        private const val FIX_ACCURACY_M = 50f
        /** Below this speed a GPS course is not trusted. */
        private const val MOVING_MPS = 1.5f

        /** Written on the main thread by the running service; [Link.OFF] when there is none. */
        val linkState = mutableStateOf(Link.OFF)

        /** Android 12 and up ask for Nearby devices at run time; before that the install grants it. */
        fun hasBluetooth(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** Bring the link up if a device is paired and the switch is on. [fresh]: a QR was just
         *  scanned, so any old system bond for that device is dropped first. */
        fun start(context: Context, fresh: Boolean = false) {
            if (DeviceLinkPrefs.bond.value == null || !DeviceLinkPrefs.enabled.value || !hasBluetooth(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, DeviceService::class.java).putExtra(EXTRA_FRESH, fresh),
                )
            }.onFailure { Log.w(TAG, "could not start the device service", it) }
        }

        fun stop(context: Context) = send(context, ACTION_STOP)
        /** Tells the device to forget this phone, then forgets the device. With no service
         *  running there is no link to tell it over, so only the phone's side goes. */
        fun unpair(context: Context) {
            if (linkState.value == Link.OFF) DeviceLinkPrefs.clearBond(context) else send(context, ACTION_UNPAIR)
        }
        fun sleep(context: Context) = send(context, ACTION_SLEEP)
        fun pushBrightness(context: Context) = send(context, ACTION_BRIGHTNESS)
        fun pushTheme(context: Context) = send(context, ACTION_THEME)
        fun pushMinimal(context: Context) = send(context, ACTION_MINIMAL)
        /** A switch on the settings page changed, or a permission was just granted. */
        fun refresh(context: Context) = send(context, ACTION_REFRESH)

        /** Only reaches a service that is already running; a stopped one has nothing to change. */
        private fun send(context: Context, action: String) {
            if (linkState.value == Link.OFF) return
            runCatching { context.startService(Intent(context, DeviceService::class.java).setAction(action)) }
        }
    }
}
