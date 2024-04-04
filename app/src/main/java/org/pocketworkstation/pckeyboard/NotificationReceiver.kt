package org.pocketworkstation.pckeyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.inputmethod.InputMethodManager

class NotificationReceiver internal constructor(private val mIME: LatinIME) :
    BroadcastReceiver() {
    init {
        Log.i(TAG, "NotificationReceiver created, ime=$mIME")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "NotificationReceiver.onReceive called, action=$action")

        if (action != null && action == ACTION_SHOW) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mIME.requestShowSelf(InputMethodManager.SHOW_FORCED)
            }
            else imm.showSoftInputFromInputMethod(
                mIME.mToken,
                InputMethodManager.SHOW_FORCED
            )

        } else if (action != null && action == ACTION_SETTINGS) {
            context.startActivity(Intent(mIME, LatinIMESettings::class.java))
        }
    }

    companion object {
        const val TAG = "PCKeyboard/Notification"
        const val ACTION_SHOW = "org.pocketworkstation.pckeyboard.SHOW"
        const val ACTION_SETTINGS = "org.pocketworkstation.pckeyboard.SETTINGS"
    }
}
