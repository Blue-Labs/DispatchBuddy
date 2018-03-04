package org.fireground.dispatchbuddy;

import android.util.Log;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.FirebaseInstanceIdService;

import static org.fireground.dispatchbuddy.DispatchBuddyBase.*;

/**
 * Created by david on 2/18/18.
 */


/*
 * test to see if this is really needed since we have the auth changed listener
 * inside DBB now
 */
public class DispatchBuddyFirebaseInstanceIDService extends FirebaseInstanceIdService {
    @Override
    public void onTokenRefresh() {
        //registration token
        String registrationToken = getRegToken();

        Log.i("DBFIIDS", "storing updated registration token: "+registrationToken);
        DispatchBuddyBase.pushFirebaseClientRegistrationData(registrationToken);
    }
}
