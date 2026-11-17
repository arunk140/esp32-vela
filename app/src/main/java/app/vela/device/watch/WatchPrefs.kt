package app.vela.device.watch

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** The CMF watch picked in Settings > Nav device, and whether it shows the drive. On this phone only. */
object WatchPrefs {
    private const val FILE = "watch_link"
    val address = mutableStateOf<String?>(null)
    val enabled = mutableStateOf(false)
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        address.value = p.getString("address", null)
            // the first test build kept the pick here
            ?: context.getSharedPreferences("watch_test", Context.MODE_PRIVATE).getString("address", null)
        enabled.value = p.getBoolean("enabled", false)
    }

    fun setAddress(context: Context, a: String) {
        address.value = a
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("address", a).apply()
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled.value = on
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
    }
}
