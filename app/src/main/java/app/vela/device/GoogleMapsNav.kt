package app.vela.device

import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.Locale

/**
 * Google Maps' navigation, read from its notification for the nav device ("Follow Google Maps",
 * Settings > Nav device). With Vela not navigating, the device shows Google Maps' next turn, its
 * distance and the road, and the distance and time left. Google Maps does not share its route
 * line, so the device draws none.
 *
 * The notification (Android 16, Google Maps 2026) is a progress-style one in the navigation
 * category: the title is the instruction with the distance to it in front ("100 m · Turn left
 * onto Elm Street"; no distance at the start and in the last meters before the turn), the short
 * critical text the distance alone, the progress the meters driven of the trip's meters, and the
 * sub-text the arrival ("Arrive 16:16"). The instruction's words say the turn; they are matched
 * in English only, and any other wording is shown as a plain "ahead".
 *
 * Nothing here logs and nothing is kept: the street and the turn go to the device and nowhere else.
 */
object GoogleMapsNav {
    const val PACKAGE = "com.google.android.apps.maps"

    private val _frame = MutableStateFlow<DeviceNavFrame?>(null)
    /** Google Maps' drive as the device shows it; null when it is not navigating. */
    val frame: StateFlow<DeviceNavFrame?> = _frame

    /** One update of Google Maps' navigation notification. */
    fun onPosted(extras: Bundle, nowMs: Long = System.currentTimeMillis()) {
        _frame.value = frameOf(
            title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            shortDistance = extras.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString(),
            subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString(),
            progress = extras.getInt(android.app.Notification.EXTRA_PROGRESS, 0),
            progressMax = extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0),
            nowMs = nowMs,
            previous = _frame.value,
        )
    }

    /** The navigation notification went away: Google Maps stopped navigating. */
    fun onRemoved() {
        _frame.value = null
    }

    /** The title splits into its distance and its instruction on this separator. */
    private const val SEPARATOR = " · "
    /** Android 16's promoted-notification chip text. */
    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
    /** A step with no distance shown is this close: Google Maps drops the figure for the last meters. */
    private const val NEAR_M = 40

    /** The frame for one notification. [previous] is the last one, for the step with no distance shown. */
    fun frameOf(
        title: String?,
        shortDistance: String?,
        subText: String?,
        progress: Int,
        progressMax: Int,
        nowMs: Long,
        previous: DeviceNavFrame? = null,
    ): DeviceNavFrame? {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        val cut = t.indexOf(SEPARATOR)
        val shownDistance = if (cut > 0) distanceM(t.substring(0, cut)) else null
        val instruction = if (cut > 0 && shownDistance != null) t.substring(cut + SEPARATOR.length).trim() else t
        val maneuver = maneuverOf(instruction)
        val street = streetOf(instruction)
        val distance = shownDistance ?: distanceM(shortDistance)
            // No figure: the turn is a few meters on if the same step was counting down,
            // otherwise (the start of a trip) not known, shown as here.
            ?: if (previous != null && previous.street == street && previous.maneuver == maneuver) minOf(previous.distanceM, NEAR_M) else 0
        val remaining = if (progressMax > 0) (progressMax - progress).coerceAtLeast(0) else 0
        return DeviceNavFrame(
            active = true,
            maneuver = maneuver,
            distanceM = distance,
            street = street,
            remainingM = remaining,
            remainingS = arrivalSeconds(subText, nowMs) ?: 0,
            turnOnly = true,
        )
    }

    /** "100 m", "1.2 km", "1,2 km", "0.3 mi", "500 ft" in meters; null for anything else. */
    fun distanceM(s: String?): Int? {
        val m = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(m|km|mi|ft|yd)\s*$""", RegexOption.IGNORE_CASE).find(s ?: return null) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "m" -> v
            "km" -> v * 1000.0
            "mi" -> v * 1609.344
            "ft" -> v * 0.3048
            else -> v * 0.9144
        }.toInt()
    }

    /** The device's turn code for an English instruction. */
    fun maneuverOf(instruction: String): Int {
        val s = instruction.lowercase(Locale.ROOT)
        fun has(vararg w: String) = w.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(s) }
        val left = has("left")
        val right = has("right")
        return when {
            has("u-turn", "u turn", "uturn") -> DeviceManeuver.UTURN
            has("roundabout", "traffic circle", "rotary") -> DeviceManeuver.ROUNDABOUT
            has("destination", "arrive", "arrived") -> DeviceManeuver.ARRIVE
            has("sharp") && left -> DeviceManeuver.SHARP_LEFT
            has("sharp") && right -> DeviceManeuver.SHARP_RIGHT
            has("slight", "bear") && left -> DeviceManeuver.SLIGHT_LEFT
            has("slight", "bear") && right -> DeviceManeuver.SLIGHT_RIGHT
            has("keep", "fork", "stay") && left -> DeviceManeuver.KEEP_LEFT
            has("keep", "fork", "stay") && right -> DeviceManeuver.KEEP_RIGHT
            has("exit", "ramp", "take") && left -> DeviceManeuver.SLIGHT_LEFT
            has("exit", "ramp", "take") && right -> DeviceManeuver.SLIGHT_RIGHT
            left -> DeviceManeuver.LEFT
            right -> DeviceManeuver.RIGHT
            else -> DeviceManeuver.CONTINUE
        }
    }

    /** The road the step goes onto, or the instruction itself when it names none. */
    fun streetOf(instruction: String): String {
        // "onto" and the like name the road; a bare "on" only when nothing better does, and never
        // the side in "the exit on the left".
        val m = Regex("""\b(?:onto|toward|towards|to stay on|via)\s+(.+)$""", RegexOption.IGNORE_CASE).find(instruction)
            ?: Regex("""\bon\s+(?!the (?:left|right)\b)(.+)$""", RegexOption.IGNORE_CASE).find(instruction)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: instruction.trim()
    }

    /** Seconds from [nowMs] to the arrival in "Arrive 16:16" or "Arrive 4:16 PM"; the next day when
     *  that clock time has passed. Null when there is none. */
    fun arrivalSeconds(subText: String?, nowMs: Long): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})\s*([ap]\.?\s?m\.?)?""", RegexOption.IGNORE_CASE).find(subText ?: return null) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].lowercase(Locale.ROOT).replace(".", "").replace(" ", "")
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val at = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            // The minute shown can be the one under way; only a time well past is tomorrow's.
            if (timeInMillis < nowMs - 5 * 60_000L) add(Calendar.DAY_OF_YEAR, 1)
        }
        return ((at.timeInMillis - nowMs) / 1000).toInt().coerceAtLeast(0)
    }
}
