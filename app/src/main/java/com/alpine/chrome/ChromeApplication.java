package com.alpine.chrome;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.alpine.chrome.engine.Prefs;

public class ChromeApplication extends Application {

    public static final String CHANNEL_SESSION = "chromium_session";

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        createNotificationChannels();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_SESSION,
                getString(R.string.notif_channel_session),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Visible only while an active Chromium session is running");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
