package org.fireground.dispatchbuddy;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.support.v7.app.AppCompatActivity;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Created by david on 2/18/18.
 */

public class DispatchBuddyFirebaseMessagingService extends FirebaseMessagingService {
    private final String TAG = "FCM";
    private Context context;

    // https://github.com/firebase/quickstart-android/blob/master/messaging/app/src/main/java/com/google/firebase/quickstart/fcm/MyFirebaseMessagingService.java

    // todo: see this url for posting the data msg: https://stackoverflow.com/questions/37711082/how-to-handle-notification-when-app-in-background-in-firebase/37845174#37845174
    // todo: for PY see https://github.com/olucurious/PyFCM

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {

        // https://stackoverflow.com/questions/37711082/how-to-handle-notification-when-app-in-background-in-firebase/37845174#37845174

        Log.i(TAG, "remoteMsg  From: "+remoteMessage.getFrom());
        Log.i(TAG, "remoteMsg MsgID: "+remoteMessage.getMessageId());
        Log.i(TAG, "remoteMsg  Type: "+remoteMessage.getMessageType());
        Log.i(TAG, "remoteMsg    TO: "+remoteMessage.getTo());
        if (remoteMessage.getNotification() != null) {
            Log.i(TAG, "remoteMsg    TO: " + remoteMessage.getNotification());
            Log.i(TAG, "remoteMsg  Noti: " + remoteMessage.getNotification().getTitle());
            Log.i(TAG, "remoteMsg  Noti: " + remoteMessage.getNotification().getBody());
            Log.i(TAG, "remoteMsg  Noti: " + remoteMessage.getNotification().getTag());
            Log.i(TAG, "remoteMsg  Noti: " + remoteMessage.getNotification().getLink());
        }
        Log.i(TAG, "remoteMsg STime: "+remoteMessage.getSentTime());
        Log.i(TAG, "remoteMsg  CKey: "+remoteMessage.getCollapseKey());
        Log.i(TAG, "remoteMsg   TTL: "+remoteMessage.getTtl());
        Log.i(TAG, "remoteMsg   ToS: "+remoteMessage.toString());

        Map<String, String> data = remoteMessage.getData();

        String dataS = data.toString();
        String nature = data.get("nature");
        String address = data.get("address");
        String isotimestamp = data.get("isotimestamp");

        Log.i(TAG, "data:"+dataS);
        Log.i(TAG, "nature:"+nature);
        Log.i(TAG, "address:"+address);
        Log.i(TAG, "isotimestamp:"+isotimestamp);

        // todo: need permission to wake the phone?

        // https://stackoverflow.com/questions/41925692/how-to-communicate-between-firebase-messaging-service-and-activity-android
        // https://stackoverflow.com/questions/38781270/calling-activity-class-method-from-firebasemessagingservice-class
        //turnOnScreen(); // todo: not working
        // research: show when locked: https://stackoverflow.com/questions/14352648/how-to-lock-unlock-screen-programmatically
        //  **research: https://stackoverflow.com/questions/40259780/wake-up-device-programmatically

        NotificationUtils n = new NotificationUtils(this);

        n.sendNotification(nature, address, NotificationCompat.PRIORITY_MAX, isotimestamp, false);

        if (!MainActivity.isActivityVisible()) {
            Intent i = new Intent(DispatchBuddyFirebaseMessagingService.this, MainActivity.class);
            startActivity(i);
        }
    }

    private void turnOnScreen() {
        PowerManager.WakeLock screenLock = null;
        if ((getSystemService(POWER_SERVICE)) != null) {
            screenLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "TAG");
            screenLock.acquire(10*60*1000L /*10 minutes*/);


            screenLock.release();
        }
    }
}
