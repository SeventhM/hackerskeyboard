package org.pocketworkstation.pckeyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

public class NotificationPermissionReceiver extends BroadcastReceiver {

    static final String TAG = "PCKeyboard/NotifPerm";
    static final String ACTION_NOTIFICATION_GRANTED =
        "org.pocketworkstation.pckeyboard.NOTIFICATION_GRANTED";
    static final String ACTION_NOTIFICATION_DENIED =
        "org.pocketworkstation.pckeyboard.NOTIFICATION_DENIED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "NotificationPermissionReceiver.onReceive called, action=" + action);
        if (action != null && action.equals(ACTION_NOTIFICATION_GRANTED)) {
            context.unregisterReceiver(this);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().putBoolean(LatinIME.PREF_KEYBOARD_NOTIFICATION, true).apply();
        } else if (action != null && action.equals(ACTION_NOTIFICATION_DENIED)) {
            context.unregisterReceiver(this);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().putBoolean(LatinIME.PREF_KEYBOARD_NOTIFICATION, false).apply();
        }
    }
}
