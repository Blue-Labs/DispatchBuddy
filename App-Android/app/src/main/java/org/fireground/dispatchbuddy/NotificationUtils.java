package org.fireground.dispatchbuddy;

import android.annotation.SuppressLint;
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
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;

/**
 * Created by david on 2/12/18.
 *
 *
 03-03 06:51:56.914 W/MediaPlayer: Couldn't open android.resource://org.fireground.dispatchbuddy/raw/sm_dispatch.mp3: java.io.FileNotFoundException: No resource found for: android.resource://org.fireground.dispatchbuddy/raw/sm_dispatch.mp3
 03-03 06:51:56.914 D/NuPlayerDriver: NuPlayerDriver(0xb23c82a0) created, clientPid(1854)
 03-03 06:51:56.921 E/FileSource: Failed to open file 'android.resource://org.fireground.dispatchbuddy/raw/sm_dispatch.mp3'. (No such file or directory)
 03-03 06:51:56.921 E/GenericSource: Failed to create data source!
 03-03 06:51:56.921 D/NuPlayerDriver: notifyListener_l(0xb23c82a0), (100, 1, -2147483648, -1), loop setting(0, 0)
 03-03 06:51:56.921 E/MediaPlayerNative: error (1, -2147483648)
 03-03 06:51:56.921 W/RingtonePlayer: error loading sound for android.resource://org.fireground.dispatchbuddy/raw/sm_dispatch.mp3
 java.io.IOException: Prepare failed.: status=0x1
 at android.media.MediaPlayer._prepare(Native Method)
 at android.media.MediaPlayer.prepare(MediaPlayer.java:1259)
 at com.android.systemui.media.NotificationPlayer$CreationAndCompletionThread.run(NotificationPlayer.java:96)
 03-03 06:51:56.934 D/EGL_emulation: eglMakeCurrent: 0xa2485900: ver 2 0 (tinfo 0xa2483f00)
 */



