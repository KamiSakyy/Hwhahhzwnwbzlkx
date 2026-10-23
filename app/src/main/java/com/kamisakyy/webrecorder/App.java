package com.kamisakyy.webrecorder;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/** Application entry point: creates the notification channels used by the recorder. */
public class App extends Application {

    public static final String CHANNEL_RECORDING = "recording";
    public static final String CHANNEL_EVENTS = "events";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            NotificationChannel rec = new NotificationChannel(
                    CHANNEL_RECORDING,
                    "Запись экрана",
                    NotificationManager.IMPORTANCE_LOW);
            rec.setDescription("Постоянное уведомление во время записи");
            rec.setShowBadge(false);
            rec.setSound(null, null);
            nm.createNotificationChannel(rec);

            NotificationChannel events = new NotificationChannel(
                    CHANNEL_EVENTS,
                    "Сохранение видео",
                    NotificationManager.IMPORTANCE_DEFAULT);
            events.setDescription("Уведомления о сохранении записи в галерею");
            nm.createNotificationChannel(events);
        }
    }
}
