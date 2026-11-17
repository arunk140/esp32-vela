package app.vela.device.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One GATT client on the CMF Watch Pro 2, next to Nothing X on the same link, talking to the
 * patched firmware's app channel with the channel key K (cmf-watch-sdk `docs/abi-1.4.md`,
 * "Writing a host"). [open] connects and reads K; [ping] and [send] are one request each, one at a
 * time; the running watch app's own messages arrive on [events].
 *
 * Nothing here logs: K, the address and the messages are personal data.
 */
@SuppressLint("MissingPermission")
class WatchSession(private val ctx: Context, private val say: (String) -> Unit = {}) {

    /** The PING answer: which slot runs an app, and its flags (bit 0 can take messages, bit 1 paused). */
    class Pong(val slot: Int, val flags: Int) {
        val appOpen get() = slot != 0xFF && flags and 3 == 1
    }

    /** The running app's messages to the phone, newest last. */
    val events = Channel<ByteArray>(64)
    /** Called once when the link drops after [open] succeeded. */
    var onDrop: (() -> Unit)? = null
    @Volatile var isOpen = false
        private set

    private var gatt: BluetoothGatt? = null
    @Volatile private var key: ByteArray? = null
    private var mtu = 23
    private var connected: CompletableDeferred<Boolean>? = null
    private var pending: CompletableDeferred<Int>? = null
    private val parser = WatchFrames.Parser()
    private val replies = Channel<WatchFrames.Frame>(Channel.UNLIMITED)
    private val shell = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Mutex()

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected?.complete(true)
                return
            }
            connected?.complete(false)
            pending?.completeExceptionally(IllegalStateException("link dropped"))
            if (isOpen) {
                isOpen = false
                onDrop?.invoke()
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
                CMD_N -> synchronized(parser) { parser.feed(value) }.forEach(::onFrame)
                SHELL_N -> shell.trySend(value)
            }
        }

        @Deprecated("Before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, c, c.value ?: return)
        }
    }

    private fun onFrame(f: WatchFrames.Frame) {
        if (f.c1 != WatchFrames.SYSTEM) return
        if (f.c2 == WatchFrames.EVENT) {
            // seq, slot, data; another host's events (Nothing X's key) do not open
            val k = key ?: return
            val p = WatchFrames.decrypt(k, f.body) ?: return
            if (p.size >= 3) events.trySend(p.copyOfRange(2, p.size))
            return
        }
        replies.trySend(f)
    }

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
        val c = chr(uuid) ?: error("characteristic missing")
        // one ATT write per frame, with response when it does not fit MTU − 3
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
        val c = chr(uuid) ?: error("characteristic missing")
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

    /** Connects a second GATT client to [device] and reads K. Throws with a short reason. */
    suspend fun open(device: BluetoothDevice) {
        val c = CompletableDeferred<Boolean>()
        connected = c
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (withTimeoutOrNull(15_000) { c.await() } != true) error("could not connect a GATT client")
        say("✓ 3. second GATT client connected")
        runCatching { op("mtu") { it.requestMtu(247) } }
        say("• MTU $mtu")
        if (op("discover") { it.discoverServices() } != BluetoothGatt.GATT_SUCCESS) error("service discovery failed")
        val missing = listOf(CMD_W, CMD_N, SHELL_W, SHELL_N).count { chr(it) == null }
        if (missing > 0) error("$missing watch characteristics missing")
        say("✓ 4. command and shell services found")
        subscribe(CMD_N)
        subscribe(SHELL_N)
        say("✓ 5. subscribed to notifications")
        val reply = at("AT GETSECRET")
        val hex = Regex("GETSECRET:([0-9A-Fa-f]{32})").find(reply)?.groupValues?.get(1)
            ?: error("the shell did not give the channel key")
        key = WatchFrames.hex(hex)
        say("✓ 6. channel key read from the shell (kept in memory only)")
        isOpen = true
    }

    /** Sends a frame and waits for the reply this key opens; null when none comes. */
    private suspend fun request(c2: Int, plain: ByteArray, timeoutMs: Long = 4000): ByteArray? = lock.withLock {
        val k = key ?: return null
        while (replies.tryReceive().isSuccess) Unit // stale replies of a request that timed out
        write(CMD_W, WatchFrames.frame(c2, plain, k))
        withTimeoutOrNull(timeoutMs) {
            var got: ByteArray? = null
            while (got == null) {
                val f = replies.receive()
                if (f.c2 == WatchFrames.reply(c2)) got = WatchFrames.decrypt(k, f.body)
            }
            got
        }
    }

    /** Null when the firmware does not answer (stock firmware, a stub without K, a dead link). */
    suspend fun ping(): Pong? {
        val r = request(WatchFrames.PING, byteArrayOf(0)) ?: return null
        if (r.size != 3 || r[0].toInt() != 0) return null
        return Pong(r[1].toInt() and 0xFF, r[2].toInt() and 0xFF)
    }

    /**
     * One message to the running app. The watch's status: [OK], [NO_APP] (nothing open, or a
     * pre-1.1 app), [FULL] and [PAUSED] after a few retries, or [NO_REPLY].
     */
    suspend fun send(data: ByteArray): Int {
        var wait = 20L
        repeat(6) {
            val r = request(WatchFrames.MSG, byteArrayOf(0xFF.toByte()) + data) ?: return NO_REPLY
            val s = r.firstOrNull()?.toInt() ?: return NO_REPLY
            if (s != FULL && s != PAUSED) return s
            delay(wait)
            wait = minOf(wait * 2, 400)
        }
        return FULL
    }

    fun close() {
        isOpen = false
        onDrop = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        key = null
        events.close()
    }

    companion object {
        const val OK = 0
        const val NO_APP = 7
        const val FULL = 8
        const val PAUSED = 9
        const val NO_REPLY = -1

        private val CMD_W = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val CMD_N = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val SHELL_W = UUID.fromString("77d4ff01-2fe2-2334-0d35-9ccd078f529c")
        private val SHELL_N = UUID.fromString("77d4ff02-2fe2-2334-0d35-9ccd078f529c")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
