package org.pocketworkstation.pckeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class NotificationPermission: AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 0) {
            if(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sendBroadcast(Intent(NotificationPermissionReceiver.ACTION_NOTIFICATION_GRANTED))
            }
            else {
                sendBroadcast(Intent(NotificationPermissionReceiver.ACTION_NOTIFICATION_DENIED))
            }
            finish()
        }
        else super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}