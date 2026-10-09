package app.vela.device

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * The paired nav device and its switches. The file is left out of device backups on purpose: the
 * pairing belongs to this phone's Bluetooth keys, and a restored copy on another phone would
 * point at a device that does not know it.
 */
object DeviceLinkPrefs {

    /** [token] is the six-digit code the device showed in its QR; it is sent once per connection. */
    data class Bond(val mac: String, val name: String, val token: String)

    val bond = mutableStateOf<Bond?>(null)
    /** The link runs while this is on and a device is paired. */
    val enabled = mutableStateOf(true)
    val brightness = mutableIntStateOf(80)
    /** The device's own map theme. The device is the truth; this follows what it reports. */
    val darkMap = mutableStateOf(true)
    /** The device shows only the route line and the next turn on black, and reads no map. */
    val minimal = mutableStateOf(false)
    /** Show what is playing on the device and take its media buttons. Needs notification access. */
    val media = mutableStateOf(false)
    /** Show a ringing call on the device and take its answer and reject buttons. */
    val calls = mutableStateOf(false)

    fun init(context: Context) {
        val p = prefs(context)
        val mac = p.getString(KEY_MAC, null)
        bond.value = mac?.let { Bond(it, p.getString(KEY_NAME, "") ?: "", p.getString(KEY_TOKEN, "") ?: "") }
        enabled.value = p.getBoolean(KEY_ENABLED, true)
        brightness.intValue = p.getInt(KEY_BRIGHTNESS, 80)
        darkMap.value = p.getBoolean(KEY_DARK_MAP, true)
        minimal.value = p.getBoolean(KEY_MINIMAL, false)
        media.value = p.getBoolean(KEY_MEDIA, false)
        calls.value = p.getBoolean(KEY_CALLS, false)
    }

    fun saveBond(context: Context, b: Bond) {
        bond.value = b
        prefs(context).edit().putString(KEY_MAC, b.mac).putString(KEY_NAME, b.name).putString(KEY_TOKEN, b.token).apply()
    }

    fun clearBond(context: Context) {
        bond.value = null
        prefs(context).edit().remove(KEY_MAC).remove(KEY_NAME).remove(KEY_TOKEN).apply()
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled.value = on
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun setBrightness(context: Context, percent: Int) {
        brightness.intValue = percent.coerceIn(0, 100)
        prefs(context).edit().putInt(KEY_BRIGHTNESS, brightness.intValue).apply()
    }

    fun setDarkMap(context: Context, dark: Boolean) {
        darkMap.value = dark
        prefs(context).edit().putBoolean(KEY_DARK_MAP, dark).apply()
    }

    fun setMinimal(context: Context, on: Boolean) {
        minimal.value = on
        prefs(context).edit().putBoolean(KEY_MINIMAL, on).apply()
    }

    fun setMedia(context: Context, on: Boolean) {
        media.value = on
        prefs(context).edit().putBoolean(KEY_MEDIA, on).apply()
    }

    fun setCalls(context: Context, on: Boolean) {
        calls.value = on
        prefs(context).edit().putBoolean(KEY_CALLS, on).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_device", Context.MODE_PRIVATE)
    private const val KEY_MAC = "mac"
    private const val KEY_NAME = "name"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_DARK_MAP = "dark_map"
    private const val KEY_MINIMAL = "minimal"
    private const val KEY_MEDIA = "media"
    private const val KEY_CALLS = "calls"
}
