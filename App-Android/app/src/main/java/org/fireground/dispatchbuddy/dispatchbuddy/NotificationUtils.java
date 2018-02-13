package org.fireground.dispatchbuddy.dispatchbuddy;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

/**
 * Created by david on 2/12/18.
 *
 */

public class NotificationUtils extends ContextWrapper {
    private NotificationManager mManager;
    private NotificationCompat.Builder nb;
    public static final String ANDROID_CHANNEL_ID = "com.fireground.org.dispatchbuddy.ANDROID";
    public static final String ANDROID_CHANNEL_NAME = "DispatchBuddy Alerts";
    private final long[] vibrationScheme = new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400};

    public NotificationUtils(Context base) {
        super(base);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {

            // Create the channel object with the unique ID MY_CHANNEL
            NotificationChannel myChannel =
                    new NotificationChannel(
                            ANDROID_CHANNEL_ID,
                            getResources().getString(R.string.app_name),
                            NotificationManager.IMPORTANCE_MAX);

            // Configure the channel's initial settings
            myChannel.enableLights(true);
            myChannel.enableVibration(true);
            myChannel.setLightColor(Color.RED);
            myChannel.setVibrationPattern(vibrationScheme);
            myChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            // Submit the notification channel object to the notification manager
            getManager().createNotificationChannel(myChannel);

        }
    }

    // TODO: pass priority so we can send low priority notes too
    public NotificationCompat.Builder getNotificationBuilder(String title, String body) {

        Bitmap notificationLargeIconBitmap = BitmapFactory.decodeResource(
                getApplicationContext().getResources(),
                R.mipmap.ic_launcher);

        Intent resultIntent = new Intent(this, DispatchesActivity.class);

        // Because clicking the notification opens a new ("special") activity, there's
        // no need to create an artificial back stack.
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        Uri alarmSound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
                                    + "://" + getPackageName() + "/raw/sm_dispatch.mp3");

        return new NotificationCompat.Builder(getApplicationContext(), ANDROID_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_info_black_24dp)
                .setLargeIcon(notificationLargeIconBitmap)
                .setContentTitle(title)
                .setContentText(body)
                .setSound(alarmSound)
                .setVibrate(vibrationScheme)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary))
                .setContentIntent(resultPendingIntent)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true);
    }

    public NotificationManager getManager() {
        if (mManager == null) {
            mManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        return mManager;
    }

    /*public NotificationCompat.Builder getAndroidChannelNotification(String title, String body) {

        NotificationCompat.Builder nb =
                new NotificationCompat.Builder(getApplicationContext(), ANDROID_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true);

        nb.setContentIntent(resultPendingIntent);
        return nb;
    }*/
}