public class NotificationUtils extends ContextWrapper {
    private String TAG = "NU";
    private NotificationManager mManager;
    private NotificationCompat.Builder nb;
    public static final String ANDROID_CHANNEL_ID_HIGH = "com.fireground.org.org.fireground.dispatchbuddy.HIGH";
    public static final String ANDROID_CHANNEL_ID_DEFAULT = "com.fireground.org.org.fireground.dispatchbuddy.DEFAULT";
    public static final String ANDROID_CHANNEL_NAME = "DispatchBuddy Alerts";
    private final long[] vibrationScheme = new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400};
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public NotificationUtils(Context base) {
        super(base);

        /*
         * important bits for .O+
         * https://developer.android.com/guide/topics/ui/notifiers/notifications.html#ManageChannels
         */
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Log.i(TAG, "creating a notification channel");

            // todo: use atts for audio stream meta
            AudioAttributes att = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            Uri alarmSound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
                    + "://" + getPackageName() + "/" + R.raw.sm_dispatch);

            // Create the channel object with the unique ID MY_CHANNEL
            // todo: do i need an ANDROID_CHANNEL_ID_LOWPRIORITY and HIGHPRIORITY, one with smvfd tones, one with plink? yes
            NotificationChannel highPriorityChannel =
                    new NotificationChannel(
                            ANDROID_CHANNEL_ID_HIGH,
                            getResources().getString(R.string.appName)+" Dispatches",
                            NotificationManager.IMPORTANCE_HIGH);

            highPriorityChannel.enableLights(true);
            highPriorityChannel.enableVibration(true);
            highPriorityChannel.setLightColor(0xffff0000);
            highPriorityChannel.setBypassDnd(true);
            highPriorityChannel.setShowBadge(true);
            highPriorityChannel.setDescription("High priority messages");
            highPriorityChannel.setName("Dispatches");
            highPriorityChannel.setVibrationPattern(vibrationScheme);
            highPriorityChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            highPriorityChannel.setSound(alarmSound, att);

            Log.w(TAG, "high priority channel can bypass dnd: "+highPriorityChannel.canBypassDnd());

            // Submit the notification channel object to the notification manager
            getManager().createNotificationChannel(highPriorityChannel);

            NotificationChannel defaultPriorityChannel =
                    new NotificationChannel(
                            ANDROID_CHANNEL_ID_DEFAULT,
                            getResources().getString(R.string.appName)+" Messages",
                            NotificationManager.IMPORTANCE_DEFAULT);

            // Configure the channel's initial settings
            defaultPriorityChannel.enableLights(true);
            defaultPriorityChannel.enableVibration(true);
            defaultPriorityChannel.setLightColor(0xffff0000);
            defaultPriorityChannel.setBypassDnd(false);
            defaultPriorityChannel.setShowBadge(true);
            defaultPriorityChannel.setDescription("Low priority messages");
            defaultPriorityChannel.setName("messages and updates");
            defaultPriorityChannel.setVibrationPattern(vibrationScheme);
            defaultPriorityChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            //defaultPriorityChannel.setSound(null, att);

            // Submit the notification channel object to the notification manager
            getManager().createNotificationChannel(defaultPriorityChannel);
        }
    }

    // TODO: pass priority so we can send low priority notes too
    public NotificationCompat.Builder getNotificationBuilder(String title, String body, Integer priority, String isotimestamp, Boolean groupSummary) {

        Bitmap notificationLargeIconBitmap = BitmapFactory.decodeResource(
                getApplicationContext().getResources(),
                R.mipmap.ic_launcher);

        Intent resultIntent = new Intent(this, ActivityDispatches.class);

        // Because clicking the notification opens a new ("special") activity, there's
        // no need to create an artificial back stack.
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        // todo: with config setting, use setFullScreenIntent()
        // todo: review setPublicVersion
//                .setDefaults(Notification.DEFAULT_ALL)
        String CH_ID = priority == NotificationCompat.PRIORITY_HIGH ? ANDROID_CHANNEL_ID_HIGH:ANDROID_CHANNEL_ID_DEFAULT;

        NotificationCompat.Builder n = new NotificationCompat.Builder(getApplicationContext(), CH_ID)
                .setPriority(priority)
                .setSmallIcon(R.mipmap.ic_dispatchbuddy_foreground)
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_launcher))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentTitle(title)
                .setContentText(body)
                .setVibrate(vibrationScheme)
                .setLights(0xffff0000, 200, 200)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.ic_dispatchbuddy_background))
                .setContentIntent(resultPendingIntent)
                .setAutoCancel(true);

        // TODO: bug, the S5 isn't showing the status update, it doesn't change the existing notification content

        if (groupSummary) {
            Log.i(TAG, "setting groupSummary");
            n.setGroupSummary(true);
            n.setShowWhen(true);

            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                Log.i(TAG, "applying mp3 dispatch tone");
                n.setSound(Uri.parse("android.resource://"+this.getPackageName()+"/"+R.raw.sm_dispatch));
            } else {
                Log.i(TAG, "NOT applying mp3 dispatch tone due to Build.VERSION, should play in channel instead");
            }
        }
        n.setGroup(isotimestamp);

        if (priority == NotificationCompat.PRIORITY_HIGH) {
            n.setUsesChronometer(true);
        }

        return n;

    }

    public NotificationManager getManager() {
        if (mManager == null) {
            mManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (mManager == null) {
                Log.e(TAG, "??, NO NOTIFICATION SERVICE??");
            }
        }
        return mManager;
    }

    // todo: why is this suppression needed?

    @SuppressLint("WrongConstant")
    public void sendNotification(String nature, String address, Integer priority, String isotimestamp, Boolean groupSummary) {
        /*
         * isotimestamp is used as the notification group key
         */
        long getTime;
        getManager();

        Log.d("sN:", "nature: "+nature);
        Log.d("sN:", "priority: "+priority);
        Log.d("sN:", "isotimestamp: "+isotimestamp);
        Log.d("sN:", "groupSummary: "+groupSummary);

        Integer previousNotificationInterruptSetting=0;
        NotificationCompat.Builder nb = getNotificationBuilder(nature, address, priority, isotimestamp, groupSummary);

        AudioManager audio = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        int currentMode = audio.getRingerMode();
        int currentNotificationVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        int maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);

        Log.w("sN:", "current mode is "+currentMode);
        Log.w("sN:", "current Nvol is "+currentNotificationVolume);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            previousNotificationInterruptSetting = mManager.getCurrentInterruptionFilter();
            if (mManager.isNotificationPolicyAccessGranted()) {
                mManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            } else {
                // can't do this, need Activity context
//                Toast.makeText(this, "Note, cannot modify DnD. Check Override Do Not Disturb under Settings -> Apps & notifications -> App info -> DispatchBuddy -> App notifications -> Categories (click) -> Override Do Not Disturb", Toast.LENGTH_LONG).show();
            }
        }

        if (currentMode != AudioManager.RINGER_MODE_NORMAL) {
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        }
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxVolume, AudioManager.FLAG_PLAY_SOUND);

        try {
            getTime = sdf.parse(isotimestamp).getTime();
        } catch (ParseException e) {
            Log.e("sN:", "failed to parse timestamp into a Date long"+e.getMessage());
            getTime = 101;
        }


        mManager.notify((int) getTime, nb.build());
        Log.i("sN:", "notification emitted, group: "+isotimestamp);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (mManager.isNotificationPolicyAccessGranted()) {
                mManager.setInterruptionFilter(previousNotificationInterruptSetting);
            }
        }

        // do i need to wait until some future time?
        if (currentMode != AudioManager.RINGER_MODE_NORMAL) {
            audio.setRingerMode(currentMode);
        }
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, currentNotificationVolume, AudioManager.FLAG_PLAY_SOUND);
    }
}
