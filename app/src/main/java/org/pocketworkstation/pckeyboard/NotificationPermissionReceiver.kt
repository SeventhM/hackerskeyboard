package org.pocketworkstation.pckeyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager

class NotificationPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "NotificationPermissionReceiver.onReceive called, action=$action")
        if (action != null && action == ACTION_NOTIFICATION_GRANTED) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putBoolean(LatinIME.PREF_KEYBOARD_NOTIFICATION, true).apply()
            context.unregisterReceiver(this)
        } else if (action != null && action == ACTION_NOTIFICATION_DENIED) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putBoolean(LatinIME.PREF_KEYBOARD_NOTIFICATION, false).apply()
            context.unregisterReceiver(this)
        }
    }

    companion object {
        const val TAG = "PCKeyboard/NotifPerm"
        const val ACTION_NOTIFICATION_GRANTED =
            "org.pocketworkstation.pckeyboard.NOTIFICATION_GRANTED"
        const val ACTION_NOTIFICATION_DENIED =
            "org.pocketworkstation.pckeyboard.NOTIFICATION_DENIED"
    }
}
