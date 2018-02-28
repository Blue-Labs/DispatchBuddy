package org.fireground.dispatchbuddy;

import android.content.Context;
import android.os.PowerManager;
import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

/**
 * Created by david on 2/18/18.
 */

public class windowAndPower {
    public static String TAG = "wAP";

    public static void setWindowParameters(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setScreenAPI27(a);
        } else {
            setScreenAPIunder27(a);
        }
    }

    // https://stackoverflow.com/questions/14741612/android-wake-up-and-unlock-device
    @TargetApi(Build.VERSION_CODES.O_MR1)
    private static void setScreenAPI27(Activity a) {
        a.setShowWhenLocked(true);
        a.setTurnScreenOn(true);
    }

    @SuppressWarnings("deprecation")
    private static void setScreenAPIunder27(Activity a) {
        Window window = a.getWindow();
        Log.e("wAP", "trying to set window params: "+window);

        // temporarily dismiss secure keyguard (deprecated in API 27)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        // dismiss non-secure keyguard
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        // as long as this window is visible to the user, keep the device screen on and bright
        //window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // (deprecated in API 27)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        // allow device timeout to lock even when DispatchBuddy window is up
        // this should applied to DispatchesActivity too
        window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
    }

    // todo: https://stackoverflow.com/questions/19074466/android-how-to-show-dialog-or-activity-over-lock-screen-not-unlock-the-screen/25707716
    @SuppressWarnings("deprecation")
    public static void unlockAndShowScreen(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean isInteractive=false;
        boolean isDeviceIdle=false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            isInteractive = pm.isInteractive();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isDeviceIdle = pm.isDeviceIdleMode();
        }

        // this is deprecated -- not that it doesn't lie to us anyway, it reports my physical S8+ screen is on even when black
        boolean isScreenOn =    pm.isScreenOn();

        Log.d(TAG,"screen interactive........................"+isInteractive);
        Log.d(TAG,"screen idle..............................."+isDeviceIdle);
        Log.d(TAG,"screen on................................."+isScreenOn);

        // is pm.* lying to us
        if(true || isInteractive==false)
        {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP |PowerManager.ON_AFTER_RELEASE,"MyLock");
            wl.acquire(10000);
        }
    }
}
