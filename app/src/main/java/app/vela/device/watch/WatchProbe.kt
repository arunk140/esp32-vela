package app.vela.device.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A step-by-step test of the CMF watch's app channel from this phone, next to Nothing X. It
 * proves, in order: the watch is bonded, a second GATT client can join the link Nothing X holds,
 * the shell answers `AT GETSECRET`, and the patched firmware answers a PING sealed with that key.
 * [demoDrive] then sends a short made-up drive to the `vela-nav` app if it is open.
 *
 * Only for the test section of Settings > Nav device. The log shows no key, address or position.
 */
@SuppressLint("MissingPermission")
object WatchProbe {
    private val CMD_W = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val CMD_N = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val SHELL_W = UUID.fromString("77d4ff01-2fe2-2334-0d35-9ccd078f529c")
    private val SHELL_N = UUID.fromString("77d4ff02-2fe2-2334-0d35-9ccd078f529c")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val log = mutableStateListOf<String>()
    val running = mutableStateOf(false)
    val linked = mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var key: ByteArray? = null
    private var mtu = 23
    private var connected: CompletableDeferred<Boolean>? = null
    private var pending: CompletableDeferred<Int>? = null
    private val parser = WatchFrames.Parser()
    private var frames = Channel<WatchFrames.Frame>(Channel.UNLIMITED)
    private var shell = Channel<ByteArray>(Channel.UNLIMITED)
    private var seq = 0

