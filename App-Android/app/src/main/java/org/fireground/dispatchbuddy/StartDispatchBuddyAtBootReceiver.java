package org.fireground.dispatchbuddy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by david on 2/12/18.
 *
 * TODO: this needs to check if a user is logged in, if not, stay in foreground in login screen. if user is logged in, go to background
 */

public class StartDispatchBuddyAtBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Intent i = new Intent(context, ActivityMain.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }

}
