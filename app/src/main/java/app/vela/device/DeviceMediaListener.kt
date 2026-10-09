package app.vela.device

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Vela's notification listener. It does two things and looks at nothing else.
 *
 * Its existence lets the user grant Notification access, which Android requires before an app
 * may read what is playing (`MediaSessionManager.getActiveSessions`).
 *
 * It spots a call ringing in another app. WhatsApp, Signal and the like do not ring through the
 * phone's call system, so [DeviceCalls] cannot see them; they announce a call with a notification
 * in the system's call style, which carries the caller and the Answer and Decline actions. Only
 * notifications in the call category are read, and only while "Incoming calls" is on.
 *
 * `adb shell setprop debug.vela.tune.callProbe 1` logs the shape of each call notification
 * (app, category, button labels), never the caller or any text.
 */
class DeviceMediaListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.category != Notification.CATEGORY_CALL) return
        probe("posted", sbn)
        val calls = DeviceCalls.running ?: return
        val x = n.extras
        // The call style says which way the call goes: 1 is incoming and still ringing.
        // Answered, it becomes 2 (ongoing) under the same key, which ends the ring.
        if (x.getInt(EXTRA_CALL_TYPE, 0) != CALL_TYPE_INCOMING) {
            calls.onAppCallEnded(sbn.key)
            return
        }
        @Suppress("DEPRECATION")
        val person = x.getParcelable<Person>(EXTRA_CALL_PERSON)
        @Suppress("DEPRECATION")
        calls.onAppCallRinging(
            DeviceCalls.AppCall(
                key = sbn.key,
                pkg = sbn.packageName,
                name = person?.name?.toString() ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                // The style stores both intents by name; the buttons are the fallback, where
                // the style puts Decline first and Answer last.
                answer = x.getParcelable<PendingIntent>(EXTRA_ANSWER_INTENT) ?: n.actions?.lastOrNull()?.actionIntent,
                decline = x.getParcelable<PendingIntent>(EXTRA_DECLINE_INTENT) ?: n.actions?.firstOrNull()?.actionIntent,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.notification?.category != Notification.CATEGORY_CALL) return
        probe("removed", sbn)
        DeviceCalls.running?.onAppCallEnded(sbn.key)
    }

    private fun probe(what: String, sbn: StatusBarNotification) {
        if (!app.vela.ui.AppTune.localOn("callProbe")) return
        val n = sbn.notification
        val x = n.extras
        val actions = n.actions?.joinToString(",") { a ->
            // A button's label is the app's own wording ("Answer", "Decline"), not user data.
            val activity = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && a.actionIntent?.isActivity == true
            "${a.title}${if (activity) "(activity)" else ""}"
        } ?: "-"
        Log.i(
            "VelaCallProbe",
            "$what pkg=${sbn.packageName} ongoing=${sbn.isOngoing} channel=${n.channelId} " +
                "template=${x.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')} " +
                "callType=${x.getInt(EXTRA_CALL_TYPE, -1)} hasPerson=${x.containsKey(EXTRA_CALL_PERSON)} " +
                "actions=[$actions]",
        )
    }

    companion object {
        // Notification.CallStyle's extras. The names are the platform's and are stable, but
        // the constants for them are not all public.
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_CALL_PERSON = "android.callPerson"
        private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private const val CALL_TYPE_INCOMING = 1

        fun component(ctx: Context) = ComponentName(ctx, DeviceMediaListener::class.java)

        fun hasAccess(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}
