package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.data.RoutingPrefs

/**
 * Motorcycle mode. [shown] is the Settings > Navigation switch that puts a Motorcycle chip on the
 * route card; [on] is that chip, sticky like the avoid chips beside it. Drive routes keep off roads
 * closed to motorcycles only while both are on: mirrored into [RoutingPrefs.motorcycle], which the
 * directions engine reads for planning, every reroute, the car screen and the nav device's
 * Home and Work.
 */
object Motorcycle {
    val shown = mutableStateOf(false)
    val on = mutableStateOf(false)

    fun init(context: Context) {
        val p = prefs(context)
        shown.value = p.getBoolean(KEY_SHOWN, false)
        on.value = p.getBoolean(KEY_ON, false)
        mirror()
    }

    fun setShown(context: Context, value: Boolean) {
        shown.value = value
        mirror()
        prefs(context).edit().putBoolean(KEY_SHOWN, value).apply()
    }

    fun setOn(context: Context, value: Boolean) {
        on.value = value
        mirror()
        prefs(context).edit().putBoolean(KEY_ON, value).apply()
    }

    /** True while drive routes are motorcycle routes. */
    val active: Boolean get() = shown.value && on.value

    private fun mirror() {
        RoutingPrefs.motorcycle = active
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY_SHOWN = "motorcycle_shown"
    private const val KEY_ON = "motorcycle_on"
}