    private fun say(s: String) {
        scope.launch { log += s }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected?.complete(true)
            } else {
                connected?.complete(false)
                pending?.completeExceptionally(IllegalStateException("link dropped (status $status)"))
                if (linked.value) say("✗ link dropped (status $status)")
                scope.launch { linked.value = false }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                CMD_N -> synchronized(parser) { parser.feed(value) }.forEach { frames.trySend(it) }
                SHELL_N -> shell.trySend(value)
            }
        }

        @Deprecated("Before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, c, c.value ?: return)
        }
    }

    /** One GATT operation: started by [start], finished by its callback. */
    private suspend fun op(what: String, start: (BluetoothGatt) -> Boolean): Int {
        val g = gatt ?: error("not connected")
        val d = CompletableDeferred<Int>()
        pending = d
        repeat(50) {
            if (start(g)) return withTimeout(6000) { d.await() }
            delay(20) // the stack is busy
        }
        error("$what: the phone refused to start it")
    }

    private fun chr(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { s -> s.characteristics.firstOrNull { it.uuid == uuid } }

    private suspend fun write(uuid: UUID, data: ByteArray) {
        val c = chr(uuid) ?: error("characteristic $uuid missing")
        // abi-1.4: one ATT write per frame, with response when it does not fit MTU − 3
        val type = if (data.size <= mtu - 3) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        op("write") { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    private suspend fun subscribe(uuid: UUID) {
        val c = chr(uuid) ?: error("characteristic $uuid missing")
        val g = gatt ?: error("not connected")
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return
        val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("subscribe") {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeDescriptor(d, on) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = on
                @Suppress("DEPRECATION")
                it.writeDescriptor(d)
            }
        }
    }

    private suspend fun at(command: String): String {
        while (shell.tryReceive().isSuccess) Unit
        write(SHELL_W, "$command\r\n".toByteArray())
        val sb = StringBuilder()
        withTimeout(6000) {
            while (listOf("OK", "ERROR", "FAIL").none { it in sb }) sb.append(String(shell.receive()))
        }
        return sb.toString()
    }

    /** Sends a frame and waits for the reply this key can open; other hosts' frames are skipped. */
    private suspend fun request(c2: Int, plain: ByteArray, timeoutMs: Long = 4000): ByteArray? {
        val k = key ?: error("no key")
        while (frames.tryReceive().isSuccess) Unit
        write(CMD_W, WatchFrames.frame(c2, plain, k))
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val f = frames.receive()
                if (f.c1 == WatchFrames.SYSTEM && f.c2 == WatchFrames.EVENT) {
                    WatchFrames.decrypt(k, f.body)?.let { onEvent(it) }
                    continue
                }
                if (f.c1 != WatchFrames.SYSTEM || f.c2 != WatchFrames.reply(c2)) continue
                WatchFrames.decrypt(k, f.body)?.let { return@withTimeoutOrNull it }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    private fun onEvent(plain: ByteArray) {
        if (plain.size < 3) return
        val data = plain.copyOfRange(2, plain.size)
        val shown = if (data.contentEquals(WatchNav.ASK)) "n1Q (the app asks for a snapshot)" else "${data.size} B"
        say("← app message: $shown")
    }

    private fun systemLinked(d: BluetoothDevice): Boolean =
        (devicesManager().getConnectionState(d, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED)

    private lateinit var appContext: Context
    private fun devicesManager() = appContext.getSystemService(BluetoothManager::class.java)

    /** A bonded device the test can be pointed at. */
    class Candidate(val address: String, val name: String, val connected: Boolean)

    /** Bonded devices, the ones connected right now first, then CMF-named ones. */
    fun candidates(context: Context): List<Candidate> {
        appContext = context.applicationContext
        val adapter = devicesManager().adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { Candidate(it.address, it.name ?: "?", systemLinked(it)) }
            .sortedWith(compareByDescending<Candidate> { it.connected }.thenByDescending { "CMF" in it.name.uppercase() })
    }

    /** The watch picked in the test section; kept on this phone only. */
    fun chosen(context: Context): String? =
        context.getSharedPreferences("watch_test", Context.MODE_PRIVATE).getString("address", null)

    fun choose(context: Context, address: String) {
        context.getSharedPreferences("watch_test", Context.MODE_PRIVATE).edit().putString("address", address).apply()
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
        val adapter = devicesManager().adapter ?: error("no Bluetooth on this phone")
        if (!adapter.isEnabled) error("Bluetooth is off")
        val address = chosen(appContext) ?: error("pick the watch first")
        val watch = adapter.bondedDevices.orEmpty().firstOrNull { it.address == address }
            ?: error("the picked watch is no longer bonded: pick it again")
        device = watch
        say("✓ 1. bonded watch: ${watch.name}")
        say(if (systemLinked(watch)) "✓ 2. the watch is connected (Nothing X holds the link)"
            else "• 2. the watch is not connected now: Vela will open the link itself")

        frames = Channel(Channel.UNLIMITED)
        shell = Channel(Channel.UNLIMITED)
        mtu = 23
        val c = CompletableDeferred<Boolean>()
        connected = c
        gatt = watch.connectGatt(appContext, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (withTimeoutOrNull(15_000) { c.await() } != true) error("3. could not connect a GATT client")
        say("✓ 3. second GATT client connected")

        runCatching { op("mtu") { it.requestMtu(247) } }
        say("• MTU $mtu")
        if (op("discover") { it.discoverServices() } != BluetoothGatt.GATT_SUCCESS) error("service discovery failed")
        val missing = listOf(CMD_W, CMD_N, SHELL_W, SHELL_N).filter { chr(it) == null }
        if (missing.isNotEmpty()) error("4. watch services missing: $missing")
        say("✓ 4. command and shell services found")

        subscribe(CMD_N)
        subscribe(SHELL_N)
        say("✓ 5. subscribed to notifications")

        val reply = at("AT GETSECRET")
        val hex = Regex("GETSECRET:([0-9A-Fa-f]{32})").find(reply)?.groupValues?.get(1)
            ?: error("6. the shell did not give the secret (reply ${reply.length} chars)")
        key = WatchFrames.hex(hex)
        say("✓ 6. channel key read from the shell (kept in memory only)")

        val pong = request(WatchFrames.PING, byteArrayOf(0))
            ?: error("7. no PING reply: stock firmware, or a stub older than 2026-11-13 (no channel key)")
        if (pong.size != 3 || pong[0].toInt() != 0) error("7. odd PING reply (${pong.size} B)")
        val slot = pong[1].toInt() and 0xFF
        val flags = pong[2].toInt() and 0xFF
        linked.value = true
        say("✓ 7. patched firmware answered the PING")
        say(
            when {
                slot == 0xFF -> "• no watch app is open. Open Vela Nav on the watch, then Send demo drive"
                flags and 2 != 0 -> "• an app runs in slot $slot but is paused (a popup covers it)"
                flags and 1 == 0 -> "• the app in slot $slot is too old for messages (ABI 1.0)"
                else -> "✓ an app is open in slot $slot: try Send demo drive"
            },
        )
        say(if (systemLinked(watch)) "✓ Nothing X's link is still up" else "• the system link is down")
    }

    /** A made-up 40 s drive for the open `vela-nav` app: route, then the car moving along it. */
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
        var wait = 20L
        repeat(8) {
            val r = request(WatchFrames.MSG, byteArrayOf(0xFF.toByte()) + data) ?: error("no reply to a message")
            when (r.firstOrNull()?.toInt()) {
                0 -> return true
                7 -> error("Vela Nav is not open on the watch")
                8, 9 -> delay(wait).also { wait = minOf(wait * 2, 500) } // inbox full or paused
                else -> error("watch refused a message (status ${r.firstOrNull()})")
            }
        }
        return false
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
            if (arrived) break
            delay(1000)
        }
        say("✓ demo drive done")
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        key = null
        linked.value = false
    }
}
