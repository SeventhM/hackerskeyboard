package org.pocketworkstation.pckeyboard;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class NotificationPermission extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        @NonNull String[] permissions,
        @NonNull int[] grantResults
    ) {
        if (requestCode == 0) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sendBroadcast(new Intent(NotificationPermissionReceiver.ACTION_NOTIFICATION_GRANTED));
            }
            else sendBroadcast(new Intent(NotificationPermissionReceiver.ACTION_NOTIFICATION_DENIED));
        }
        finish();
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
